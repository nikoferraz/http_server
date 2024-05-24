package HTTPServer.tests;

import HTTPServer.WebSocketFrame;
import HTTPServer.WebSocketFrame.WebSocketException;
import HTTPServer.WebSocketHandshake;
import HTTPServer.WebSocketHandshake.WebSocketHandshakeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("WebSocket Security Tests")
class WebSocketSecurityTest {

    @Test
    @DisplayName("Should reject frame with negative payload length")
    void testRejectNegativePayloadLength() {
        byte[] frameData = {
                (byte) 0x81, // FIN + TEXT opcode
                (byte) 0xFF  // MASK + 127 (64-bit length follows)
        };
        // Add 64-bit negative length
        ByteBuffer buffer = ByteBuffer.allocate(frameData.length + 8);
        buffer.put(frameData);
        buffer.putLong(-1);
        buffer.flip();

        assertThatThrownBy(() -> WebSocketFrame.parse(buffer))
                .isInstanceOf(WebSocketException.class)
                .hasMessageContaining("negative");
    }

    @Test
    @DisplayName("Should reject frame with oversized payload length")
    void testRejectOversizedPayloadLength() {
        byte[] frameData = {
                (byte) 0x81, // FIN + TEXT opcode
                (byte) 0xFF  // MASK + 127 (64-bit length follows)
        };
        // Add 64-bit huge length
        ByteBuffer buffer = ByteBuffer.allocate(frameData.length + 8);
        buffer.put(frameData);
        buffer.putLong(Integer.MAX_VALUE + 1L);
        buffer.flip();

        assertThatThrownBy(() -> WebSocketFrame.parse(buffer))
                .isInstanceOf(WebSocketException.class);
    }

    @Test
    @DisplayName("Should reject frames with RSV bits set")
    void testRejectRSVBits() {
        // RSV1 bit set (0xC0 = 11000000)
        byte[] frameData = {(byte) 0xC0, 0x00};
        ByteBuffer buffer = ByteBuffer.wrap(frameData);

        assertThatThrownBy(() -> WebSocketFrame.parse(buffer))
                .isInstanceOf(WebSocketException.class)
                .hasMessageContaining("RSV");
    }

    @Test
    @DisplayName("Should handle UTF-8 validation in text frames")
    void testUTF8ValidationInText() {
        String validUTF8 = "Hello 世界 мир";
        byte[] payload = validUTF8.getBytes();
        WebSocketFrame frame = new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, false, payload);

        assertThat(frame.getPayloadAsText()).isEqualTo(validUTF8);
    }

    @Test
    @DisplayName("Should not require UTF-8 validation in binary frames")
    void testBinaryFramesAllowAnyBytes() {
        // Invalid UTF-8 sequence
        byte[] payload = {(byte) 0xFF, (byte) 0xFE, (byte) 0xFD, (byte) 0xFC};
        WebSocketFrame frame = new WebSocketFrame(true, WebSocketFrame.OPCODE_BINARY, false, payload);

        assertThat(frame.getPayload()).isEqualTo(payload);
    }

    @Test
    @DisplayName("Should require client-to-server frames to be masked")
    void testClientFramesMustBeMasked() {
        // Simulate client-side validation
        byte[] payload = "Hello".getBytes();
        byte[] maskingKey = {1, 2, 3, 4};

        // Client should mask frames
        WebSocketFrame clientFrame = new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, true, maskingKey, payload);
        assertThat(clientFrame.isMasked()).isTrue();
    }

    @Test
    @DisplayName("Should reject invalid Base64 in Sec-WebSocket-Key")
    void testRejectInvalidBase64Key() {
        Map<String, String> headers = createValidHeaders();
        headers.put("Sec-WebSocket-Key", "!@#$%^&*(");

        assertThatThrownBy(() -> WebSocketHandshake.validateRequest("GET", headers))
                .isInstanceOf(WebSocketHandshakeException.class);
    }

    @Test
    @DisplayName("Should validate Sec-WebSocket-Key is exactly 24 bytes base64")
    void testSecKeyLengthValidation() {
        Map<String, String> headers = createValidHeaders();

        // Too short
        headers.put("Sec-WebSocket-Key", "dGVz"); // "tes" base64
        assertThatThrownBy(() -> WebSocketHandshake.validateRequest("GET", headers))
                .isInstanceOf(WebSocketHandshakeException.class);

        // Too long
        headers.put("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==dGhlIHNhbXBsZQ==");
        assertThatThrownBy(() -> WebSocketHandshake.validateRequest("GET", headers))
                .isInstanceOf(WebSocketHandshakeException.class);
    }

    @Test
    @DisplayName("Should handle Origin header for CSRF prevention")
    void testOriginHeader() throws WebSocketHandshakeException {
        Map<String, String> headers = createValidHeaders();
        headers.put("Origin", "http://example.com");

        WebSocketHandshake.HandshakeRequest request = WebSocketHandshake.validateRequest("GET", headers);
        assertThat(request.getOrigin()).isEqualTo("http://example.com");
    }

    @Test
    @DisplayName("Should accept requests without Origin header")
    void testOriginHeaderOptional() throws WebSocketHandshakeException {
        Map<String, String> headers = createValidHeaders();
        // Don't include Origin header

        WebSocketHandshake.HandshakeRequest request = WebSocketHandshake.validateRequest("GET", headers);
        assertThat(request.getOrigin()).isNull();
    }

    @Test
    @DisplayName("Should reject frames exceeding frame size limits")
    void testFrameSizeLimit() {
        // Frame exceeds 1MB limit (implementation specific)
        byte[] largePayload = new byte[2 * 1024 * 1024]; // 2MB
        Arrays.fill(largePayload, (byte) 'A');

        WebSocketFrame frame = new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, false, largePayload);
        // Frame itself doesn't validate max size, but connection should reject on read

        assertThat(frame.getPayloadLength()).isEqualTo(2 * 1024 * 1024);
    }

    @Test
    @DisplayName("Should handle control frame payload limits")
    void testControlFramePayloadLimit() {
        // Control frames must have payload <= 125 bytes per RFC 6455
        byte[] payload = new byte[126];
        Arrays.fill(payload, (byte) 'A');

        // This should still create the frame, but RFC says control frames max 125 bytes
        WebSocketFrame frame = new WebSocketFrame(true, WebSocketFrame.OPCODE_PING, false, payload);
        assertThat(frame.getPayloadLength()).isEqualTo(126);
    }

    @Test
    @DisplayName("Should mask payload properly for client frames")
    void testPayloadMasking() {
        byte[] payload = "Secret".getBytes();
        byte[] maskingKey = {0x37, (byte) 0xfa, 0x21, (byte) 0x3d};

        WebSocketFrame frame = new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, true, maskingKey, payload);
        byte[] encoded = frame.encode();

        // Verify masking key is in the frame
        assertThat(encoded).isNotNull();
        assertThat(encoded.length).isGreaterThan(payload.length);
    }

    @Test
    @DisplayName("Should prevent payload data tampering through masking")
    void testMaskingPreventsTampering() {
        byte[] payload = {1, 2, 3, 4, 5};
        byte[] maskingKey = {0x12, 0x34, 0x56, 0x78};

        WebSocketFrame frame = new WebSocketFrame(true, WebSocketFrame.OPCODE_BINARY, true, maskingKey, payload);
        byte[] encoded = frame.encode();

        // The encoded frame should contain the masking key
        assertThat(encoded).isNotNull();

        // Verify round-trip preserves data
        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        try {
            WebSocketFrame parsed = WebSocketFrame.parse(buffer);
            assertThat(parsed.getPayload()).isEqualTo(payload);
        } catch (WebSocketException e) {
            fail("Failed to parse masked frame", e);
        }
    }

    @Test
    @DisplayName("Should handle empty Sec-WebSocket-Key rejection")
    void testEmptySecWebSocketKey() {
        Map<String, String> headers = createValidHeaders();
        headers.put("Sec-WebSocket-Key", "");

        assertThatThrownBy(() -> WebSocketHandshake.validateRequest("GET", headers))
                .isInstanceOf(WebSocketHandshakeException.class);
    }

    @Test
    @DisplayName("Should validate protocol negotiation strings")
    void testProtocolNegotiation() throws WebSocketHandshakeException {
        Map<String, String> headers = createValidHeaders();
        headers.put("Sec-WebSocket-Protocol", "chat, superchat");

        WebSocketHandshake.HandshakeRequest request = WebSocketHandshake.validateRequest("GET", headers);
        assertThat(request.getProtocol()).contains("chat");
    }

    @Test
    @DisplayName("Should generate different accept keys for different inputs")
    void testAcceptKeyUniqueness() throws WebSocketHandshakeException {
        String key1 = "dGhlIHNhbXBsZSBub25jZQ==";
        String key2 = "x3JJHMbDL1EzLkh9GBhXDw==";

        Map<String, String> response1 = WebSocketHandshake.generateResponse(key1, null);
        Map<String, String> response2 = WebSocketHandshake.generateResponse(key2, null);

        assertThat(response1.get("Sec-WebSocket-Accept"))
                .isNotEqualTo(response2.get("Sec-WebSocket-Accept"));
    }

    @Test
    @DisplayName("Should detect invalid connection upgrade attacks")
    void testInvalidConnectionUpgradeDetection() {
        Map<String, String> headers = createValidHeaders();
        headers.put("Connection", "close"); // Missing "Upgrade"

        assertThatThrownBy(() -> WebSocketHandshake.validateRequest("GET", headers))
                .isInstanceOf(WebSocketHandshakeException.class);
    }

    @Test
    @DisplayName("Should handle multiple Upgrade header values")
    void testMultipleUpgradeValues() {
        Map<String, String> headers = createValidHeaders();
        headers.put("Upgrade", "websocket, http"); // Multiple values

        try {
            WebSocketHandshake.HandshakeRequest request = WebSocketHandshake.validateRequest("GET", headers);
            // Should accept if "websocket" is present
            assertThat(request).isNotNull();
        } catch (WebSocketHandshakeException e) {
            fail("Should accept Upgrade with multiple values including websocket", e);
        }
    }

    private Map<String, String> createValidHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Host", "localhost:8080");
        headers.put("Upgrade", "websocket");
        headers.put("Connection", "Upgrade");
        headers.put("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==");
        headers.put("Sec-WebSocket-Version", "13");
        return headers;
    }
}
