package HTTPServer;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class RequestBodyParser {

    private static final long DEFAULT_MAX_BODY_SIZE = 10 * 1024 * 1024; // 10MB
    private final long maxBodySize;

    public RequestBodyParser() {
        this(DEFAULT_MAX_BODY_SIZE);
    }

    public RequestBodyParser(long maxBodySize) {
        this.maxBodySize = maxBodySize;
    }

    public ParsedBody parseBody(InputStream inputStream, Map<String, String> headers) throws IOException {
        String contentType = headers.getOrDefault("content-type", "");
        String contentLengthStr = headers.get("content-length");

        if (contentLengthStr == null) {
            throw new IOException("Content-Length header required for request body");
        }

        long contentLength;
        try {
            contentLength = Long.parseLong(contentLengthStr);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid Content-Length header");
        }

        if (contentLength > maxBodySize) {
            throw new PayloadTooLargeException("Request body exceeds maximum size of " + maxBodySize + " bytes");
        }

        if (contentLength < 0) {
            throw new IOException("Invalid Content-Length: " + contentLength);
        }

        // Read the body
        byte[] bodyBytes = readBody(inputStream, (int) contentLength);

        // Parse based on content type
        if (contentType.startsWith("application/json")) {
            return parseJson(bodyBytes);
        } else if (contentType.startsWith("application/x-www-form-urlencoded")) {
            return parseFormUrlEncoded(bodyBytes);
        } else if (contentType.startsWith("multipart/form-data")) {
            String boundary = extractBoundary(contentType);
            return parseMultipart(bodyBytes, boundary);
        } else if (contentType.startsWith("text/plain")) {
            return parsePlainText(bodyBytes);
        } else {
            // Default: treat as binary/octet-stream
            return parseRaw(bodyBytes);
        }
    }

    private byte[] readBody(InputStream inputStream, int contentLength) throws IOException {
        byte[] buffer = new byte[contentLength];
        int totalRead = 0;

        while (totalRead < contentLength) {
            int bytesRead = inputStream.read(buffer, totalRead, contentLength - totalRead);
            if (bytesRead == -1) {
                throw new IOException("Unexpected end of stream while reading request body");
            }
            totalRead += bytesRead;
        }

        return buffer;
    }

    private ParsedBody parseJson(byte[] bodyBytes) {
        String jsonString = new String(bodyBytes, StandardCharsets.UTF_8);
        ParsedBody body = new ParsedBody("application/json");
        body.setRawContent(jsonString);

        // Simple JSON parsing - extract key-value pairs for simple objects
        // This is a minimal implementation; for production use a proper JSON library
        try {
            Map<String, String> jsonMap = parseSimpleJson(jsonString);
            body.setParameters(jsonMap);
        } catch (Exception e) {
            // If parsing fails, just keep the raw content
        }

        return body;
    }

    private Map<String, String> parseSimpleJson(String json) {
        Map<String, String> map = new HashMap<>();

        // Remove whitespace, outer braces
        json = json.trim();
        if (json.startsWith("{")) {
            json = json.substring(1);
        }
        if (json.endsWith("}")) {
            json = json.substring(0, json.length() - 1);
        }

        // Split by commas (simple parsing, doesn't handle nested objects)
        String[] pairs = json.split(",");
        for (String pair : pairs) {
            String[] keyValue = pair.split(":", 2);
            if (keyValue.length == 2) {
                String key = keyValue[0].trim().replace("\"", "");
                String value = keyValue[1].trim().replace("\"", "");
                map.put(key, value);
            }
        }

        return map;
    }

    private ParsedBody parseFormUrlEncoded(byte[] bodyBytes) throws IOException {
        String formString = new String(bodyBytes, StandardCharsets.UTF_8);
        ParsedBody body = new ParsedBody("application/x-www-form-urlencoded");
        body.setRawContent(formString);

        Map<String, String> params = new HashMap<>();
        String[] pairs = formString.split("&");

        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8.name());
                String value = URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8.name());
                params.put(key, value);
            } else if (keyValue.length == 1) {
                String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8.name());
                params.put(key, "");
            }
        }

        body.setParameters(params);
        return body;
    }

    private ParsedBody parseMultipart(byte[] bodyBytes, String boundary) {
        ParsedBody body = new ParsedBody("multipart/form-data");
        String bodyString = new String(bodyBytes, StandardCharsets.UTF_8);
        body.setRawContent(bodyString);

        Map<String, String> params = new HashMap<>();

        if (boundary == null || boundary.isEmpty()) {
            return body;
        }

        // Simple multipart parsing (basic implementation)
        String[] parts = bodyString.split("--" + boundary);

        for (String part : parts) {
            if (part.trim().isEmpty() || part.trim().equals("--")) {
                continue;
            }

            // Extract field name and value
            String[] lines = part.split("\r\n");
            String fieldName = null;
            String fieldValue = null;

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];

                if (line.contains("Content-Disposition")) {
                    // Extract name from: Content-Disposition: form-data; name="fieldname"
                    int nameStart = line.indexOf("name=\"");
                    if (nameStart != -1) {
                        nameStart += 6;
                        int nameEnd = line.indexOf("\"", nameStart);
                        if (nameEnd != -1) {
                            fieldName = line.substring(nameStart, nameEnd);
                        }
                    }
                } else if (line.isEmpty() && i + 1 < lines.length) {
                    // Value starts after empty line
                    fieldValue = lines[i + 1];
                    break;
                }
            }

            if (fieldName != null && fieldValue != null) {
                params.put(fieldName, fieldValue);
            }
        }

        body.setParameters(params);
        return body;
    }

    private ParsedBody parsePlainText(byte[] bodyBytes) {
        String textString = new String(bodyBytes, StandardCharsets.UTF_8);
        ParsedBody body = new ParsedBody("text/plain");
        body.setRawContent(textString);
        return body;
    }

    private ParsedBody parseRaw(byte[] bodyBytes) {
        ParsedBody body = new ParsedBody("application/octet-stream");
        body.setRawBytes(bodyBytes);
        return body;
    }

    private String extractBoundary(String contentType) {
        int boundaryIndex = contentType.indexOf("boundary=");
        if (boundaryIndex == -1) {
            return null;
        }

        String boundary = contentType.substring(boundaryIndex + 9);
        // Remove quotes if present
        if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
            boundary = boundary.substring(1, boundary.length() - 1);
        }

        return boundary;
    }

    public static class ParsedBody {
        private final String contentType;
        private String rawContent;
        private byte[] rawBytes;
        private Map<String, String> parameters = new HashMap<>();

        public ParsedBody(String contentType) {
            this.contentType = contentType;
        }

        public String getContentType() {
            return contentType;
        }

        public String getRawContent() {
            return rawContent;
        }

        public void setRawContent(String rawContent) {
            this.rawContent = rawContent;
        }

        public byte[] getRawBytes() {
            return rawBytes;
        }

        public void setRawBytes(byte[] rawBytes) {
            this.rawBytes = rawBytes;
        }

        public Map<String, String> getParameters() {
            return parameters;
        }

        public void setParameters(Map<String, String> parameters) {
            this.parameters = parameters;
        }

        public String getParameter(String key) {
            return parameters.get(key);
        }
    }

    public static class PayloadTooLargeException extends IOException {
        public PayloadTooLargeException(String message) {
            super(message);
        }
    }
}
