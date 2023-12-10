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
import java.util.Date;
import java.util.logging.Logger;

class ProcessRequest implements Runnable {

    private static final int MAX_REQUEST_SIZE = 8192; // 8KB max request size
    private static final int REQUEST_TIMEOUT_MS = 5000; // 5 second timeout
    private static final long MAX_FILE_SIZE = 1073741824L; // 1GB max file size
    private static File webroot;
    private final Socket socket;
    private Logger auditLog;


    public ProcessRequest(File webroot, Socket socket, Logger auditLog) {
        this.webroot = webroot;
        this.socket = socket;
        this.auditLog = auditLog;
    }

    @Override
    public void run() {
        try {
            socket.setSoTimeout(REQUEST_TIMEOUT_MS);
            Reader inputStream = new InputStreamReader(new BufferedInputStream(socket.getInputStream()), StandardCharsets.UTF_8);
            StringBuilder requestText = new StringBuilder();
            int ch;
            while ((ch = inputStream.read()) != -1 && requestText.length() < MAX_REQUEST_SIZE) {
                if (ch == '\r' || ch == '\n')
                    break;
                requestText.append((char) ch);
            }

            if (requestText.length() >= MAX_REQUEST_SIZE) {
                throw new IOException("Request size exceeds maximum allowed size");
            }

            String[] parts = requestText.toString().split("\\s+");
            if (parts.length < 2) {
                throw new IOException("Invalid HTTP request format");
            }

            routeRequest(parts);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void routeRequest(String[] requestHeader) throws IOException {
        // Array bounds checking
        if (requestHeader.length < 2) {
            throw new IOException("Invalid HTTP request: missing method or path");
        }

        String fileName = requestHeader[1];
        //if(fileName == ""){ emptyRequest()}; Handle default file, such as index.html or 404.
        fileName = fileName.substring(1);

        // Path traversal validation - normalize and validate BEFORE file construction
        Path requestedPath = validateAndNormalizePath(fileName);
        if (requestedPath == null) {
            try (OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                 Writer writer = new OutputStreamWriter(outputStream)) {
                String version = (requestHeader.length > 2) ? requestHeader[2] : "";
                resourceNotFound(writer, version);
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

            OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
            Writer writer = new OutputStreamWriter(outputStream);
            if (!file.canRead() || !file.getCanonicalPath().startsWith(webroot.getCanonicalPath())) {
                resourceNotFound(writer, version);
                return;
            }
            String verb = requestHeader[0];
            switch (verb) {
                case "GET":
                    HTTPGet(outputStream, writer, file, mimeType, version);
                    break;
                case "HEAD":
                    HTTPHead(writer, file, mimeType, version);
                    break;
                default:
                    invalidVerb(verb);
                    break;
            }
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return;
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
        return;
    }

    private void HTTPHead(Writer writer, File file, String mimeType, String version) throws IOException {
        long fileLength = file.length();
        String code = "404";
        if (version.startsWith("HTTP/")) {
            sendHeader(writer, "HTTP/1.0 200 OK", mimeType, (int) fileLength);
            code = "200";
        }
        logRequest("HEAD", file.getName(), "HTTP/1.0", code, (int) fileLength);
        return;
    }

    public void invalidVerb(String verb) {
        System.out.println("The verb " + verb + "is not valid or has not been implemented.\r\n");
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

        return;
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
}