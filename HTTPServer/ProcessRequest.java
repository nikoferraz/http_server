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


    public ProcessRequest(File webroot, Socket socket, Logger auditLog) {
        this.webroot = webroot;
        this.socket = socket;
        this.auditLog = auditLog;
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
        //if(fileName == ""){ emptyRequest()}; Handle default file, such as index.html or 404.

        // Handle root path requests - serve index.html or return 404
        if (fileName == null || fileName.isEmpty() || fileName.equals("/")) {
            try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                 Writer writer = new OutputStreamWriter(outputStream)) {
                String version = (requestHeader.length > 2) ? requestHeader[2] : "";
                resourceNotFound(writer, version);
            }
            return;
        }

        fileName = fileName.substring(1);

        // Path traversal validation - normalize and validate BEFORE authentication
        Path requestedPath = validateAndNormalizePath(fileName);
        if (requestedPath == null) {
            try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                 Writer writer = new OutputStreamWriter(outputStream)) {
                String version = (requestHeader.length > 2) ? requestHeader[2] : "";
                resourceNotFound(writer, version);
            }
            return;
        }

        // Check authentication AFTER path validation to prevent information disclosure
        if (!validateBasicAuth(headers)) {
            try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                 Writer writer = new OutputStreamWriter(outputStream)) {
                sendUnauthorizedResponse(writer);
            }
            return;
        }

        String mimeType = URLConnection.getFileNameMap().getContentTypeFor(fileName);
        // Default MIME type if null
        if (mimeType == null) {
            mimeType = "application/octet-stream";
        }
        String version = "";
        File file = requestedPath.toFile();
        System.out.println("Requesting resource: " + fileName+ "\r\n");
        System.out.println("The file mimetype is  " + mimeType+ "\r\n");
        if (requestHeader.length > 2) {
            version = requestHeader[2];
        }
        try {
            // Check file size before processing
            if (file.length() > MAX_FILE_SIZE) {
                try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                     Writer writer = new OutputStreamWriter(outputStream)) {
                    writer.write("HTTP/1.0 413 Payload Too Large\r\n");
                    writer.write("Content-type: text/plain\r\n\r\n");
                    writer.write("File size exceeds maximum allowed size\r\n");
                    writer.flush();
                }
                return;
            }

            // Check if file is readable - validateAndNormalizePath already checked path safety
            if (!file.canRead()) {
                try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                     Writer writer = new OutputStreamWriter(outputStream)) {
                    resourceNotFound(writer, version);
                }
                return;
            }

            try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                 Writer writer = new OutputStreamWriter(outputStream)) {
                String verb = requestHeader[0];
                switch (verb) {
                    case "GET":
                        HTTPGet(outputStream, writer, file, mimeType, version);
                        break;
                    case "HEAD":
                        HTTPHead(writer, file, mimeType, version);
                        break;
                    default:
                        invalidVerb(writer, version);
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

    private void HTTPGet(OutputStream outputStream, Writer writer, File file, String mimeType, String version)
            throws IOException {
        long fileLength = file.length();
        String code = "404";
        if (version.startsWith("HTTP/")) {
            sendHeader(writer, "HTTP/1.0 200 OK", mimeType, (int) fileLength);
            code = "200";
        }

        // Stream file in chunks to prevent memory exhaustion
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }

        logRequest("GET", file.getName(), "HTTP/1.0", code, (int) fileLength);
        outputStream.flush();
    }

    private void HTTPHead(Writer writer, File file, String mimeType, String version) throws IOException {
        long fileLength = file.length();
        String code = "404";
        if (version.startsWith("HTTP/")) {
            sendHeader(writer, "HTTP/1.0 200 OK", mimeType, (int) fileLength);
            code = "200";
        }
        logRequest("HEAD", file.getName(), "HTTP/1.0", code, (int) fileLength);
    }

    public void invalidVerb(Writer writer, String version) throws IOException {
        String response = "<HTML>\r\n<head><title>Method Not Allowed</title></head>\r\n"
                + "<body><h1>405 Method Not Allowed</h1>\r\n"
                + "<p>The HTTP method used is not supported by this server.</p>\r\n"
                + "</body></html>\r\n";

        if (version.startsWith("HTTP/")) {
            writer.write("HTTP/1.0 405 Method Not Allowed\r\n");
            writer.write("Allow: GET, HEAD\r\n");
            writer.write("Content-type: text/html; charset=utf-8\r\n");
            writer.write("Content-length: " + response.length() + "\r\n\r\n");
        }
        writer.write(response);
        writer.flush();
    }

    public void resourceNotFound(Writer writer, String version) throws IOException {
        String response = new StringBuilder("<HTML>\r\n").append("<head><title>Resource Not Found</title></head>\r\n")
                .append("<body>").append("<h1>404 Error: File not found.</h1>\r\n").append("</body></html>\r\n")
                .toString();
        if (version.startsWith("HTTP/")) {
            sendHeader(writer, "HTTP/1.0 404 File not found!", "text/html; charset=utf-8", response.length());
        }
        writer.write(response);
        writer.flush();
        return;
    }

    private void sendHeader(Writer writer, String responseCode, String mimeType, int length) throws IOException {
        writer.write(responseCode + "\r\n");
        writer.write("Date: " + (new Date()) + "\r\n");
        writer.write("Server: Nick's CSC 583 Final Project HTTPServer\r\n");
        writer.write("Content-length: " + length + "\r\n");
        writer.write("Content-type: " + mimeType + "\r\n\r\n");
        writer.flush();
        return;
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

    private void sendUnauthorizedResponse(Writer writer) throws IOException {
        String response = "<HTML>\r\n<head><title>Unauthorized</title></head>\r\n"
                + "<body><h1>401 Unauthorized</h1>\r\n"
                + "<p>Please provide valid credentials using Basic Authentication.</p>\r\n"
                + "</body></html>\r\n";

        writer.write("HTTP/1.0 401 Unauthorized\r\n");
        writer.write("WWW-Authenticate: Basic realm=\"HTTP Server\"\r\n");
        writer.write("Content-type: text/html; charset=utf-8\r\n");
        writer.write("Content-length: " + response.length() + "\r\n\r\n");
        writer.write(response);
        writer.flush();
    }
}