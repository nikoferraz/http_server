package HTTPServer.tests;

import HTTPServer.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("WebSocket Integration Tests")
class WebSocketIntegrationTest {

    private TestWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TestWebSocketHandler();
    }

    @Test
    @DisplayName("Should validate complete WebSocket handshake flow")
    void testCompleteHandshakeFlow() throws Exception {
        // Create handshake request headers
        Map<String, String> requestHeaders = new HashMap<>();
        requestHeaders.put("Upgrade", "websocket");
        requestHeaders.put("Connection", "Upgrade");
        requestHeaders.put("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==");
        requestHeaders.put("Sec-WebSocket-Version", "13");

        // Validate request
        WebSocketHandshake.HandshakeRequest request = WebSocketHandshake.validateRequest("GET", requestHeaders);
        assertThat(request).isNotNull();

        // Generate response
        Map<String, String> responseHeaders = WebSocketHandshake.generateResponse(
                request.getSecWebSocketKey(), null
        );

        assertThat(responseHeaders)
                .containsKey("Upgrade")
                .containsKey("Connection")
                .containsKey("Sec-WebSocket-Accept");
    }

    @Test
    @DisplayName("Should handle frame encoding and decoding roundtrip")
    void testFrameRoundTrip() throws Exception {
        String originalMessage = "Hello WebSocket";
        byte[] payload = originalMessage.getBytes();

        // Create and encode frame
        WebSocketFrame frame = new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, false, payload);
        byte[] encoded = frame.encode();

        // Decode frame
        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        WebSocketFrame decoded = WebSocketFrame.parse(buffer);

        assertThat(decoded.getPayloadAsText()).isEqualTo(originalMessage);
        assertThat(decoded.isFin()).isTrue();
        assertThat(decoded.isTextFrame()).isTrue();
    }

    @Test
    @DisplayName("Should handle masked frames from client")
    void testMaskedFrameHandling() throws Exception {
        String originalMessage = "Masked message";
        byte[] payload = originalMessage.getBytes();
        byte[] maskingKey = {0x01, 0x02, 0x03, 0x04};

        // Create masked frame (as client would send)
        WebSocketFrame frame = new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, true, maskingKey, payload);
        byte[] encoded = frame.encode();

        // Decode frame (server receives)
        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        WebSocketFrame decoded = WebSocketFrame.parse(buffer);

        // Payload should be unmasked correctly
        assertThat(decoded.getPayloadAsText()).isEqualTo(originalMessage);
        assertThat(decoded.isMasked()).isTrue();
    }

    @Test
    @DisplayName("Should handle fragmented messages")
    void testFragmentedMessage() throws Exception {
        // First fragment
        WebSocketFrame frame1 = new WebSocketFrame(false, WebSocketFrame.OPCODE_TEXT, false, "Hello ".getBytes());
        // Second fragment (continuation)
        WebSocketFrame frame2 = new WebSocketFrame(false, WebSocketFrame.OPCODE_CONTINUATION, false, "WebSocket ".getBytes());
        // Final fragment
        WebSocketFrame frame3 = new WebSocketFrame(true, WebSocketFrame.OPCODE_CONTINUATION, false, "World".getBytes());

        // Verify frames are created correctly
        assertThat(frame1.isFin()).isFalse();
        assertThat(frame1.isTextFrame()).isTrue();

        assertThat(frame2.isFin()).isFalse();
        assertThat(frame2.isContinuationFrame()).isTrue();

        assertThat(frame3.isFin()).isTrue();
        assertThat(frame3.isContinuationFrame()).isTrue();
    }

    @Test
    @DisplayName("Should handle control frames (ping/pong)")
    void testControlFrames() throws Exception {
        // Ping frame
        WebSocketFrame pingFrame = new WebSocketFrame(true, WebSocketFrame.OPCODE_PING, false, "ping".getBytes());
        byte[] pingEncoded = pingFrame.encode();

        ByteBuffer buffer = ByteBuffer.wrap(pingEncoded);
        WebSocketFrame parsed = WebSocketFrame.parse(buffer);

        assertThat(parsed.isPingFrame()).isTrue();
        assertThat(parsed.isControlFrame()).isTrue();
        assertThat(parsed.getPayloadAsText()).isEqualTo("ping");

        // Pong frame
        WebSocketFrame pongFrame = new WebSocketFrame(true, WebSocketFrame.OPCODE_PONG, false, "pong".getBytes());
        byte[] pongEncoded = pongFrame.encode();

        buffer = ByteBuffer.wrap(pongEncoded);
        parsed = WebSocketFrame.parse(buffer);

        assertThat(parsed.isPongFrame()).isTrue();
        assertThat(parsed.isControlFrame()).isTrue();
    }

    @Test
    @DisplayName("Should handle close frames with status codes")
    void testCloseFrames() throws Exception {
        // Create close frame with status code and reason
        ByteBuffer closePayload = ByteBuffer.allocate(2 + 4);
        closePayload.putShort((short) 1000); // Normal closure
        closePayload.put("bye".getBytes());
        closePayload.flip();

        WebSocketFrame closeFrame = new WebSocketFrame(
                true, WebSocketFrame.OPCODE_CLOSE, false, closePayload.array()
        );
        byte[] encoded = closeFrame.encode();

        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        WebSocketFrame parsed = WebSocketFrame.parse(buffer);

        assertThat(parsed.isCloseFrame()).isTrue();
        assertThat(parsed.isControlFrame()).isTrue();
    }

    @Test
    @DisplayName("Should support echo handler")
    void testEchoHandler() {
        WebSocketEchoHandler echoHandler = new WebSocketEchoHandler();
        assertThat(echoHandler).isNotNull();
    }

    @Test
    @DisplayName("Should support chat handler")
    void testChatHandler() {
        WebSocketChatHandler chatHandler = new WebSocketChatHandler("test-room");
        assertThat(chatHandler.getRoomName()).isEqualTo("test-room");
        assertThat(chatHandler.getClientCount()).isZero();
    }

    @Test
    @DisplayName("Should create connection stats")
    void testConnectionStats() {
        WebSocketConnection.ConnectionStats stats = new WebSocketConnection.ConnectionStats(
                100, 50, 10000, 5000, 1000
        );

        assertThat(stats.messagesReceived).isEqualTo(100);
        assertThat(stats.messagesSent).isEqualTo(50);
        assertThat(stats.bytesReceived).isEqualTo(10000);
        assertThat(stats.bytesSent).isEqualTo(5000);
        assertThat(stats.idleTimeMs).isEqualTo(1000);
    }

    @Test
    @DisplayName("Should handle multiple frames with different opcodes")
    void testMultipleFrameTypes() throws Exception {
        // Text frame
        WebSocketFrame textFrame = new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, false, "text".getBytes());
        assertThat(textFrame.isTextFrame()).isTrue();
        assertThat(textFrame.isBinaryFrame()).isFalse();

        // Binary frame
        WebSocketFrame binaryFrame = new WebSocketFrame(true, WebSocketFrame.OPCODE_BINARY, false, new byte[]{1, 2, 3});
        assertThat(binaryFrame.isBinaryFrame()).isTrue();
        assertThat(binaryFrame.isTextFrame()).isFalse();

        // Ping frame
        WebSocketFrame pingFrame = new WebSocketFrame(true, WebSocketFrame.OPCODE_PING, false, new byte[0]);
        assertThat(pingFrame.isPingFrame()).isTrue();

        // Pong frame
        WebSocketFrame pongFrame = new WebSocketFrame(true, WebSocketFrame.OPCODE_PONG, false, new byte[0]);
        assertThat(pongFrame.isPongFrame()).isTrue();

        // Close frame
        WebSocketFrame closeFrame = new WebSocketFrame(true, WebSocketFrame.OPCODE_CLOSE, false, new byte[0]);
        assertThat(closeFrame.isCloseFrame()).isTrue();
    }

    @Test
    @DisplayName("Should handle large payloads")
    void testLargePayload() throws Exception {
        byte[] largePayload = new byte[100000];
        for (int i = 0; i < largePayload.length; i++) {
            largePayload[i] = (byte) (i % 256);
        }

        WebSocketFrame frame = new WebSocketFrame(true, WebSocketFrame.OPCODE_BINARY, false, largePayload);
        byte[] encoded = frame.encode();

        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        WebSocketFrame decoded = WebSocketFrame.parse(buffer);

        assertThat(decoded.getPayloadLength()).isEqualTo(largePayload.length);
        assertThat(decoded.getPayload()).isEqualTo(largePayload);
    }

    @Test
    @DisplayName("Should handle UTF-8 with special characters")
    void testUTF8SpecialCharacters() throws Exception {
        String message = "Hello 世界 🌍 مرحبا";
        byte[] payload = message.getBytes();

        WebSocketFrame frame = new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, false, payload);
        byte[] encoded = frame.encode();

        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        WebSocketFrame decoded = WebSocketFrame.parse(buffer);

        assertThat(decoded.getPayloadAsText()).isEqualTo(message);
    }

    /**
     * Test handler that tracks callbacks
     */
    static class TestWebSocketHandler implements WebSocketHandler {
        public int openCount = 0;
        public int messageCount = 0;
        public int errorCount = 0;
        public int closeCount = 0;

        @Override
        public void onOpen(WebSocketConnection connection) {
            openCount++;
        }

        @Override
        public void onMessage(WebSocketConnection connection, String message) {
            messageCount++;
        }

        @Override
        public void onMessage(WebSocketConnection connection, byte[] message) {
            messageCount++;
        }

        @Override
        public void onError(WebSocketConnection connection, Throwable error) {
            errorCount++;
        }

        @Override
        public void onClose(WebSocketConnection connection, int statusCode, String reason) {
            closeCount++;
        }
    }
}
