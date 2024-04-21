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
    private static final int REQUEST_TIMEOUT_MS = 5000; // 5 second timeout
    private static final long MAX_FILE_SIZE = 1073741824L; // 1GB max file size
    private static final int HTTP_OK = 200;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_METHOD_NOT_ALLOWED = 405;
    private static final int HTTP_PAYLOAD_TOO_LARGE = 413;
    private static final Map<String, String> VALID_CREDENTIALS = new HashMap<>();
    static {
        // Initialize default credentials - user: admin, password: password
        VALID_CREDENTIALS.put("admin", "password");
    }
    private final File webroot;
    private final Socket socket;
    private Logger auditLog;
    private Logger errorLog = Logger.getLogger("errors");
    private ServerConfig config;
    private CompressionHandler compressionHandler;
    private CacheManager cacheManager;
    private boolean useHTTP11 = true; // Default to HTTP/1.1


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
    }

    @Override
    public void run() {
        try {
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

            // Read HTTP headers to extract Authorization
            Map<String, String> headers = new HashMap<>();
            String headerLine;
            while ((headerLine = inputStream.readLine()) != null && !headerLine.isEmpty()) {
                int colonIndex = headerLine.indexOf(':');
                if (colonIndex > 0) {
                    String name = headerLine.substring(0, colonIndex).trim();
                    String value = headerLine.substring(colonIndex + 1).trim();
                    headers.put(name.toLowerCase(), value);
                }
            }

            routeRequest(parts, headers);
        } catch (UnsupportedEncodingException e) {
            errorLog.log(Level.WARNING, "Unsupported encoding in request", e);
        } catch (IOException e) {
            errorLog.log(Level.WARNING, "IO error processing request", e);
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                errorLog.log(Level.WARNING, "Error closing socket", e);
            }
        }
    }

    public void routeRequest(String[] requestHeader, Map<String, String> headers) throws IOException {
        // Array bounds checking
        if (requestHeader.length < 2) {
            throw new IOException("Invalid HTTP request: missing method or path");
        }

        String fileName = requestHeader[1];
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

        // Handle root path requests - serve index.html or return 404
        if (fileName == null || fileName.isEmpty() || fileName.equals("/")) {
            try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                 Writer writer = new OutputStreamWriter(outputStream)) {
                resourceNotFound(writer, version, headers);
            }
            return;
        }

        fileName = fileName.substring(1);

        // Path traversal validation - normalize and validate BEFORE authentication
        Path requestedPath = validateAndNormalizePath(fileName);
        if (requestedPath == null) {
            try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                 Writer writer = new OutputStreamWriter(outputStream)) {
                resourceNotFound(writer, version, headers);
            }
            return;
        }

        // Check authentication AFTER path validation to prevent information disclosure
        if (!validateBasicAuth(headers)) {
            try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                 Writer writer = new OutputStreamWriter(outputStream)) {
                sendUnauthorizedResponse(writer, version, headers);
            }
            return;
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
                String verb = requestHeader[0];
                switch (verb) {
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

    private Path validateAndNormalizePath(String fileName) {
        try {
            // Reject paths containing directory traversal attempts
            if (fileName.contains("..")) {
                System.err.println("Path traversal attempt detected: " + fileName);
                return null;
            }

            // Normalize the path
            Path requestedPath = Paths.get(webroot.getCanonicalPath(), fileName).normalize();
            Path webrootPath = Paths.get(webroot.getCanonicalPath()).normalize();

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

            // Validate against credentials map
            String validPassword = VALID_CREDENTIALS.get(username);
            if (validPassword != null && validPassword.equals(password)) {
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
        String response = "<HTML>\r\n<head><title>Unauthorized</title></head>\r\n"
                + "<body><h1>401 Unauthorized</h1>\r\n"
                + "<p>Please provide valid credentials using Basic Authentication.</p>\r\n"
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
}