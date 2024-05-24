package HTTPServer.tests;

import HTTPServer.WebSocketFrame;
import HTTPServer.WebSocketFrame.WebSocketException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.*;

@DisplayName("WebSocket Frame Tests")
class WebSocketFrameTest {

    @Test
    @DisplayName("Should create text frame")
    void testCreateTextFrame() {
        byte[] payload = "Hello".getBytes();
        WebSocketFrame frame = new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, false, payload);

        assertThat(frame.isFin()).isTrue();
        assertThat(frame.getOpcode()).isEqualTo(WebSocketFrame.OPCODE_TEXT);
        assertThat(frame.isMasked()).isFalse();
        assertThat(frame.getPayload()).isEqualTo(payload);
        assertThat(frame.getPayloadAsText()).isEqualTo("Hello");
    }

    @Test
    @DisplayName("Should create binary frame")
    void testCreateBinaryFrame() {
        byte[] payload = {1, 2, 3, 4, 5};
        WebSocketFrame frame = new WebSocketFrame(true, WebSocketFrame.OPCODE_BINARY, false, payload);

        assertThat(frame.isBinaryFrame()).isTrue();
        assertThat(frame.getPayload()).isEqualTo(payload);
    }

    @Test
    @DisplayName("Should create control frame (ping)")
    void testCreatePingFrame() {
        WebSocketFrame frame = new WebSocketFrame(true, WebSocketFrame.OPCODE_PING, false, new byte[0]);

        assertThat(frame.isPingFrame()).isTrue();
        assertThat(frame.isControlFrame()).isTrue();
    }

    @Test
    @DisplayName("Should create control frame (pong)")
    void testCreatePongFrame() {
        WebSocketFrame frame = new WebSocketFrame(true, WebSocketFrame.OPCODE_PONG, false, new byte[0]);

        assertThat(frame.isPongFrame()).isTrue();
        assertThat(frame.isControlFrame()).isTrue();
    }

    @Test
    @DisplayName("Should create control frame (close)")
    void testCreateCloseFrame() {
        WebSocketFrame frame = new WebSocketFrame(true, WebSocketFrame.OPCODE_CLOSE, false, new byte[0]);

        assertThat(frame.isCloseFrame()).isTrue();
        assertThat(frame.isControlFrame()).isTrue();
    }

    @Test
    @DisplayName("Should create masked frame")
    void testCreateMaskedFrame() {
        byte[] payload = "Hello".getBytes();
        byte[] maskingKey = {1, 2, 3, 4};
        WebSocketFrame frame = new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, true, maskingKey, payload);

        assertThat(frame.isMasked()).isTrue();
        assertThat(frame.getMaskingKey()).isEqualTo(maskingKey);
    }

    @Test
    @DisplayName("Should reject masked frame without masking key")
    void testRejectMaskedWithoutKey() {
        assertThatThrownBy(() -> {
            new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, true, null, "Hello".getBytes());
        }).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should reject masking key with wrong length")
    void testRejectWrongMaskingKeyLength() {
        assertThatThrownBy(() -> {
            new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, true, new byte[3], "Hello".getBytes());
        }).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should encode unmasked frame")
    void testEncodeUnmaskedFrame() {
        byte[] payload = "Hi".getBytes();
        WebSocketFrame frame = new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, false, payload);
        byte[] encoded = frame.encode();

        assertThat(encoded).isNotNull();
        assertThat(encoded.length).isGreaterThanOrEqualTo(2 + payload.length);
        // First byte should have FIN bit set and TEXT opcode
        assertThat(encoded[0]).isEqualTo((byte) 0x81); // 10000001 = FIN + TEXT
    }

    @Test
    @DisplayName("Should encode masked frame")
    void testEncodeMaskedFrame() {
        byte[] payload = "Hi".getBytes();
        byte[] maskingKey = {1, 2, 3, 4};
        WebSocketFrame frame = new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, true, maskingKey, payload);
        byte[] encoded = frame.encode();

        assertThat(encoded).isNotNull();
        // Should have masking key in the frame
        assertThat(encoded.length).isGreaterThanOrEqualTo(2 + 4 + payload.length);
    }

    @Test
    @DisplayName("Should encode frame with extended 16-bit payload length")
    void testEncodeExtended16BitLength() {
        byte[] payload = new byte[200];
        Arrays.fill(payload, (byte) 42);
        WebSocketFrame frame = new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, false, payload);
        byte[] encoded = frame.encode();

        assertThat(encoded).isNotNull();
        // Should have extended 16-bit length
        assertThat(encoded[1] & 0x7F).isEqualTo(126);
    }

    @Test
    @DisplayName("Should encode frame with extended 64-bit payload length")
    void testEncodeExtended64BitLength() {
        byte[] payload = new byte[70000];
        Arrays.fill(payload, (byte) 42);
        WebSocketFrame frame = new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, false, payload);
        byte[] encoded = frame.encode();

        assertThat(encoded).isNotNull();
        // Should have extended 64-bit length
        assertThat(encoded[1] & 0x7F).isEqualTo(127);
    }

    @Test
    @DisplayName("Should parse simple unmasked frame")
    void testParseUnmaskedFrame() throws WebSocketException {
        byte[] payload = "Hello".getBytes();
        WebSocketFrame original = new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, false, payload);
        byte[] encoded = original.encode();

        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        WebSocketFrame parsed = WebSocketFrame.parse(buffer);

        assertThat(parsed).isNotNull();
        assertThat(parsed.isFin()).isTrue();
        assertThat(parsed.getOpcode()).isEqualTo(WebSocketFrame.OPCODE_TEXT);
        assertThat(parsed.isMasked()).isFalse();
        assertThat(parsed.getPayload()).isEqualTo(payload);
    }

    @Test
    @DisplayName("Should parse masked frame")
    void testParseMaskedFrame() throws WebSocketException {
        byte[] payload = "Hello".getBytes();
        byte[] maskingKey = {0x12, 0x34, 0x56, 0x78};
        WebSocketFrame original = new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, true, maskingKey, payload);
        byte[] encoded = original.encode();

        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        WebSocketFrame parsed = WebSocketFrame.parse(buffer);

        assertThat(parsed).isNotNull();
        assertThat(parsed.isMasked()).isTrue();
        assertThat(parsed.getPayload()).isEqualTo(payload);
    }

    @Test
    @DisplayName("Should return null if insufficient data")
    void testParseInsufficientData() throws WebSocketException {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[1]);
        WebSocketFrame frame = WebSocketFrame.parse(buffer);

        assertThat(frame).isNull();
    }

    @Test
    @DisplayName("Should parse continuation frame")
    void testParseContinuationFrame() throws WebSocketException {
        byte[] payload = "world".getBytes();
        WebSocketFrame original = new WebSocketFrame(false, WebSocketFrame.OPCODE_CONTINUATION, false, payload);
        byte[] encoded = original.encode();

        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        WebSocketFrame parsed = WebSocketFrame.parse(buffer);

        assertThat(parsed).isNotNull();
        assertThat(parsed.isContinuationFrame()).isTrue();
        assertThat(parsed.isFin()).isFalse();
    }

    @Test
    @DisplayName("Should reject RSV bits set")
    void testRejectRSVBits() {
        // Create a frame with RSV bit set (invalid)
        byte[] frameData = {(byte) 0xC0, 0x05, 'H', 'e', 'l', 'l', 'o'}; // RSV1 bit set
        ByteBuffer buffer = ByteBuffer.wrap(frameData);

        assertThatThrownBy(() -> WebSocketFrame.parse(buffer))
                .isInstanceOf(WebSocketException.class)
                .hasMessageContaining("RSV bits");
    }

    @Test
    @DisplayName("Should parse frame with 16-bit extended length")
    void testParse16BitExtendedLength() throws WebSocketException {
        byte[] payload = new byte[200];
        Arrays.fill(payload, (byte) 42);
        WebSocketFrame original = new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, false, payload);
        byte[] encoded = original.encode();

        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        WebSocketFrame parsed = WebSocketFrame.parse(buffer);

        assertThat(parsed).isNotNull();
        assertThat(parsed.getPayloadLength()).isEqualTo(200);
        assertThat(parsed.getPayload()).isEqualTo(payload);
    }

    @Test
    @DisplayName("Should parse frame with 64-bit extended length")
    void testParse64BitExtendedLength() throws WebSocketException {
        byte[] payload = new byte[70000];
        Arrays.fill(payload, (byte) 42);
        WebSocketFrame original = new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, false, payload);
        byte[] encoded = original.encode();

        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        WebSocketFrame parsed = WebSocketFrame.parse(buffer);

        assertThat(parsed).isNotNull();
        assertThat(parsed.getPayloadLength()).isEqualTo(70000);
    }

    @Test
    @DisplayName("Should handle fragmented message (text)")
    void testFragmentedTextMessage() throws WebSocketException {
        // First frame: "Hello"
        WebSocketFrame frame1 = new WebSocketFrame(false, WebSocketFrame.OPCODE_TEXT, false, "Hello".getBytes());
        // Continuation frame: " World"
        WebSocketFrame frame2 = new WebSocketFrame(true, WebSocketFrame.OPCODE_CONTINUATION, false, " World".getBytes());

        byte[] encoded1 = frame1.encode();
        byte[] encoded2 = frame2.encode();

        ByteBuffer buffer = ByteBuffer.wrap(encoded1);
        WebSocketFrame parsed1 = WebSocketFrame.parse(buffer);

        assertThat(parsed1.isFin()).isFalse();
        assertThat(parsed1.getPayloadAsText()).isEqualTo("Hello");

        buffer = ByteBuffer.wrap(encoded2);
        WebSocketFrame parsed2 = WebSocketFrame.parse(buffer);

        assertThat(parsed2.isFin()).isTrue();
        assertThat(parsed2.isContinuationFrame()).isTrue();
    }

    @Test
    @DisplayName("Should handle empty payload")
    void testEmptyPayload() throws WebSocketException {
        WebSocketFrame frame = new WebSocketFrame(true, WebSocketFrame.OPCODE_PING, false, new byte[0]);
        byte[] encoded = frame.encode();

        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        WebSocketFrame parsed = WebSocketFrame.parse(buffer);

        assertThat(parsed).isNotNull();
        assertThat(parsed.getPayloadLength()).isZero();
    }

    @Test
    @DisplayName("Should handle UTF-8 text")
    void testUTF8Text() throws WebSocketException {
        String text = "Hello 世界 мир 🌍";
        byte[] payload = text.getBytes();
        WebSocketFrame frame = new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, false, payload);
        byte[] encoded = frame.encode();

        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        WebSocketFrame parsed = WebSocketFrame.parse(buffer);

        assertThat(parsed.getPayloadAsText()).isEqualTo(text);
    }

    @Test
    @DisplayName("Should identify control frames")
    void testIdentifyControlFrames() {
        assertThat(new WebSocketFrame(true, WebSocketFrame.OPCODE_PING, false, new byte[0]).isControlFrame()).isTrue();
        assertThat(new WebSocketFrame(true, WebSocketFrame.OPCODE_PONG, false, new byte[0]).isControlFrame()).isTrue();
        assertThat(new WebSocketFrame(true, WebSocketFrame.OPCODE_CLOSE, false, new byte[0]).isControlFrame()).isTrue();
        assertThat(new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, false, new byte[0]).isControlFrame()).isFalse();
        assertThat(new WebSocketFrame(true, WebSocketFrame.OPCODE_BINARY, false, new byte[0]).isControlFrame()).isFalse();
    }

    @Test
    @DisplayName("Should maintain payload integrity through encode/decode")
    void testPayloadIntegrity() throws WebSocketException {
        byte[] originalPayload = {0, 1, 2, 3, 127, (byte) 128, (byte) 254, (byte) 255};
        WebSocketFrame original = new WebSocketFrame(true, WebSocketFrame.OPCODE_BINARY, false, originalPayload);
        byte[] encoded = original.encode();

        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        WebSocketFrame parsed = WebSocketFrame.parse(buffer);

        assertThat(parsed.getPayload()).isEqualTo(originalPayload);
    }

    @Test
    @DisplayName("Should handle multiple frames in sequence")
    void testMultipleFramesInSequence() throws WebSocketException {
        WebSocketFrame frame1 = new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, false, "Frame1".getBytes());
        WebSocketFrame frame2 = new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, false, "Frame2".getBytes());

        byte[] encoded1 = frame1.encode();
        byte[] encoded2 = frame2.encode();

        // Parse each frame separately
        ByteBuffer buffer1 = ByteBuffer.wrap(encoded1);
        WebSocketFrame parsed1 = WebSocketFrame.parse(buffer1);

        ByteBuffer buffer2 = ByteBuffer.wrap(encoded2);
        WebSocketFrame parsed2 = WebSocketFrame.parse(buffer2);

        assertThat(parsed1.getPayloadAsText()).isEqualTo("Frame1");
        assertThat(parsed2.getPayloadAsText()).isEqualTo("Frame2");
    }
}
