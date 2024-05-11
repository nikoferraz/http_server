package HTTPServer;

import java.io.*;
import java.net.Socket;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.logging.Level;

class ProcessRequest implements Runnable {

    private static final int MAX_REQUEST_SIZE = 8192;
    private static final int MAX_HEADERS_SIZE = 8192;
    private static final int REQUEST_TIMEOUT_MS = 5000;
    private static final long MAX_FILE_SIZE = 1073741824L;
    private static final int HTTP_OK = 200;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_METHOD_NOT_ALLOWED = 405;
    private static final int HTTP_PAYLOAD_TOO_LARGE = 413;
    private static final int BUFFER_SIZE = 8192;
    private static final int BUFFER_POOL_SIZE = 1000;

    private static final BufferPool bufferPool = new BufferPool(BUFFER_SIZE, BUFFER_POOL_SIZE);
    private final File webroot;
    private final Socket socket;
    private Logger auditLog;
    private Logger errorLog = Logger.getLogger("errors");
    private ServerConfig config;
    private CompressionHandler compressionHandler;
    private CacheManager cacheManager;
    private boolean useHTTP11 = true;

    private MetricsCollector metrics;
    private HealthCheckHandler healthCheckHandler;
    private RequestBodyParser bodyParser;
    private RateLimiter rateLimiter;
    private StructuredLogger structuredLogger;
    private String requestId;

    private AuthenticationManager authManager;
    private VirtualHostManager vhostManager;
    private RoutingEngine routingEngine;

    private SecurityHeadersHandler securityHeadersHandler;
    private TraceContextHandler traceContextHandler;
    private GracefulShutdownHandler gracefulShutdownHandler;

    public ProcessRequest(File webroot, Socket socket, Logger auditLog) {
        this(webroot, socket, auditLog, new ServerConfig());
    }

    public ProcessRequest(File webroot, Socket socket, Logger auditLog, ServerConfig config) {
        this.webroot = webroot;
        this.socket = socket;
        this.auditLog = auditLog;
        this.config = config;
        this.compressionHandler = new CompressionHandler();
        this.cacheManager = new CacheManager();

        this.metrics = MetricsCollector.getInstance();
        this.healthCheckHandler = new HealthCheckHandler(webroot, cacheManager, compressionHandler, metrics);
        this.bodyParser = new RequestBodyParser(config.getRequestBodyMaxSizeBytes());
        this.structuredLogger = new StructuredLogger(auditLog, config.isJsonLogging(), config.getLoggingLevel());
        this.requestId = structuredLogger.generateRequestId();

        this.authManager = new AuthenticationManager(config);
        this.vhostManager = new VirtualHostManager(config, webroot);
        this.routingEngine = new RoutingEngine(config);

        this.securityHeadersHandler = new SecurityHeadersHandler();
        this.traceContextHandler = new TraceContextHandler();
    }

    public void setGracefulShutdownHandler(GracefulShutdownHandler handler) {
        this.gracefulShutdownHandler = handler;
    }

    public void setRateLimiter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void run() {
        boolean keepAlive = true;
        int requestCount = 0;
        long totalStartTime = System.currentTimeMillis();

        try {
            if (gracefulShutdownHandler != null) {
                gracefulShutdownHandler.incrementActiveConnections();
            }

            if (config.isMetricsEnabled()) {
                metrics.incrementGauge("http_active_connections");
            }

            if (gracefulShutdownHandler != null && gracefulShutdownHandler.isShuttingDown()) {
                try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                     Writer writer = new OutputStreamWriter(outputStream)) {
                    String httpVersion = "HTTP/1.1";
                    writer.write(httpVersion + " 503 Service Unavailable\r\n");
                    writer.write("Content-Length: 0\r\n");
                    writer.write("Connection: close\r\n");
                    writer.write("\r\n");
                    writer.flush();
                }
                return;
            }

            int socketTimeout = config.isKeepAliveEnabled() ?
                config.getKeepAliveTimeoutMs() : REQUEST_TIMEOUT_MS;
            socket.setSoTimeout(socketTimeout);

            BufferedReader inputStream = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
            );

            while (keepAlive && requestCount < config.getKeepAliveMaxRequests()) {
                long startTime = System.currentTimeMillis();
                String method = null;
                String path = null;
                int statusCode = 500;
                long responseSize = 0;

                try {
                    String requestLine = inputStream.readLine();
                    if (requestLine == null || requestLine.isEmpty()) {
                        break;
                    }

                    if (requestLine.length() >= MAX_REQUEST_SIZE) {
                        throw new IOException("Request line too long");
                    }

                    String[] parts = requestLine.split("\\s+");
                    if (parts.length < 2) {
                        throw new IOException("Invalid HTTP request format");
                    }

                    method = parts[0];
                    path = parts[1];

                    Map<String, String> headers = new HashMap<>();
                    String headerLine;
                    int totalHeadersSize = requestLine.length();
                    while ((headerLine = inputStream.readLine()) != null && !headerLine.isEmpty()) {
                        totalHeadersSize += headerLine.length() + 2;
                        if (totalHeadersSize > MAX_HEADERS_SIZE) {
                            throw new IOException("Request headers size exceeds maximum allowed size");
                        }

                        int colonIndex = headerLine.indexOf(':');
                        if (colonIndex > 0) {
                            String name = headerLine.substring(0, colonIndex).trim();
                            String value = headerLine.substring(colonIndex + 1).trim();
                            headers.put(name.toLowerCase(), value);
                        }
                    }

                    String version = (parts.length > 2) ? parts[2] : "HTTP/1.0";
                    String connectionHeader = headers.get("connection");
                    keepAlive = config.isKeepAliveEnabled() && shouldKeepAlive(connectionHeader, version);

                    TraceContextHandler.TraceContext traceContext =
                        traceContextHandler.extractContext(headers.get("traceparent"));
                    String traceId = traceContext.getTraceId();
                    String spanId = traceContext.getSpanId();
                    String traceparent = traceContext.toTraceparent();

                    if (config.isRateLimitEnabled() && rateLimiter != null) {
                        String clientIp = socket.getRemoteSocketAddress().toString();
                        RateLimiter.RateLimitResult rateLimitResult = rateLimiter.tryAcquire(clientIp);

                        if (!rateLimitResult.isAllowed()) {
                            statusCode = 429;
                            try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                                 Writer writer = new OutputStreamWriter(outputStream)) {
                                sendRateLimitExceeded(writer, version, rateLimitResult, headers, keepAlive);
                            }
                            if (!keepAlive) {
                                break;
                            }
                            requestCount++;
                            continue;
                        }

                        headers.put("x-ratelimit-limit", String.valueOf(rateLimitResult.getLimit()));
                        headers.put("x-ratelimit-remaining", String.valueOf(rateLimitResult.getRemaining()));
                        headers.put("x-ratelimit-reset", String.valueOf(rateLimitResult.getResetTime()));
                    }

                    if (requestCount >= config.getKeepAliveMaxRequests() - 1) {
                        keepAlive = false;
                    }

                    routeRequest(parts, headers, inputStream, keepAlive, traceId, traceparent);
                    statusCode = 200;

                } catch (java.net.SocketTimeoutException e) {
                    auditLog.fine("Keep-alive timeout for " + socket.getRemoteSocketAddress());
                    break;
                } catch (UnsupportedEncodingException e) {
                    statusCode = 400;
                    errorLog.log(Level.WARNING, "Unsupported encoding in request", e);
                    structuredLogger.logError(requestId, "Unsupported encoding", e);
                    keepAlive = false;
                } catch (IOException e) {
                    statusCode = 500;
                    errorLog.log(Level.WARNING, "IO error processing request", e);
                    structuredLogger.logError(requestId, "IO error processing request", e);
                    keepAlive = false;
                } finally {
                    if (config.isMetricsEnabled()) {
                        long duration = System.currentTimeMillis() - startTime;
                        metrics.recordRequest(method != null ? method : "UNKNOWN", statusCode, duration, responseSize);

                        String remoteIp = socket.getRemoteSocketAddress().toString();
                        structuredLogger.logRequest(requestId, remoteIp, method != null ? method : "UNKNOWN",
                                                  path != null ? path : "/", statusCode, duration, responseSize);
                    }
                }

                requestCount++;
            }

        } catch (Exception e) {
            errorLog.log(Level.SEVERE, "Error in request handler", e);
        } finally {
            if (gracefulShutdownHandler != null) {
                gracefulShutdownHandler.decrementActiveConnections();
            }

            try {
                socket.close();
            } catch (IOException e) {
                errorLog.log(Level.WARNING, "Error closing socket", e);
            }

            if (config.isMetricsEnabled()) {
                metrics.decrementGauge("http_active_connections");
                long totalDuration = System.currentTimeMillis() - totalStartTime;
                auditLog.info("Connection closed: " + requestCount + " requests, " + totalDuration + "ms");
            }
        }
    }

    private boolean shouldKeepAlive(String connectionHeader, String httpVersion) {
        if (connectionHeader == null) {
            return httpVersion != null && httpVersion.startsWith("HTTP/1.1");
        }

        String normalized = connectionHeader.toLowerCase().trim();
        if (normalized.contains("close")) {
            return false;
        }
        if (normalized.contains("keep-alive")) {
            return true;
        }

        return httpVersion != null && httpVersion.startsWith("HTTP/1.1");
    }

    public void routeRequest(String[] requestHeader, Map<String, String> headers, BufferedReader inputStream) throws IOException {
        routeRequest(requestHeader, headers, inputStream, true, null, null);
    }

    public void routeRequest(String[] requestHeader, Map<String, String> headers, BufferedReader inputStream, boolean keepAlive) throws IOException {
        routeRequest(requestHeader, headers, inputStream, keepAlive, null, null);
    }

    public void routeRequest(String[] requestHeader, Map<String, String> headers, BufferedReader inputStream, boolean keepAlive, String traceId, String traceparent) throws IOException {
        if (requestHeader.length < 2) {
            throw new IOException("Invalid HTTP request: missing method or path");
        }

        String method = requestHeader[0];
        String path = requestHeader[1];
        String version = (requestHeader.length > 2) ? requestHeader[2] : "HTTP/1.0";

        useHTTP11 = version.startsWith("HTTP/1.1");

        if (useHTTP11 && !headers.containsKey("host")) {
            try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                 Writer writer = new OutputStreamWriter(outputStream)) {
                sendBadRequest(writer, "HTTP/1.1 requires Host header", keepAlive);
            }
            return;
        }

        if (routingEngine.isEnabled()) {
            RoutingEngine.RoutingResult routingResult = routingEngine.processRequest(path);
            if (routingResult.isRedirect()) {
                try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                     Writer writer = new OutputStreamWriter(outputStream)) {
                    routingEngine.sendRedirect(writer, version, routingResult.getRedirectStatusCode(),
                                              routingResult.getRedirectLocation(), headers);
                }
                return;
            } else if (routingResult.wasModified()) {
                path = routingResult.getPath();
            }
        }

        if (config.isHealthChecksEnabled() && path.startsWith("/health/")) {
            try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                 Writer writer = new OutputStreamWriter(outputStream)) {
                healthCheckHandler.handleHealthCheck(writer, path, version);
            }
            return;
        }

        if (config.isMetricsEnabled() && path.equals("/metrics")) {
            try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                 Writer writer = new OutputStreamWriter(outputStream)) {
                handleMetricsEndpoint(writer, version, keepAlive);
            }
            return;
        }

        if (path.startsWith("/api/")) {
            try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                 Writer writer = new OutputStreamWriter(outputStream)) {
                handleApiEndpoint(method, path, version, headers, inputStream, writer, keepAlive);
            }
            return;
        }

        if (path == null || path.isEmpty() || path.equals("/")) {
            try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                 Writer writer = new OutputStreamWriter(outputStream)) {
                resourceNotFound(writer, version, headers, keepAlive);
            }
            return;
        }

        String fileName = path;
        fileName = fileName.substring(1);

        File resolvedWebroot = vhostManager.resolveWebroot(headers);

        Path requestedPath = validateAndNormalizePath(fileName, resolvedWebroot);
        if (requestedPath == null) {
            try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                 Writer writer = new OutputStreamWriter(outputStream)) {
                resourceNotFound(writer, version, headers, keepAlive);
            }
            return;
        }

        if (authManager.requiresAuthentication(path)) {
            AuthenticationManager.AuthResult authResult = authManager.authenticate(headers);
            if (!authResult.isAuthenticated()) {
                try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                     Writer writer = new OutputStreamWriter(outputStream)) {
                    sendUnauthorizedResponse(writer, version, headers, keepAlive);
                    if (config.isMetricsEnabled()) {
                        metrics.incrementCounter("auth_failures", "path=" + path);
                    }
                }
                return;
            }

            structuredLogger.logInfo(requestId, "Authentication successful: user=" + authResult.getUsername() +
                                               ", method=" + authResult.getMethod());
            if (config.isMetricsEnabled()) {
                metrics.incrementCounter("auth_success", "method=" + authResult.getMethod());
            }
        }

        String mimeType = URLConnection.getFileNameMap().getContentTypeFor(fileName);
        if (mimeType == null) {
            mimeType = "application/octet-stream";
        }

        File file = requestedPath.toFile();
        System.out.println("Requesting resource: " + fileName+ "\r\n");
        System.out.println("The file mimetype is  " + mimeType+ "\r\n");

        try {
            if (file.length() > MAX_FILE_SIZE) {
                try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                     Writer writer = new OutputStreamWriter(outputStream)) {
                    sendPayloadTooLarge(writer, version, headers, keepAlive);
                }
                return;
            }

            if (!file.canRead()) {
                try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                     Writer writer = new OutputStreamWriter(outputStream)) {
                    resourceNotFound(writer, version, headers, keepAlive);
                }
                return;
            }

            try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                 Writer writer = new OutputStreamWriter(outputStream)) {
                switch (method) {
                    case "GET":
                        HTTPGet(outputStream, writer, file, mimeType, version, headers, keepAlive);
                        break;
                    case "HEAD":
                        HTTPHead(writer, file, mimeType, version, headers, keepAlive);
                        break;
                    default:
                        invalidVerb(writer, version, headers, keepAlive);
                        break;
                }
            }
        } catch (UnsupportedEncodingException e) {
            errorLog.log(Level.WARNING, "Unsupported encoding in route", e);
        } catch (IOException e) {
            errorLog.log(Level.WARNING, "IO error in route", e);
        }
    }

    private void HTTPGet(OutputStream outputStream, Writer writer, File file, String mimeType, String version, Map<String, String> headers, boolean keepAlive)
            throws IOException {
        long fileLength = file.length();
        String code = "200";

        String etag = null;
        String lastModified = null;
        if (config.isCacheEnabled()) {
            etag = cacheManager.generateETag(file);
            lastModified = cacheManager.getLastModified(file);

            if (!cacheManager.shouldServeResource(headers, file, etag)) {
                sendNotModified(writer, version, etag, lastModified, headers, keepAlive);
                logRequest("GET", file.getName(), version, "304", 0);
                return;
            }
        }

        boolean useCompression = config.isCompressionEnabled() &&
                                 compressionHandler.shouldCompress(headers, mimeType, fileLength, file.getName());

        byte[] content = null;
        int contentLength = (int) fileLength;

        if (useCompression) {
            content = compressionHandler.compressFile(file);
            if (content != null) {
                contentLength = content.length;
            } else {
                useCompression = false;
            }
        }

        if (version.startsWith("HTTP/")) {
            String httpVersion = useHTTP11 ? "HTTP/1.1" : "HTTP/1.0";
            sendHeader(writer, httpVersion + " 200 OK", mimeType, contentLength, etag, lastModified, useCompression, headers, keepAlive);
        }

        if (useCompression && content != null) {
            outputStream.write(content);
        } else {
            // Determine if we should use zero-copy for large uncompressed files
            boolean useZeroCopy = fileLength >= config.getZeroCopyThreshold();

            if (useZeroCopy) {
                sendFileZeroCopy(file, outputStream);
            } else {
                sendFileBuffered(file, outputStream);
            }
        }

        logRequest("GET", file.getName(), version, code, contentLength);
        outputStream.flush();
    }

    private void sendFileZeroCopy(File file, OutputStream outputStream) throws IOException {
        try {
            boolean used = ZeroCopyTransferHandler.transferZeroCopy(file, outputStream);
            if (!used) {
                // Fall back to buffered if zero-copy failed
                sendFileBuffered(file, outputStream);
            }
        } catch (IOException e) {
            // Fall back to buffered on any error
            sendFileBuffered(file, outputStream);
        }
    }

    private void sendFileBuffered(File file, OutputStream outputStream) throws IOException {
        ByteBuffer pooledBuffer = bufferPool.acquire();
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = pooledBuffer.array();
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        } finally {
            bufferPool.release(pooledBuffer);
        }
    }

    private void HTTPHead(Writer writer, File file, String mimeType, String version, Map<String, String> headers, boolean keepAlive) throws IOException {
        long fileLength = file.length();
        String code = "200";

        String etag = null;
        String lastModified = null;
        if (config.isCacheEnabled()) {
            etag = cacheManager.generateETag(file);
            lastModified = cacheManager.getLastModified(file);

            if (!cacheManager.shouldServeResource(headers, file, etag)) {
                sendNotModified(writer, version, etag, lastModified, headers, keepAlive);
                logRequest("HEAD", file.getName(), version, "304", 0);
                return;
            }
        }

        boolean wouldCompress = config.isCompressionEnabled() &&
                                compressionHandler.shouldCompress(headers, mimeType, fileLength, file.getName());
        int contentLength = (int) fileLength;

        if (wouldCompress) {
            byte[] compressed = compressionHandler.compressFile(file);
            if (compressed != null) {
                contentLength = compressed.length;
            } else {
                wouldCompress = false;
            }
        }

        if (version.startsWith("HTTP/")) {
            String httpVersion = useHTTP11 ? "HTTP/1.1" : "HTTP/1.0";
            sendHeader(writer, httpVersion + " 200 OK", mimeType, contentLength, etag, lastModified, wouldCompress, headers, keepAlive);
        }

        logRequest("HEAD", file.getName(), version, code, contentLength);
    }

    public void invalidVerb(Writer writer, String version, Map<String, String> headers, boolean keepAlive) throws IOException {
        String response = "<HTML>\r\n<head><title>Method Not Allowed</title></head>\r\n"
                + "<body><h1>405 Method Not Allowed</h1>\r\n"
                + "<p>The HTTP method used is not supported by this server.</p>\r\n"
                + "</body></html>\r\n";

        if (version.startsWith("HTTP/")) {
            String httpVersion = useHTTP11 ? "HTTP/1.1" : "HTTP/1.0";
            writer.write(httpVersion + " 405 Method Not Allowed\r\n");
            writer.write("Allow: GET, HEAD\r\n");
            writer.write("Content-type: text/html; charset=utf-8\r\n");
            writer.write("Content-length: " + response.length() + "\r\n");
            addCommonHeaders(writer, headers, keepAlive);
            writer.write("\r\n");
        }
        writer.write(response);
        writer.flush();
    }

    public void resourceNotFound(Writer writer, String version, Map<String, String> headers, boolean keepAlive) throws IOException {
        String response = new StringBuilder("<HTML>\r\n").append("<head><title>Resource Not Found</title></head>\r\n")
                .append("<body>").append("<h1>404 Error: File not found.</h1>\r\n").append("</body></html>\r\n")
                .toString();
        if (version.startsWith("HTTP/")) {
            String httpVersion = useHTTP11 ? "HTTP/1.1" : "HTTP/1.0";
            writer.write(httpVersion + " 404 Not Found\r\n");
            writer.write("Content-type: text/html; charset=utf-8\r\n");
            writer.write("Content-length: " + response.length() + "\r\n");
            addCommonHeaders(writer, headers, keepAlive);
            writer.write("\r\n");
        }
        writer.write(response);
        writer.flush();
    }

    private void sendHeader(Writer writer, String responseCode, String mimeType, int length) throws IOException {
        sendHeader(writer, responseCode, mimeType, length, null, null, false, null, true, null);
    }

    private void sendHeader(Writer writer, String responseCode, String mimeType, int length,
                           String etag, String lastModified, boolean compressed, Map<String, String> headers, boolean keepAlive) throws IOException {
        sendHeader(writer, responseCode, mimeType, length, etag, lastModified, compressed, headers, keepAlive, null);
    }

    private void sendHeader(Writer writer, String responseCode, String mimeType, int length,
                           String etag, String lastModified, boolean compressed, Map<String, String> headers, boolean keepAlive, String traceparent) throws IOException {
        writer.write(responseCode + "\r\n");
        writer.write("Date: " + cacheManager.getHttpDate() + "\r\n");
        writer.write("Server: HTTPServer/2.0\r\n");
        writer.write("Content-length: " + length + "\r\n");
        writer.write("Content-type: " + mimeType + "\r\n");

        if (config.isCacheEnabled() && etag != null) {
            writer.write("ETag: " + etag + "\r\n");
        }
        if (config.isCacheEnabled() && lastModified != null) {
            writer.write("Last-Modified: " + lastModified + "\r\n");
        }
        if (config.isCacheEnabled()) {
            writer.write("Cache-Control: " + cacheManager.getCacheControl("") + "\r\n");
        }

        if (compressed) {
            writer.write("Content-Encoding: gzip\r\n");
            writer.write("Vary: Accept-Encoding\r\n");
        }

        addCommonHeaders(writer, headers, keepAlive, traceparent);

        writer.write("\r\n");
        writer.flush();
    }

    private void addCommonHeaders(Writer writer, Map<String, String> headers, boolean keepAlive) throws IOException {
        addCommonHeaders(writer, headers, keepAlive, null);
    }

    private void addCommonHeaders(Writer writer, Map<String, String> headers, boolean keepAlive, String traceparent) throws IOException {
        if (useHTTP11) {
            if (keepAlive) {
                writer.write("Connection: keep-alive\r\n");
                writer.write("Keep-Alive: timeout=" + (config.getKeepAliveTimeoutMs() / 1000) +
                            ", max=" + config.getKeepAliveMaxRequests() + "\r\n");
            } else {
                writer.write("Connection: close\r\n");
            }
        }

        if (config.isTlsEnabled() && config.isHstsEnabled()) {
            String hstsHeader = config.getHstsHeader();
            if (hstsHeader != null) {
                writer.write("Strict-Transport-Security: " + hstsHeader + "\r\n");
            }
        }

        securityHeadersHandler.addSecurityHeaders(writer, config.isTlsEnabled());

        if (traceparent != null) {
            writer.write("Traceparent: " + traceparent + "\r\n");
        }
    }

    private void sendNotModified(Writer writer, String version, String etag, String lastModified, Map<String, String> headers, boolean keepAlive) throws IOException {
        String httpVersion = useHTTP11 ? "HTTP/1.1" : "HTTP/1.0";
        writer.write(httpVersion + " 304 Not Modified\r\n");
        writer.write("Date: " + cacheManager.getHttpDate() + "\r\n");

        if (etag != null) {
            writer.write("ETag: " + etag + "\r\n");
        }
        if (lastModified != null) {
            writer.write("Last-Modified: " + lastModified + "\r\n");
        }

        writer.write("Cache-Control: " + cacheManager.getCacheControl("") + "\r\n");

        addCommonHeaders(writer, headers, keepAlive);
        writer.write("\r\n");
        writer.flush();
    }

    private void sendBadRequest(Writer writer, String message, boolean keepAlive) throws IOException {
        String response = "<HTML>\r\n<head><title>Bad Request</title></head>\r\n"
                + "<body><h1>400 Bad Request</h1>\r\n"
                + "<p>" + message + "</p>\r\n"
                + "</body></html>\r\n";

        String httpVersion = useHTTP11 ? "HTTP/1.1" : "HTTP/1.0";
        writer.write(httpVersion + " 400 Bad Request\r\n");
        writer.write("Content-type: text/html; charset=utf-8\r\n");
        writer.write("Content-length: " + response.length() + "\r\n");
        writer.write("Connection: close\r\n");
        writer.write("\r\n");
        writer.write(response);
        writer.flush();
    }

    private void sendPayloadTooLarge(Writer writer, String version, Map<String, String> headers, boolean keepAlive) throws IOException {
        String response = "<HTML>\r\n<head><title>Payload Too Large</title></head>\r\n"
                + "<body><h1>413 Payload Too Large</h1>\r\n"
                + "<p>File size exceeds maximum allowed size</p>\r\n"
                + "</body></html>\r\n";

        String httpVersion = useHTTP11 ? "HTTP/1.1" : "HTTP/1.0";
        writer.write(httpVersion + " 413 Payload Too Large\r\n");
        writer.write("Content-type: text/html; charset=utf-8\r\n");
        writer.write("Content-length: " + response.length() + "\r\n");
        addCommonHeaders(writer, headers, keepAlive);
        writer.write("\r\n");
        writer.write(response);
        writer.flush();
    }

    public void logRequest(String verb, String fileName, String version, String code, int bytes){
        ZonedDateTime time = ZonedDateTime.now(ZoneId.systemDefault());
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd/MMM/yyyy HH:mm:ss Z");
        String formattedTime = time.format(dateFormatter);
        String logInfo = socket.getRemoteSocketAddress().toString() + " - - ";
        logInfo += "[" + formattedTime + "]";
        logInfo += " \"" + verb + "/" + fileName + "/" + version + "\" " + code + " " + bytes;
        auditLog.info(logInfo);
    }

    private Path validateAndNormalizePath(String fileName, File webrootToUse) {
        try {
            if (fileName.contains("..")) {
                System.err.println("Path traversal attempt detected: " + fileName);
                return null;
            }

            Path requestedPath = Paths.get(webrootToUse.getCanonicalPath(), fileName).normalize();
            Path webrootPath = Paths.get(webrootToUse.getCanonicalPath()).normalize();

            if (!requestedPath.startsWith(webrootPath)) {
                System.err.println("Path outside webroot: " + requestedPath);
                return null;
            }

            if (Paths.get(fileName).isAbsolute()) {
                System.err.println("Absolute path rejected: " + fileName);
                return null;
            }

            return requestedPath;
        } catch (IOException e) {
            System.err.println("Error validating path: " + e.getMessage());
            return null;
        }
    }

    private void sendUnauthorizedResponse(Writer writer, String version, Map<String, String> headers, boolean keepAlive) throws IOException {
        String authMethods = "Basic Authentication";
        if (authManager.isApiKeyEnabled()) {
            authMethods += ", API Key (X-API-Key header)";
        }

        String response = "<HTML>\r\n<head><title>Unauthorized</title></head>\r\n"
                + "<body><h1>401 Unauthorized</h1>\r\n"
                + "<p>Please provide valid credentials using one of: " + authMethods + "</p>\r\n"
                + "</body></html>\r\n";

        String httpVersion = useHTTP11 ? "HTTP/1.1" : "HTTP/1.0";
        writer.write(httpVersion + " 401 Unauthorized\r\n");
        writer.write("WWW-Authenticate: Basic realm=\"HTTP Server\"\r\n");

        writer.write("Content-type: text/html; charset=utf-8\r\n");
        writer.write("Content-length: " + response.length() + "\r\n");
        addCommonHeaders(writer, headers, keepAlive);
        writer.write("\r\n");
        writer.write(response);
        writer.flush();
    }

    private void handleMetricsEndpoint(Writer writer, String version, boolean keepAlive) throws IOException {
        String metricsOutput = metrics.exportPrometheusMetrics();

        String httpVersion = useHTTP11 ? "HTTP/1.1" : "HTTP/1.0";
        writer.write(httpVersion + " 200 OK\r\n");
        writer.write("Content-Type: text/plain; version=0.0.4\r\n");
        writer.write("Content-Length: " + metricsOutput.length() + "\r\n");
        if (keepAlive) {
            writer.write("Connection: keep-alive\r\n");
            writer.write("Keep-Alive: timeout=" + (config.getKeepAliveTimeoutMs() / 1000) +
                        ", max=" + config.getKeepAliveMaxRequests() + "\r\n");
        } else {
            writer.write("Connection: close\r\n");
        }
        writer.write("\r\n");
        writer.write(metricsOutput);
        writer.flush();
    }

    private void sendRateLimitExceeded(Writer writer, String version, RateLimiter.RateLimitResult result,
                                      Map<String, String> headers, boolean keepAlive) throws IOException {
        String response = String.format(
            "{\"error\":\"Too many requests\",\"retryAfter\":%d}",
            result.getRetryAfter()
        );

        String httpVersion = useHTTP11 ? "HTTP/1.1" : "HTTP/1.0";
        writer.write(httpVersion + " 429 Too Many Requests\r\n");
        writer.write("Content-Type: application/json\r\n");
        writer.write("Content-Length: " + response.length() + "\r\n");
        writer.write("X-RateLimit-Limit: " + result.getLimit() + "\r\n");
        writer.write("X-RateLimit-Remaining: " + result.getRemaining() + "\r\n");
        writer.write("X-RateLimit-Reset: " + result.getResetTime() + "\r\n");
        writer.write("Retry-After: " + result.getRetryAfter() + "\r\n");
        if (keepAlive) {
            writer.write("Connection: keep-alive\r\n");
            writer.write("Keep-Alive: timeout=" + (config.getKeepAliveTimeoutMs() / 1000) +
                        ", max=" + config.getKeepAliveMaxRequests() + "\r\n");
        } else {
            writer.write("Connection: close\r\n");
        }
        writer.write("\r\n");
        writer.write(response);
        writer.flush();
    }

    private void handleApiEndpoint(String method, String path, String version, Map<String, String> headers,
                                   BufferedReader inputStream, Writer writer, boolean keepAlive) throws IOException {
        AuthenticationManager.AuthResult authResult = authManager.authenticate(headers);
        if (!authResult.isAuthenticated()) {
            sendUnauthorizedResponse(writer, version, headers, keepAlive);
            return;
        }

        switch (path) {
            case "/api/echo":
                handleEchoEndpoint(method, version, headers, inputStream, writer, keepAlive);
                break;

            case "/api/upload":
                handleUploadEndpoint(method, version, headers, inputStream, writer, keepAlive);
                break;

            default:
                if (path.startsWith("/api/data")) {
                    handleDataEndpoint(method, path, version, headers, inputStream, writer, keepAlive);
                } else {
                    sendApiNotFound(writer, version, keepAlive);
                }
                break;
        }
    }

    private void handleEchoEndpoint(String method, String version, Map<String, String> headers,
                                   BufferedReader inputStream, Writer writer, boolean keepAlive) throws IOException {
        if (!method.equals("POST")) {
            sendMethodNotAllowed(writer, version, "POST", keepAlive);
            return;
        }

        try {
            RequestBodyParser.ParsedBody body = bodyParser.parseBody(socket.getInputStream(), headers);
            String responseBody = String.format(
                "{\"echo\":%s,\"contentType\":\"%s\",\"size\":%d}",
                body.getRawContent() != null ? "\"" + body.getRawContent() + "\"" : "null",
                body.getContentType(),
                body.getRawContent() != null ? body.getRawContent().length() : 0
            );

            String httpVersion = useHTTP11 ? "HTTP/1.1" : "HTTP/1.0";
            writer.write(httpVersion + " 200 OK\r\n");
            writer.write("Content-Type: application/json\r\n");
            writer.write("Content-Length: " + responseBody.length() + "\r\n");
            if (keepAlive) {
                writer.write("Connection: keep-alive\r\n");
                writer.write("Keep-Alive: timeout=" + (config.getKeepAliveTimeoutMs() / 1000) +
                            ", max=" + config.getKeepAliveMaxRequests() + "\r\n");
            } else {
                writer.write("Connection: close\r\n");
            }
            writer.write("\r\n");
            writer.write(responseBody);
            writer.flush();
        } catch (RequestBodyParser.PayloadTooLargeException e) {
            sendPayloadTooLarge(writer, version, headers, keepAlive);
        }
    }

    private void handleUploadEndpoint(String method, String version, Map<String, String> headers,
                                     BufferedReader inputStream, Writer writer, boolean keepAlive) throws IOException {
        if (!method.equals("POST")) {
            sendMethodNotAllowed(writer, version, "POST", keepAlive);
            return;
        }

        try {
            RequestBodyParser.ParsedBody body = bodyParser.parseBody(socket.getInputStream(), headers);
            String responseBody = String.format(
                "{\"status\":\"uploaded\",\"contentType\":\"%s\",\"size\":%d,\"parameters\":%d}",
                body.getContentType(),
                body.getRawBytes() != null ? body.getRawBytes().length : 0,
                body.getParameters().size()
            );

            String httpVersion = useHTTP11 ? "HTTP/1.1" : "HTTP/1.0";
            writer.write(httpVersion + " 200 OK\r\n");
            writer.write("Content-Type: application/json\r\n");
            writer.write("Content-Length: " + responseBody.length() + "\r\n");
            if (keepAlive) {
                writer.write("Connection: keep-alive\r\n");
                writer.write("Keep-Alive: timeout=" + (config.getKeepAliveTimeoutMs() / 1000) +
                            ", max=" + config.getKeepAliveMaxRequests() + "\r\n");
            } else {
                writer.write("Connection: close\r\n");
            }
            writer.write("\r\n");
            writer.write(responseBody);
            writer.flush();
        } catch (RequestBodyParser.PayloadTooLargeException e) {
            sendPayloadTooLarge(writer, version, headers, keepAlive);
        }
    }

    private void handleDataEndpoint(String method, String path, String version, Map<String, String> headers,
                                   BufferedReader inputStream, Writer writer, boolean keepAlive) throws IOException {
        String responseBody;
        int statusCode = 200;
        String statusText = "OK";

        switch (method) {
            case "PUT":
                try {
                    RequestBodyParser.ParsedBody body = bodyParser.parseBody(socket.getInputStream(), headers);
                    responseBody = "{\"status\":\"updated\",\"path\":\"" + path + "\"}";
                } catch (RequestBodyParser.PayloadTooLargeException e) {
                    sendPayloadTooLarge(writer, version, headers, keepAlive);
                    return;
                }
                break;

            case "DELETE":
                responseBody = "{\"status\":\"deleted\",\"path\":\"" + path + "\"}";
                break;

            case "POST":
                try {
                    RequestBodyParser.ParsedBody body = bodyParser.parseBody(socket.getInputStream(), headers);
                    responseBody = "{\"status\":\"created\",\"path\":\"" + path + "\"}";
                    statusCode = 201;
                    statusText = "Created";
                } catch (RequestBodyParser.PayloadTooLargeException e) {
                    sendPayloadTooLarge(writer, version, headers, keepAlive);
                    return;
                }
                break;

            default:
                sendMethodNotAllowed(writer, version, "PUT, DELETE, POST", keepAlive);
                return;
        }

        String httpVersion = useHTTP11 ? "HTTP/1.1" : "HTTP/1.0";
        writer.write(httpVersion + " " + statusCode + " " + statusText + "\r\n");
        writer.write("Content-Type: application/json\r\n");
        writer.write("Content-Length: " + responseBody.length() + "\r\n");
        if (keepAlive) {
            writer.write("Connection: keep-alive\r\n");
            writer.write("Keep-Alive: timeout=" + (config.getKeepAliveTimeoutMs() / 1000) +
                        ", max=" + config.getKeepAliveMaxRequests() + "\r\n");
        } else {
            writer.write("Connection: close\r\n");
        }
        writer.write("\r\n");
        writer.write(responseBody);
        writer.flush();
    }

    private void sendApiNotFound(Writer writer, String version, boolean keepAlive) throws IOException {
        String response = "{\"error\":\"API endpoint not found\"}";

        String httpVersion = useHTTP11 ? "HTTP/1.1" : "HTTP/1.0";
        writer.write(httpVersion + " 404 Not Found\r\n");
        writer.write("Content-Type: application/json\r\n");
        writer.write("Content-Length: " + response.length() + "\r\n");
        if (keepAlive) {
            writer.write("Connection: keep-alive\r\n");
            writer.write("Keep-Alive: timeout=" + (config.getKeepAliveTimeoutMs() / 1000) +
                        ", max=" + config.getKeepAliveMaxRequests() + "\r\n");
        } else {
            writer.write("Connection: close\r\n");
        }
        writer.write("\r\n");
        writer.write(response);
        writer.flush();
    }

    private void sendMethodNotAllowed(Writer writer, String version, String allowedMethods, boolean keepAlive) throws IOException {
        String response = "{\"error\":\"Method not allowed\"}";

        String httpVersion = useHTTP11 ? "HTTP/1.1" : "HTTP/1.0";
        writer.write(httpVersion + " 405 Method Not Allowed\r\n");
        writer.write("Allow: " + allowedMethods + "\r\n");
        writer.write("Content-Type: application/json\r\n");
        writer.write("Content-Length: " + response.length() + "\r\n");
        if (keepAlive) {
            writer.write("Connection: keep-alive\r\n");
            writer.write("Keep-Alive: timeout=" + (config.getKeepAliveTimeoutMs() / 1000) +
                        ", max=" + config.getKeepAliveMaxRequests() + "\r\n");
        } else {
            writer.write("Connection: close\r\n");
        }
        writer.write("\r\n");
        writer.write(response);
        writer.flush();
    }
}
