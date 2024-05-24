package HTTPServer.tests;

import HTTPServer.WebSocketHandshake;
import HTTPServer.WebSocketHandshake.HandshakeRequest;
import HTTPServer.WebSocketHandshake.WebSocketHandshakeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("WebSocket Handshake Tests")
class WebSocketHandshakeTest {

    private static final String VALID_SEC_KEY = "dGhlIHNhbXBsZSBub25jZQ==";
    private static final String EXPECTED_ACCEPT_KEY = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";

    private Map<String, String> createValidHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Host", "localhost:8080");
        headers.put("Upgrade", "websocket");
        headers.put("Connection", "Upgrade");
        headers.put("Sec-WebSocket-Key", VALID_SEC_KEY);
        headers.put("Sec-WebSocket-Version", "13");
        return headers;
    }

    @Test
    @DisplayName("Should validate correct handshake request")
    void testValidHandshake() throws WebSocketHandshakeException {
        Map<String, String> headers = createValidHeaders();
        HandshakeRequest request = WebSocketHandshake.validateRequest("GET", headers);

        assertThat(request).isNotNull();
        assertThat(request.getSecWebSocketKey()).isEqualTo(VALID_SEC_KEY);
    }

    @Test
    @DisplayName("Should reject non-GET method")
    void testRejectNonGetMethod() {
        Map<String, String> headers = createValidHeaders();

        assertThatThrownBy(() -> WebSocketHandshake.validateRequest("POST", headers))
                .isInstanceOf(WebSocketHandshakeException.class)
                .hasMessageContaining("GET method");
    }

    @Test
    @DisplayName("Should reject missing Upgrade header")
    void testRejectMissingUpgradeHeader() {
        Map<String, String> headers = createValidHeaders();
        headers.remove("Upgrade");

        assertThatThrownBy(() -> WebSocketHandshake.validateRequest("GET", headers))
                .isInstanceOf(WebSocketHandshakeException.class)
                .hasMessageContaining("Upgrade");
    }

    @Test
    @DisplayName("Should reject invalid Upgrade header value")
    void testRejectInvalidUpgradeValue() {
        Map<String, String> headers = createValidHeaders();
        headers.put("Upgrade", "http");

        assertThatThrownBy(() -> WebSocketHandshake.validateRequest("GET", headers))
                .isInstanceOf(WebSocketHandshakeException.class)
                .hasMessageContaining("Upgrade");
    }

    @Test
    @DisplayName("Should reject missing Connection header")
    void testRejectMissingConnectionHeader() {
        Map<String, String> headers = createValidHeaders();
        headers.remove("Connection");

        assertThatThrownBy(() -> WebSocketHandshake.validateRequest("GET", headers))
                .isInstanceOf(WebSocketHandshakeException.class)
                .hasMessageContaining("Connection");
    }

    @Test
    @DisplayName("Should reject Connection header without Upgrade")
    void testRejectConnectionWithoutUpgrade() {
        Map<String, String> headers = createValidHeaders();
        headers.put("Connection", "keep-alive");

        assertThatThrownBy(() -> WebSocketHandshake.validateRequest("GET", headers))
                .isInstanceOf(WebSocketHandshakeException.class)
                .hasMessageContaining("Connection");
    }

    @Test
    @DisplayName("Should accept Connection with multiple values")
    void testConnectionWithMultipleValues() throws WebSocketHandshakeException {
        Map<String, String> headers = createValidHeaders();
        headers.put("Connection", "Upgrade, keep-alive");

        HandshakeRequest request = WebSocketHandshake.validateRequest("GET", headers);
        assertThat(request).isNotNull();
    }

    @Test
    @DisplayName("Should reject missing Sec-WebSocket-Key")
    void testRejectMissingSecKey() {
        Map<String, String> headers = createValidHeaders();
        headers.remove("Sec-WebSocket-Key");

        assertThatThrownBy(() -> WebSocketHandshake.validateRequest("GET", headers))
                .isInstanceOf(WebSocketHandshakeException.class)
                .hasMessageContaining("Sec-WebSocket-Key");
    }

    @Test
    @DisplayName("Should reject invalid Sec-WebSocket-Key format")
    void testRejectInvalidSecKeyFormat() {
        Map<String, String> headers = createValidHeaders();
        headers.put("Sec-WebSocket-Key", "notbase64!");

        assertThatThrownBy(() -> WebSocketHandshake.validateRequest("GET", headers))
                .isInstanceOf(WebSocketHandshakeException.class);
    }

    @Test
    @DisplayName("Should reject Sec-WebSocket-Key with wrong length")
    void testRejectSecKeyWrongLength() {
        Map<String, String> headers = createValidHeaders();
        headers.put("Sec-WebSocket-Key", "dGhlIHNhbXBsZQ=="); // Only 16 bytes, should be 24

        assertThatThrownBy(() -> WebSocketHandshake.validateRequest("GET", headers))
                .isInstanceOf(WebSocketHandshakeException.class);
    }

    @Test
    @DisplayName("Should reject missing Sec-WebSocket-Version")
    void testRejectMissingVersion() {
        Map<String, String> headers = createValidHeaders();
        headers.remove("Sec-WebSocket-Version");

        assertThatThrownBy(() -> WebSocketHandshake.validateRequest("GET", headers))
                .isInstanceOf(WebSocketHandshakeException.class)
                .hasMessageContaining("Version");
    }

    @Test
    @DisplayName("Should reject unsupported Sec-WebSocket-Version")
    void testRejectUnsupportedVersion() {
        Map<String, String> headers = createValidHeaders();
        headers.put("Sec-WebSocket-Version", "8");

        assertThatThrownBy(() -> WebSocketHandshake.validateRequest("GET", headers))
                .isInstanceOf(WebSocketHandshakeException.class)
                .hasMessageContaining("Version");
    }

    @Test
    @DisplayName("Should accept optional Sec-WebSocket-Protocol")
    void testOptionalProtocol() throws WebSocketHandshakeException {
        Map<String, String> headers = createValidHeaders();
        headers.put("Sec-WebSocket-Protocol", "chat");

        HandshakeRequest request = WebSocketHandshake.validateRequest("GET", headers);
        assertThat(request.getProtocol()).isEqualTo("chat");
    }

    @Test
    @DisplayName("Should accept optional Origin header")
    void testOptionalOrigin() throws WebSocketHandshakeException {
        Map<String, String> headers = createValidHeaders();
        headers.put("Origin", "http://example.com");

        HandshakeRequest request = WebSocketHandshake.validateRequest("GET", headers);
        assertThat(request.getOrigin()).isEqualTo("http://example.com");
    }

    @Test
    @DisplayName("Should handle case-insensitive headers")
    void testCaseInsensitiveHeaders() throws WebSocketHandshakeException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Host", "localhost:8080");
        headers.put("upgrade", "websocket");
        headers.put("connection", "Upgrade");
        headers.put("sec-websocket-key", VALID_SEC_KEY);
        headers.put("sec-websocket-version", "13");

        HandshakeRequest request = WebSocketHandshake.validateRequest("GET", headers);
        assertThat(request).isNotNull();
    }

    @Test
    @DisplayName("Should generate correct accept key")
    void testGenerateAcceptKey() throws WebSocketHandshakeException {
        Map<String, String> response = WebSocketHandshake.generateResponse(VALID_SEC_KEY, null);

        assertThat(response).containsEntry("Sec-WebSocket-Accept", EXPECTED_ACCEPT_KEY);
    }

    @Test
    @DisplayName("Should generate response headers")
    void testGenerateResponseHeaders() throws WebSocketHandshakeException {
        Map<String, String> response = WebSocketHandshake.generateResponse(VALID_SEC_KEY, null);

        assertThat(response)
                .containsEntry("Upgrade", "websocket")
                .containsEntry("Connection", "Upgrade")
                .hasSize(3); // Upgrade, Connection, Sec-WebSocket-Accept
    }

    @Test
    @DisplayName("Should include protocol in response if provided")
    void testGenerateResponseWithProtocol() throws WebSocketHandshakeException {
        Map<String, String> response = WebSocketHandshake.generateResponse(VALID_SEC_KEY, "chat");

        assertThat(response)
                .containsEntry("Sec-WebSocket-Protocol", "chat")
                .hasSize(4); // Upgrade, Connection, Sec-WebSocket-Accept, Protocol
    }

    @Test
    @DisplayName("Should not include protocol in response if not provided")
    void testGenerateResponseWithoutProtocol() throws WebSocketHandshakeException {
        Map<String, String> response = WebSocketHandshake.generateResponse(VALID_SEC_KEY, null);

        assertThat(response).doesNotContainKey("Sec-WebSocket-Protocol");
    }

    @Test
    @DisplayName("Should not include empty protocol in response")
    void testGenerateResponseWithEmptyProtocol() throws WebSocketHandshakeException {
        Map<String, String> response = WebSocketHandshake.generateResponse(VALID_SEC_KEY, "");

        assertThat(response).doesNotContainKey("Sec-WebSocket-Protocol");
    }

    @Test
    @DisplayName("Should generate valid accept key for different inputs")
    void testGenerateAcceptKeyConsistency() throws WebSocketHandshakeException {
        String key1 = "x3JJHMbDL1EzLkh9GBhXDw==";
        String key2 = "dGhlIHNhbXBsZSBub25jZQ==";

        Map<String, String> response1 = WebSocketHandshake.generateResponse(key1, null);
        Map<String, String> response2 = WebSocketHandshake.generateResponse(key2, null);

        assertThat(response1.get("Sec-WebSocket-Accept"))
                .isNotEqualTo(response2.get("Sec-WebSocket-Accept"));

        // Verify accept keys are always the same for the same input
        Map<String, String> response1Again = WebSocketHandshake.generateResponse(key1, null);
        assertThat(response1.get("Sec-WebSocket-Accept"))
                .isEqualTo(response1Again.get("Sec-WebSocket-Accept"));
    }

    @Test
    @DisplayName("Should handle WebSocket-Key with padding variations")
    void testSecKeyWithPaddingVariations() throws WebSocketHandshakeException {
        // Valid base64 with proper padding
        String key = "x3JJHMbDL1EzLkh9GBhXDw==";
        HandshakeRequest request = WebSocketHandshake.validateRequest("GET", createHeadersWithKey(key));

        assertThat(request).isNotNull();
    }

    @Test
    @DisplayName("Should accept Sec-WebSocket-Version with whitespace")
    void testVersionWithWhitespace() throws WebSocketHandshakeException {
        Map<String, String> headers = createValidHeaders();
        headers.put("Sec-WebSocket-Version", "  13  ");

        HandshakeRequest request = WebSocketHandshake.validateRequest("GET", headers);
        assertThat(request).isNotNull();
    }

    @Test
    @DisplayName("Should validate handshake roundtrip")
    void testHandshakeRoundtrip() throws WebSocketHandshakeException {
        Map<String, String> requestHeaders = createValidHeaders();
        String secKey = requestHeaders.get("Sec-WebSocket-Key");

        // Validate request
        HandshakeRequest request = WebSocketHandshake.validateRequest("GET", requestHeaders);
        assertThat(request.getSecWebSocketKey()).isEqualTo(secKey);

        // Generate response
        Map<String, String> responseHeaders = WebSocketHandshake.generateResponse(secKey, "chat");
        assertThat(responseHeaders).containsKey("Sec-WebSocket-Accept");
        assertThat(responseHeaders).containsEntry("Sec-WebSocket-Protocol", "chat");
    }

    private Map<String, String> createHeadersWithKey(String key) {
        Map<String, String> headers = createValidHeaders();
        headers.put("Sec-WebSocket-Key", key);
        return headers;
    }
}
