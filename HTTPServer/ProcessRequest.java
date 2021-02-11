package HTTPServer;

import java.io.*;
import java.net.Socket;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.logging.Logger;

class ProcessRequest implements Runnable {

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
            Reader inputStream = new InputStreamReader(new BufferedInputStream(socket.getInputStream()), StandardCharsets.UTF_8);
            StringBuilder requestText = new StringBuilder();
            while (true) {
                int ch = inputStream.read();
                if (ch == '\r' || ch == '\n')
                    break;
                requestText.append((char) ch);
            }
            routeRequest(requestText.toString().split("\\s+"));
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
        String fileName = requestHeader[1];
        //if(fileName == ""){ emptyRequest()}; Handle default file, such as index.html or 404.
        fileName = fileName.substring(1);
        String mimeType = URLConnection.getFileNameMap().getContentTypeFor(fileName);
        String version = "";
        File file = new File(webroot, fileName);
        System.out.println("Requesting resource: " + fileName+ "\r\n");
        System.out.println("The file mimetype is  " + mimeType+ "\r\n");
        if (requestHeader.length > 2) {
            version = requestHeader[2];
        }
        try {
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
        byte[] rawData = Files.readAllBytes(file.toPath());
        String code = "404";
        if (version.startsWith("HTTP/")) {
            sendHeader(writer, "HTTP/1.0 200 OK", mimeType, rawData.length);
            code = "200";
        }
        logRequest("GET", file.getName(), "HTTP/1.0", code, rawData.length);
        outputStream.write(rawData);
        outputStream.flush();
        return;
    }

    private void HTTPHead(Writer writer, File file, String mimeType, String version) throws IOException {
        byte[] rawData = Files.readAllBytes(file.toPath());
        String code = "404";
        if (version.startsWith("HTTP/")) {
            sendHeader(writer, "HTTP/1.0 204 OK", mimeType, rawData.length);
            code = "204";
        }
        logRequest("HEAD", file.getName(), "HTTP/1.0", code, rawData.length);
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
}