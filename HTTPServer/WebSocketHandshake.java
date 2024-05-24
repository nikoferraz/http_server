package HTTPServer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * WebSocket opening handshake handler per RFC 6455.
 *
 * The WebSocket Protocol is designed to work over HTTP/1.1 and higher,
 * using an upgrade mechanism. An opening handshake is sent by the client,
 * and a matching response is sent by the server.
 *
 * Client handshake example:
 *   GET /chat HTTP/1.1
 *   Host: server.example.com
 *   Upgrade: websocket
 *   Connection: Upgrade
 *   Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
 *   Sec-WebSocket-Version: 13
 *
 * Server response example:
 *   HTTP/1.1 101 Switching Protocols
 *   Upgrade: websocket
 *   Connection: Upgrade
 *   Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
 */
public class WebSocketHandshake {

    private static final String WEBSOCKET_VERSION = "13";
    private static final String MAGIC_STRING = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final Pattern BASE64_PATTERN = Pattern.compile("^[A-Za-z0-9+/]+=*$");

    private WebSocketHandshake() {
        // Utility class
    }

    /**
     * Validates a WebSocket upgrade request and extracts relevant headers.
     *
     * @param method HTTP method
     * @param headers HTTP headers
     * @return a HandshakeRequest if valid
     * @throws WebSocketHandshakeException if handshake is invalid
     */
    public static HandshakeRequest validateRequest(String method, Map<String, String> headers)
            throws WebSocketHandshakeException {
        // Must be GET
        if (!"GET".equalsIgnoreCase(method)) {
            throw new WebSocketHandshakeException("WebSocket upgrade requires GET method");
        }

        // Check Upgrade header (can have multiple comma-separated values)
        String upgrade = getHeaderCaseInsensitive(headers, "Upgrade");
        if (upgrade == null) {
            throw new WebSocketHandshakeException("Missing or invalid Upgrade header");
        }
        // Check if "websocket" is one of the upgrade options (case-insensitive)
        boolean hasWebsocket = false;
        for (String value : upgrade.split(",")) {
            if ("websocket".equalsIgnoreCase(value.trim())) {
                hasWebsocket = true;
                break;
            }
        }
        if (!hasWebsocket) {
            throw new WebSocketHandshakeException("Missing or invalid Upgrade header");
        }

        // Check Connection header
        String connection = getHeaderCaseInsensitive(headers, "Connection");
        if (connection == null || !connection.toLowerCase().contains("upgrade")) {
            throw new WebSocketHandshakeException("Missing or invalid Connection header");
        }

        // Check Sec-WebSocket-Key
        String secKey = getHeaderCaseInsensitive(headers, "Sec-WebSocket-Key");
        if (secKey == null || secKey.isEmpty()) {
            throw new WebSocketHandshakeException("Missing Sec-WebSocket-Key header");
        }

        // Validate key format: must be 24 bytes base64 (represents 16 random bytes)
        if (!isValidBase64(secKey) || secKey.length() != 24) {
            throw new WebSocketHandshakeException("Invalid Sec-WebSocket-Key format");
        }

        // Check Sec-WebSocket-Version
        String version = getHeaderCaseInsensitive(headers, "Sec-WebSocket-Version");
        if (version == null || !WEBSOCKET_VERSION.equals(version.trim())) {
            throw new WebSocketHandshakeException("Unsupported Sec-WebSocket-Version: " + version);
        }

        // Optional: Sec-WebSocket-Protocol (subprotocol negotiation)
        String protocol = getHeaderCaseInsensitive(headers, "Sec-WebSocket-Protocol");

        // Optional: Origin (for CSRF prevention)
        String origin = getHeaderCaseInsensitive(headers, "Origin");

        return new HandshakeRequest(secKey, protocol, origin);
    }

    /**
     * Generates the server response for a WebSocket upgrade.
     *
     * @param secKey the client's Sec-WebSocket-Key
     * @param selectedProtocol the selected subprotocol (optional)
     * @return the response headers
     */
    public static Map<String, String> generateResponse(String secKey, String selectedProtocol)
            throws WebSocketHandshakeException {
        String acceptKey = generateAcceptKey(secKey);

        Map<String, String> responseHeaders = new HashMap<>();
        responseHeaders.put("Upgrade", "websocket");
        responseHeaders.put("Connection", "Upgrade");
        responseHeaders.put("Sec-WebSocket-Accept", acceptKey);

        if (selectedProtocol != null && !selectedProtocol.isEmpty()) {
            responseHeaders.put("Sec-WebSocket-Protocol", selectedProtocol);
        }

        return responseHeaders;
    }

    /**
     * Generates the accept key from the client's security key.
     *
     * The accept key is computed as: Base64(SHA-1(Sec-WebSocket-Key + magic string))
     */
    private static String generateAcceptKey(String secKey) throws WebSocketHandshakeException {
        try {
            String concatenated = secKey + MAGIC_STRING;
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest(concatenated.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new WebSocketHandshakeException("Failed to generate accept key", e);
        }
    }

    /**
     * Validates if a string is valid base64.
     */
    private static boolean isValidBase64(String s) {
        return BASE64_PATTERN.matcher(s).matches();
    }

    /**
     * Case-insensitive header lookup.
     */
    private static String getHeaderCaseInsensitive(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Represents a validated WebSocket upgrade request.
     */
    public static class HandshakeRequest {
        private final String secWebSocketKey;
        private final String protocol;
        private final String origin;

        public HandshakeRequest(String secWebSocketKey, String protocol, String origin) {
            this.secWebSocketKey = secWebSocketKey;
            this.protocol = protocol;
            this.origin = origin;
        }

        public String getSecWebSocketKey() {
            return secWebSocketKey;
        }

        public String getProtocol() {
            return protocol;
        }

        public String getOrigin() {
            return origin;
        }
    }

    /**
     * Exception thrown during handshake validation.
     */
    public static class WebSocketHandshakeException extends Exception {
        public WebSocketHandshakeException(String message) {
            super(message);
        }

        public WebSocketHandshakeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
