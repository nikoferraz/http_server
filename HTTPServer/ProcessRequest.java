package HTTPServer;

import java.io.*;
import java.net.Socket;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.logging.Level;

class ProcessRequest implements Runnable {

    private static final int MAX_REQUEST_SIZE = 8192; // 8KB max request size
    private static final int MAX_HEADERS_SIZE = 8192; // 8KB max headers size
    private static final int REQUEST_TIMEOUT_MS = 5000; // 5 second timeout
    private static final long MAX_FILE_SIZE = 1073741824L; // 1GB max file size
    private static final int HTTP_OK = 200;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_METHOD_NOT_ALLOWED = 405;
    private static final int HTTP_PAYLOAD_TOO_LARGE = 413;
    private final File webroot;
    private final Socket socket;
    private Logger auditLog;
    private Logger errorLog = Logger.getLogger("errors");
    private ServerConfig config;
    private CompressionHandler compressionHandler;
    private CacheManager cacheManager;
    private boolean useHTTP11 = true; // Default to HTTP/1.1

    // Phase 5: Advanced features
    private MetricsCollector metrics;
    private HealthCheckHandler healthCheckHandler;
    private RequestBodyParser bodyParser;
    private RateLimiter rateLimiter;
    private StructuredLogger structuredLogger;
    private String requestId;

    // Phase 6: Enterprise features
    private AuthenticationManager authManager;
    private VirtualHostManager vhostManager;
    private RoutingEngine routingEngine;


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

        // Phase 5: Initialize advanced features
        this.metrics = MetricsCollector.getInstance();
        this.healthCheckHandler = new HealthCheckHandler(webroot);
        this.bodyParser = new RequestBodyParser(config.getRequestBodyMaxSizeBytes());
        this.structuredLogger = new StructuredLogger(auditLog, config.isJsonLogging(), config.getLoggingLevel());
        this.requestId = structuredLogger.generateRequestId();

        // Phase 6: Initialize enterprise features
        this.authManager = new AuthenticationManager(config);
        this.vhostManager = new VirtualHostManager(config, webroot);
        this.routingEngine = new RoutingEngine(config);
    }

    public void setRateLimiter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void run() {
        long startTime = System.currentTimeMillis();
        String method = null;
        String path = null;
        int statusCode = 500;
        long responseSize = 0;

        try {
            // Phase 5: Increment active connections gauge
            if (config.isMetricsEnabled()) {
                metrics.incrementGauge("http_active_connections");
            }

            socket.setSoTimeout(REQUEST_TIMEOUT_MS);
            BufferedReader inputStream = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            // Read the request line
            String requestLine = inputStream.readLine();
            if (requestLine == null || requestLine.isEmpty() || requestLine.length() >= MAX_REQUEST_SIZE) {
                throw new IOException("Invalid HTTP request format");
            }

            String[] parts = requestLine.split("\\s+");
            if (parts.length < 2) {
                throw new IOException("Invalid HTTP request format");
            }

            method = parts[0];
            path = parts[1];

            // Read HTTP headers with size validation
            Map<String, String> headers = new HashMap<>();
            String headerLine;
            int totalHeadersSize = requestLine.length();
            while ((headerLine = inputStream.readLine()) != null && !headerLine.isEmpty()) {
                // Check if adding this header would exceed the limit
                totalHeadersSize += headerLine.length() + 2; // +2 for \r\n
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

            // Phase 5: Rate limiting check
            if (config.isRateLimitEnabled() && rateLimiter != null) {
                String clientIp = socket.getRemoteSocketAddress().toString();
                RateLimiter.RateLimitResult rateLimitResult = rateLimiter.tryAcquire(clientIp);

                if (!rateLimitResult.isAllowed()) {
                    statusCode = 429;
                    try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                         Writer writer = new OutputStreamWriter(outputStream)) {
                        sendRateLimitExceeded(writer, parts.length > 2 ? parts[2] : "HTTP/1.0",
                                            rateLimitResult, headers);
                    }
                    return;
                }

                // Add rate limit headers (will be included in response)
                headers.put("x-ratelimit-limit", String.valueOf(rateLimitResult.getLimit()));
                headers.put("x-ratelimit-remaining", String.valueOf(rateLimitResult.getRemaining()));
                headers.put("x-ratelimit-reset", String.valueOf(rateLimitResult.getResetTime()));
            }

            routeRequest(parts, headers, inputStream);
            statusCode = 200; // Assume success if no exception
        } catch (UnsupportedEncodingException e) {
            statusCode = 400;
            errorLog.log(Level.WARNING, "Unsupported encoding in request", e);
            structuredLogger.logError(requestId, "Unsupported encoding", e);
        } catch (IOException e) {
            statusCode = 500;
            errorLog.log(Level.WARNING, "IO error processing request", e);
            structuredLogger.logError(requestId, "IO error processing request", e);
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                errorLog.log(Level.WARNING, "Error closing socket", e);
            }

            // Phase 5: Decrement active connections gauge
            if (config.isMetricsEnabled()) {
                metrics.decrementGauge("http_active_connections");

                // Record request metrics
                long duration = System.currentTimeMillis() - startTime;
                metrics.recordRequest(method != null ? method : "UNKNOWN", statusCode, duration, responseSize);

                // Log structured request
                String remoteIp = socket.getRemoteSocketAddress().toString();
                structuredLogger.logRequest(requestId, remoteIp, method != null ? method : "UNKNOWN",
                                          path != null ? path : "/", statusCode, duration, responseSize);
            }
        }
    }

    public void routeRequest(String[] requestHeader, Map<String, String> headers, BufferedReader inputStream) throws IOException {
        // Array bounds checking
        if (requestHeader.length < 2) {
            throw new IOException("Invalid HTTP request: missing method or path");
        }

        String method = requestHeader[0];
        String path = requestHeader[1];
        String version = (requestHeader.length > 2) ? requestHeader[2] : "HTTP/1.0";

        // Determine HTTP version
        useHTTP11 = version.startsWith("HTTP/1.1");

        // Validate Host header for HTTP/1.1 (required by spec)
        if (useHTTP11 && !headers.containsKey("host")) {
            try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                 Writer writer = new OutputStreamWriter(outputStream)) {
                sendBadRequest(writer, "HTTP/1.1 requires Host header");
            }
            return;
        }

        // Phase 6: Process routing rules (redirects and rewrites)
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

        // Phase 5: Route health check endpoints
        if (config.isHealthChecksEnabled() && path.startsWith("/health/")) {
            try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                 Writer writer = new OutputStreamWriter(outputStream)) {
                healthCheckHandler.handleHealthCheck(writer, path, version);
            }
            return;
        }

        // Phase 5: Route metrics endpoint
        if (config.isMetricsEnabled() && path.equals("/metrics")) {
            try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                 Writer writer = new OutputStreamWriter(outputStream)) {
                handleMetricsEndpoint(writer, version);
            }
            return;
        }

        // Phase 5: Route API endpoints (POST/PUT/DELETE)
        if (path.startsWith("/api/")) {
            try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                 Writer writer = new OutputStreamWriter(outputStream)) {
                handleApiEndpoint(method, path, version, headers, inputStream, writer);
            }
            return;
        }

        // Handle root path requests - serve index.html or return 404
        if (path == null || path.isEmpty() || path.equals("/")) {
            try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                 Writer writer = new OutputStreamWriter(outputStream)) {
                resourceNotFound(writer, version, headers);
            }
            return;
        }

        String fileName = path;

        fileName = fileName.substring(1);

        // Phase 6: Resolve webroot using virtual host manager
        File resolvedWebroot = vhostManager.resolveWebroot(headers);

        // Path traversal validation - normalize and validate BEFORE authentication
        Path requestedPath = validateAndNormalizePath(fileName, resolvedWebroot);
        if (requestedPath == null) {
            try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                 Writer writer = new OutputStreamWriter(outputStream)) {
                resourceNotFound(writer, version, headers);
            }
            return;
        }

        // Phase 6: Check authentication using AuthenticationManager
        if (authManager.requiresAuthentication(path)) {
            AuthenticationManager.AuthResult authResult = authManager.authenticate(headers);
            if (!authResult.isAuthenticated()) {
                try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                     Writer writer = new OutputStreamWriter(outputStream)) {
                    sendUnauthorizedResponse(writer, version, headers);
                    if (config.isMetricsEnabled()) {
                        metrics.incrementCounter("auth_failures", "path=" + path);
                    }
                }
                return;
            }

            // Log successful authentication
            structuredLogger.logInfo(requestId, "Authentication successful: user=" + authResult.getUsername() +
                                               ", method=" + authResult.getMethod());
            if (config.isMetricsEnabled()) {
                metrics.incrementCounter("auth_success", "method=" + authResult.getMethod());
            }
        }

        String mimeType = URLConnection.getFileNameMap().getContentTypeFor(fileName);
        // Default MIME type if null
        if (mimeType == null) {
            mimeType = "application/octet-stream";
        }

        File file = requestedPath.toFile();
        System.out.println("Requesting resource: " + fileName+ "\r\n");
        System.out.println("The file mimetype is  " + mimeType+ "\r\n");

        try {
            // Check file size before processing
            if (file.length() > MAX_FILE_SIZE) {
                try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                     Writer writer = new OutputStreamWriter(outputStream)) {
                    sendPayloadTooLarge(writer, version, headers);
                }
                return;
            }

            // Check if file is readable - validateAndNormalizePath already checked path safety
            if (!file.canRead()) {
                try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                     Writer writer = new OutputStreamWriter(outputStream)) {
                    resourceNotFound(writer, version, headers);
                }
                return;
            }

            try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                 Writer writer = new OutputStreamWriter(outputStream)) {
                switch (method) {
                    case "GET":
                        HTTPGet(outputStream, writer, file, mimeType, version, headers);
                        break;
                    case "HEAD":
                        HTTPHead(writer, file, mimeType, version, headers);
                        break;
                    default:
                        invalidVerb(writer, version, headers);
                        break;
                }
            }
        } catch (UnsupportedEncodingException e) {
            errorLog.log(Level.WARNING, "Unsupported encoding in route", e);
        } catch (IOException e) {
            errorLog.log(Level.WARNING, "IO error in route", e);
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                errorLog.log(Level.WARNING, "Error closing socket in route", e);
            }
        }
    }

    private void HTTPGet(OutputStream outputStream, Writer writer, File file, String mimeType, String version, Map<String, String> headers)
            throws IOException {
        long fileLength = file.length();
        String code = "200";

        // Generate ETag and Last-Modified headers
        String etag = null;
        String lastModified = null;
        if (config.isCacheEnabled()) {
            etag = cacheManager.generateETag(file);
            lastModified = cacheManager.getLastModified(file);

            // Check if resource was modified (conditional request)
            if (!cacheManager.shouldServeResource(headers, file, etag)) {
                // Send 304 Not Modified
                sendNotModified(writer, version, etag, lastModified, headers);
                logRequest("GET", file.getName(), version, "304", 0);
                return;
            }
        }

        // Check if compression should be applied
        boolean useCompression = config.isCompressionEnabled() &&
                                 compressionHandler.shouldCompress(headers, mimeType, fileLength, file.getName());

        byte[] content = null;
        int contentLength = (int) fileLength;

        if (useCompression) {
            // Compress the file
            content = compressionHandler.compressFile(file);
            if (content != null) {
                contentLength = content.length;
            } else {
                // Compression failed, serve uncompressed
                useCompression = false;
            }
        }

        // Send response headers
        if (version.startsWith("HTTP/")) {
            String httpVersion = useHTTP11 ? "HTTP/1.1" : "HTTP/1.0";
            sendHeader(writer, httpVersion + " 200 OK", mimeType, contentLength, etag, lastModified, useCompression, headers);
        }

        // Send response body
        if (useCompression && content != null) {
            // Write compressed content
            outputStream.write(content);
        } else {
            // Stream file in chunks to prevent memory exhaustion
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }
        }

        logRequest("GET", file.getName(), version, code, contentLength);
        outputStream.flush();
    }

    private void HTTPHead(Writer writer, File file, String mimeType, String version, Map<String, String> headers) throws IOException {
        long fileLength = file.length();
        String code = "200";

        // Generate ETag and Last-Modified headers (same as GET)
        String etag = null;
        String lastModified = null;
        if (config.isCacheEnabled()) {
            etag = cacheManager.generateETag(file);
            lastModified = cacheManager.getLastModified(file);

            // Check if resource was modified (conditional request)
            if (!cacheManager.shouldServeResource(headers, file, etag)) {
                // Send 304 Not Modified
                sendNotModified(writer, version, etag, lastModified, headers);
                logRequest("HEAD", file.getName(), version, "304", 0);
                return;
            }
        }

        // Calculate content length (compressed or not)
        boolean wouldCompress = config.isCompressionEnabled() &&
                                compressionHandler.shouldCompress(headers, mimeType, fileLength, file.getName());
        int contentLength = (int) fileLength;

        if (wouldCompress) {
            // For HEAD, we need to calculate what the compressed size would be
            byte[] compressed = compressionHandler.compressFile(file);
            if (compressed != null) {
                contentLength = compressed.length;
            } else {
                wouldCompress = false;
            }
        }

        if (version.startsWith("HTTP/")) {
            String httpVersion = useHTTP11 ? "HTTP/1.1" : "HTTP/1.0";
            sendHeader(writer, httpVersion + " 200 OK", mimeType, contentLength, etag, lastModified, wouldCompress, headers);
        }

        logRequest("HEAD", file.getName(), version, code, contentLength);
    }

    public void invalidVerb(Writer writer, String version, Map<String, String> headers) throws IOException {
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
            addCommonHeaders(writer, headers);
            writer.write("\r\n");
        }
        writer.write(response);
        writer.flush();
    }

    public void resourceNotFound(Writer writer, String version, Map<String, String> headers) throws IOException {
        String response = new StringBuilder("<HTML>\r\n").append("<head><title>Resource Not Found</title></head>\r\n")
                .append("<body>").append("<h1>404 Error: File not found.</h1>\r\n").append("</body></html>\r\n")
                .toString();
        if (version.startsWith("HTTP/")) {
            String httpVersion = useHTTP11 ? "HTTP/1.1" : "HTTP/1.0";
            writer.write(httpVersion + " 404 Not Found\r\n");
            writer.write("Content-type: text/html; charset=utf-8\r\n");
            writer.write("Content-length: " + response.length() + "\r\n");
            addCommonHeaders(writer, headers);
            writer.write("\r\n");
        }
        writer.write(response);
        writer.flush();
    }

    private void sendHeader(Writer writer, String responseCode, String mimeType, int length) throws IOException {
        sendHeader(writer, responseCode, mimeType, length, null, null, false, null);
    }

    private void sendHeader(Writer writer, String responseCode, String mimeType, int length,
                           String etag, String lastModified, boolean compressed, Map<String, String> headers) throws IOException {
        writer.write(responseCode + "\r\n");
        writer.write("Date: " + cacheManager.getHttpDate() + "\r\n");
        writer.write("Server: HTTPServer/2.0\r\n");
        writer.write("Content-length: " + length + "\r\n");
        writer.write("Content-type: " + mimeType + "\r\n");

        // Add caching headers
        if (config.isCacheEnabled() && etag != null) {
            writer.write("ETag: " + etag + "\r\n");
        }
        if (config.isCacheEnabled() && lastModified != null) {
            writer.write("Last-Modified: " + lastModified + "\r\n");
        }
        if (config.isCacheEnabled()) {
            writer.write("Cache-Control: " + cacheManager.getCacheControl("") + "\r\n");
        }

        // Add compression header
        if (compressed) {
            writer.write("Content-Encoding: gzip\r\n");
            writer.write("Vary: Accept-Encoding\r\n");
        }

        // Add common headers (Connection, HSTS, etc.)
        addCommonHeaders(writer, headers);

        writer.write("\r\n");
        writer.flush();
    }

    private void addCommonHeaders(Writer writer, Map<String, String> headers) throws IOException {
        // HTTP/1.1 Connection header
        if (useHTTP11) {
            // For HTTP/1.1, default is keep-alive, but we're closing after each request for now
            writer.write("Connection: close\r\n");
        }

        // Add HSTS header if TLS is enabled
        if (config.isTlsEnabled() && config.isHstsEnabled()) {
            String hstsHeader = config.getHstsHeader();
            if (hstsHeader != null) {
                writer.write("Strict-Transport-Security: " + hstsHeader + "\r\n");
            }
        }
    }

    private void sendNotModified(Writer writer, String version, String etag, String lastModified, Map<String, String> headers) throws IOException {
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

        addCommonHeaders(writer, headers);
        writer.write("\r\n");
        writer.flush();
    }

    private void sendBadRequest(Writer writer, String message) throws IOException {
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

    private void sendPayloadTooLarge(Writer writer, String version, Map<String, String> headers) throws IOException {
        String response = "<HTML>\r\n<head><title>Payload Too Large</title></head>\r\n"
                + "<body><h1>413 Payload Too Large</h1>\r\n"
                + "<p>File size exceeds maximum allowed size</p>\r\n"
                + "</body></html>\r\n";

        String httpVersion = useHTTP11 ? "HTTP/1.1" : "HTTP/1.0";
        writer.write(httpVersion + " 413 Payload Too Large\r\n");
        writer.write("Content-type: text/html; charset=utf-8\r\n");
        writer.write("Content-length: " + response.length() + "\r\n");
        addCommonHeaders(writer, headers);
        writer.write("\r\n");
        writer.write(response);
        writer.flush();
    }
    public void logRequest(String verb, String fileName, String version, String code, int bytes){
        //127.0.0.1 - - [10/Oct/2000:13:55:36 -0700] "GET /apache_pb.gif HTTP/1.0" 200 2326
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
            // Reject paths containing directory traversal attempts
            if (fileName.contains("..")) {
                System.err.println("Path traversal attempt detected: " + fileName);
                return null;
            }

            // Normalize the path
            Path requestedPath = Paths.get(webrootToUse.getCanonicalPath(), fileName).normalize();
            Path webrootPath = Paths.get(webrootToUse.getCanonicalPath()).normalize();

            // Verify the resolved path is within the webroot
            if (!requestedPath.startsWith(webrootPath)) {
                System.err.println("Path outside webroot: " + requestedPath);
                return null;
            }

            // Reject absolute paths
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

    private boolean validateBasicAuth(Map<String, String> headers) {
        String authHeader = headers.get("authorization");

        // No authorization header - unauthorized
        if (authHeader == null || authHeader.isEmpty()) {
            return false;
        }

        // Check if it's Basic auth
        if (!authHeader.startsWith("Basic ")) {
            return false;
        }

        try {
            // Extract and decode the Base64 credentials
            String encodedCredentials = authHeader.substring(6);
            String decodedCredentials = new String(Base64.getDecoder().decode(encodedCredentials), StandardCharsets.UTF_8);

            int colonIndex = decodedCredentials.indexOf(':');
            if (colonIndex <= 0) {
                return false;
            }

            String username = decodedCredentials.substring(0, colonIndex);
            String password = decodedCredentials.substring(colonIndex + 1);

            // Validate against credentials via AuthenticationManager
            if (authManager.validateBasicAuthCredentials(username, password)) {
                auditLog.info("Authentication successful for user: " + username);
                return true;
            }

            auditLog.warning("Authentication failed for user: " + username);
            return false;
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid Base64 encoding in Authorization header");
            return false;
        }
    }

    private void sendUnauthorizedResponse(Writer writer, String version, Map<String, String> headers) throws IOException {
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
        addCommonHeaders(writer, headers);
        writer.write("\r\n");
        writer.write(response);
        writer.flush();
    }

    // Phase 5: Metrics endpoint handler
    private void handleMetricsEndpoint(Writer writer, String version) throws IOException {
        String metricsOutput = metrics.exportPrometheusMetrics();

        String httpVersion = useHTTP11 ? "HTTP/1.1" : "HTTP/1.0";
        writer.write(httpVersion + " 200 OK\r\n");
        writer.write("Content-Type: text/plain; version=0.0.4\r\n");
        writer.write("Content-Length: " + metricsOutput.length() + "\r\n");
        writer.write("Connection: close\r\n");
        writer.write("\r\n");
        writer.write(metricsOutput);
        writer.flush();
    }

    // Phase 5: Rate limit exceeded response
    private void sendRateLimitExceeded(Writer writer, String version, RateLimiter.RateLimitResult result,
                                      Map<String, String> headers) throws IOException {
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
        writer.write("Connection: close\r\n");
        writer.write("\r\n");
        writer.write(response);
        writer.flush();
    }

    // Phase 5: API endpoint handler (POST/PUT/DELETE)
    private void handleApiEndpoint(String method, String path, String version, Map<String, String> headers,
                                   BufferedReader inputStream, Writer writer) throws IOException {
        // Phase 6: Check authentication using AuthenticationManager
        AuthenticationManager.AuthResult authResult = authManager.authenticate(headers);
        if (!authResult.isAuthenticated()) {
            sendUnauthorizedResponse(writer, version, headers);
            return;
        }

        String httpVersion = useHTTP11 ? "HTTP/1.1" : "HTTP/1.0";

        switch (path) {
            case "/api/echo":
                handleEchoEndpoint(method, version, headers, inputStream, writer);
                break;

            case "/api/upload":
                handleUploadEndpoint(method, version, headers, inputStream, writer);
                break;

            default:
                if (path.startsWith("/api/data")) {
                    handleDataEndpoint(method, path, version, headers, inputStream, writer);
                } else {
                    sendApiNotFound(writer, version);
                }
                break;
        }
    }

    private void handleEchoEndpoint(String method, String version, Map<String, String> headers,
                                   BufferedReader inputStream, Writer writer) throws IOException {
        if (!method.equals("POST")) {
            sendMethodNotAllowed(writer, version, "POST");
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
            writer.write("Connection: close\r\n");
            writer.write("\r\n");
            writer.write(responseBody);
            writer.flush();
        } catch (RequestBodyParser.PayloadTooLargeException e) {
            sendPayloadTooLarge(writer, version, headers);
        }
    }

    private void handleUploadEndpoint(String method, String version, Map<String, String> headers,
                                     BufferedReader inputStream, Writer writer) throws IOException {
        if (!method.equals("POST")) {
            sendMethodNotAllowed(writer, version, "POST");
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
            writer.write("Connection: close\r\n");
            writer.write("\r\n");
            writer.write(responseBody);
            writer.flush();
        } catch (RequestBodyParser.PayloadTooLargeException e) {
            sendPayloadTooLarge(writer, version, headers);
        }
    }

    private void handleDataEndpoint(String method, String path, String version, Map<String, String> headers,
                                   BufferedReader inputStream, Writer writer) throws IOException {
        String responseBody;
        int statusCode = 200;
        String statusText = "OK";

        switch (method) {
            case "PUT":
                try {
                    RequestBodyParser.ParsedBody body = bodyParser.parseBody(socket.getInputStream(), headers);
                    responseBody = "{\"status\":\"updated\",\"path\":\"" + path + "\"}";
                } catch (RequestBodyParser.PayloadTooLargeException e) {
                    sendPayloadTooLarge(writer, version, headers);
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
                    sendPayloadTooLarge(writer, version, headers);
                    return;
                }
                break;

            default:
                sendMethodNotAllowed(writer, version, "PUT, DELETE, POST");
                return;
        }

        String httpVersion = useHTTP11 ? "HTTP/1.1" : "HTTP/1.0";
        writer.write(httpVersion + " " + statusCode + " " + statusText + "\r\n");
        writer.write("Content-Type: application/json\r\n");
        writer.write("Content-Length: " + responseBody.length() + "\r\n");
        writer.write("Connection: close\r\n");
        writer.write("\r\n");
        writer.write(responseBody);
        writer.flush();
    }

    private void sendApiNotFound(Writer writer, String version) throws IOException {
        String response = "{\"error\":\"API endpoint not found\"}";

        String httpVersion = useHTTP11 ? "HTTP/1.1" : "HTTP/1.0";
        writer.write(httpVersion + " 404 Not Found\r\n");
        writer.write("Content-Type: application/json\r\n");
        writer.write("Content-Length: " + response.length() + "\r\n");
        writer.write("Connection: close\r\n");
        writer.write("\r\n");
        writer.write(response);
        writer.flush();
    }

    private void sendMethodNotAllowed(Writer writer, String version, String allowedMethods) throws IOException {
        String response = "{\"error\":\"Method not allowed\"}";

        String httpVersion = useHTTP11 ? "HTTP/1.1" : "HTTP/1.0";
        writer.write(httpVersion + " 405 Method Not Allowed\r\n");
        writer.write("Allow: " + allowedMethods + "\r\n");
        writer.write("Content-Type: application/json\r\n");
        writer.write("Content-Length: " + response.length() + "\r\n");
        writer.write("Connection: close\r\n");
        writer.write("\r\n");
        writer.write(response);
        writer.flush();
    }
}