package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.*;

public class HTTP2FrameEdgeCasesTest {

    private HTTP2FrameParser parser;

    @BeforeEach
    public void setUp() {
        parser = new HTTP2FrameParser();
    }

    @Nested
    class FrameSizeLimitTests {

        @Test
        public void testMaximumFrameSize() {
            // 16 MB is the maximum payload size (2^24 - 1)
            ByteBuffer buffer = ByteBuffer.allocate(9);
            buffer.put((byte) 0xFF); // Length byte 1
            buffer.put((byte) 0xFF); // Length byte 2
            buffer.put((byte) 0xFF); // Length byte 3 = 16,777,215 bytes
            buffer.put((byte) 0x00); // Type: DATA
            buffer.put((byte) 0x00); // Flags: none
            buffer.put((byte) 0x00); // Stream ID byte 1
            buffer.put((byte) 0x00); // Stream ID byte 2
            buffer.put((byte) 0x00); // Stream ID byte 3
            buffer.put((byte) 0x01); // Stream ID byte 4: 1
            buffer.position(0);

            // Parse should accept this size but return null due to missing payload
            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNull(); // Incomplete payload
        }

        @Test
        public void testOversizedFrame() {
            // Maximum 3-byte length value: 0xFFFFFF (16,777,215)
            // This is the maximum allowed, so should be accepted but incomplete
            ByteBuffer buffer = ByteBuffer.allocate(9);
            buffer.put((byte) 0xFF); // Length byte 1
            buffer.put((byte) 0xFF); // Length byte 2
            buffer.put((byte) 0xFF); // Length byte 3: 0xFFFFFF (max 3-byte value)
            buffer.put((byte) 0x00); // Type: DATA
            buffer.put((byte) 0x00); // Flags: none
            buffer.put((byte) 0x00); // Stream ID byte 1
            buffer.put((byte) 0x00); // Stream ID byte 2
            buffer.put((byte) 0x00); // Stream ID byte 3
            buffer.put((byte) 0x01); // Stream ID byte 4: 1
            buffer.position(0);

            // Should return null because payload is incomplete (need 16MB+ but have 0)
            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNull();
        }

        @Test
        public void testZeroLengthFrame() {
            ByteBuffer buffer = ByteBuffer.allocate(9);
            buffer.put(0, (byte) 0x00); // Length byte 1
            buffer.put(1, (byte) 0x00); // Length byte 2
            buffer.put(2, (byte) 0x00); // Length byte 3
            buffer.put(3, (byte) 0x04); // Type: SETTINGS
            buffer.put(4, (byte) 0x00); // Flags: none
            buffer.put(5, (byte) 0x00); // Stream ID byte 1
            buffer.put(6, (byte) 0x00); // Stream ID byte 2
            buffer.put(7, (byte) 0x00); // Stream ID byte 3
            buffer.put(8, (byte) 0x00); // Stream ID byte 4: 0
            buffer.position(0);

            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getLength()).isEqualTo(0);
        }

        @Test
        public void testLargePayloadAllocation() {
            // Test with 1MB payload
            byte[] payload = new byte[1048576]; // 1MB
            HTTP2Frame frame = new HTTP2Frame(HTTP2Frame.FrameType.DATA, (byte) 0x00, 1, payload);

            ByteBuffer encoded = parser.encodeFrame(frame);
            assertThat(encoded).isNotNull();
            assertThat(encoded.remaining()).isGreaterThanOrEqualTo(9 + 1048576);
        }
    }

    @Nested
    class InvalidFrameTypeTests {

        private ByteBuffer createFrame(byte type, int payloadLen) {
            ByteBuffer buffer = ByteBuffer.allocate(9 + payloadLen);
            buffer.put(0, (byte) ((payloadLen >> 16) & 0xFF));
            buffer.put(1, (byte) ((payloadLen >> 8) & 0xFF));
            buffer.put(2, (byte) (payloadLen & 0xFF));
            buffer.put(3, type);
            buffer.put(4, (byte) 0x00); // Flags
            buffer.put(5, (byte) 0x00);
            buffer.put(6, (byte) 0x00);
            buffer.put(7, (byte) 0x00);
            buffer.put(8, (byte) 0x01); // Stream ID: 1
            buffer.position(0);
            return buffer;
        }

        @Test
        public void testInvalidFrameType() {
            ByteBuffer buffer = createFrame((byte) 0xFF, 0);
            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNull(); // Should reject unknown frame type
        }

        @Test
        public void testInvalidFrameTypeWithPayload() {
            ByteBuffer buffer = createFrame((byte) 0xAB, 5);
            buffer.put(9, new byte[]{1, 2, 3, 4, 5});
            buffer.position(0);
            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNull();
        }
    }

    @Nested
    class StreamIdValidationTests {

        private ByteBuffer createFrame(int streamId, byte type) {
            ByteBuffer buffer = ByteBuffer.allocate(9);
            buffer.put(0, (byte) 0x00); // Length byte 1
            buffer.put(1, (byte) 0x00); // Length byte 2
            buffer.put(2, (byte) 0x00); // Length byte 3 (0 payload)
            buffer.put(3, type); // Type
            buffer.put(4, (byte) 0x00); // Flags
            buffer.put(5, (byte) ((streamId >> 24) & 0xFF));
            buffer.put(6, (byte) ((streamId >> 16) & 0xFF));
            buffer.put(7, (byte) ((streamId >> 8) & 0xFF));
            buffer.put(8, (byte) (streamId & 0xFF));
            buffer.position(0);
            return buffer;
        }

        @Test
        public void testStreamIdZero() {
            // Stream ID 0 is reserved for connection-level frames
            ByteBuffer buffer = createFrame(0x00000000, (byte) 0x04);
            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getStreamId()).isEqualTo(0);
        }

        @Test
        public void testMaxStreamId() {
            // Maximum valid stream ID: 2^31 - 1
            ByteBuffer buffer = createFrame(0x7FFFFFFF, (byte) 0x00);
            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getStreamId()).isEqualTo(0x7FFFFFFF);
        }

        @Test
        public void testStreamIdReservedBitMasked() {
            // Reserved bit (most significant bit) should be masked
            ByteBuffer buffer = createFrame(0xFFFFFFFF, (byte) 0x00);
            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getStreamId()).isEqualTo(0x7FFFFFFF); // Reserved bit masked
        }

        @Test
        public void testOddStreamIdIsClientInitiated() {
            HTTP2Stream stream = new HTTP2Stream(1, 65535);
            assertThat(stream.isClientInitiated()).isTrue();
        }

        @Test
        public void testEvenStreamIdIsServerInitiated() {
            HTTP2Stream stream = new HTTP2Stream(2, 65535);
            assertThat(stream.isClientInitiated()).isFalse();
        }
    }

    @Nested
    class FlagValidationTests {

        private ByteBuffer createFrame(byte type, byte flags, int streamId, byte[] payload) {
            ByteBuffer buffer = ByteBuffer.allocate(20 + payload.length);
            int payloadLen = payload != null ? payload.length : 0;
            buffer.put(0, (byte) ((payloadLen >> 16) & 0xFF));
            buffer.put(1, (byte) ((payloadLen >> 8) & 0xFF));
            buffer.put(2, (byte) (payloadLen & 0xFF));
            buffer.put(3, type);
            buffer.put(4, flags);
            buffer.put(5, (byte) ((streamId >> 24) & 0xFF));
            buffer.put(6, (byte) ((streamId >> 16) & 0xFF));
            buffer.put(7, (byte) ((streamId >> 8) & 0xFF));
            buffer.put(8, (byte) (streamId & 0xFF));
            if (payload != null && payload.length > 0) {
                buffer.put(9, payload);
            }
            buffer.position(0);
            buffer.limit(9 + payloadLen);
            return buffer;
        }

        @Test
        public void testAllFlagsSet() {
            byte[] payload = new byte[]{1, 2, 3, 4, 5};
            ByteBuffer buffer = createFrame((byte) 0x00, (byte) 0xFF, 1, payload);

            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getFlags()).isEqualTo((byte) 0xFF);
        }

        @Test
        public void testNoFlagsSet() {
            byte[] payload = new byte[]{1, 2, 3, 4, 5};
            ByteBuffer buffer = createFrame((byte) 0x00, (byte) 0x00, 1, payload);

            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getFlags()).isEqualTo((byte) 0x00);
        }

        @Test
        public void testEndStreamFlag() {
            byte[] payload = new byte[]{1, 2, 3, 4, 5};
            ByteBuffer buffer = createFrame((byte) 0x00, (byte) 0x01, 1, payload);

            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame.isEndStream()).isTrue();
        }

        @Test
        public void testEndHeadersFlag() {
            byte[] payload = new byte[]{1, 2, 3, 4, 5};
            ByteBuffer buffer = createFrame((byte) 0x01, (byte) 0x04, 1, payload);

            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame.isEndHeaders()).isTrue();
        }
    }

    @Nested
    class IncompleteFrameTests {

        @Test
        public void testIncompleteHeader() {
            ByteBuffer buffer = ByteBuffer.allocate(5); // Less than 9 byte header
            buffer.put((byte) 0x00);
            buffer.put((byte) 0x00);
            buffer.put((byte) 0x05); // Length: 5 bytes
            buffer.put((byte) 0x00); // Type: DATA (incomplete)
            buffer.position(0);

            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNull();
        }

        @Test
        public void testIncompletePayload() {
            ByteBuffer buffer = ByteBuffer.allocate(15);
            buffer.put((byte) 0x00);
            buffer.put((byte) 0x00);
            buffer.put((byte) 0x10); // Length: 16 bytes
            buffer.put((byte) 0x00); // Type: DATA
            buffer.put((byte) 0x00); // Flags: none
            buffer.putInt(0x00000001); // Stream ID: 1
            buffer.put(new byte[]{1, 2, 3, 4}); // Only 4 bytes, need 16
            buffer.position(0);
            buffer.limit(14);
            buffer.position(0);

            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNull();
        }

        @Test
        public void testEmptyBuffer() {
            ByteBuffer buffer = ByteBuffer.allocate(0);

            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNull();
        }
    }

    @Nested
    class FrameEncodingTests {

        @Test
        public void testEncodeDataFrameWithEmptyPayload() {
            HTTP2Frame frame = new HTTP2Frame(HTTP2Frame.FrameType.DATA, (byte) 0x00, 1, new byte[0]);

            ByteBuffer encoded = parser.encodeFrame(frame);
            assertThat(encoded).isNotNull();
            assertThat(encoded.remaining()).isEqualTo(9); // Header only
        }

        @Test
        public void testEncodeSettingsFrame() {
            byte[] settingsPayload = new byte[18];
            HTTP2Frame frame = new HTTP2Frame(HTTP2Frame.FrameType.SETTINGS, (byte) 0x00, 0, settingsPayload);

            ByteBuffer encoded = parser.encodeFrame(frame);
            assertThat(encoded).isNotNull();
            assertThat(encoded.remaining()).isEqualTo(9 + 18);
        }

        @Test
        public void testEncodeGoAwayFrame() {
            byte[] goawayPayload = new byte[8];
            HTTP2Frame frame = new HTTP2Frame(HTTP2Frame.FrameType.GOAWAY, (byte) 0x00, 0, goawayPayload);

            ByteBuffer encoded = parser.encodeFrame(frame);
            assertThat(encoded).isNotNull();
            encoded.rewind();
            byte[] data = new byte[9];
            encoded.get(data);
            assertThat(data[3]).isEqualTo((byte) 0x07); // GOAWAY type
        }

        @Test
        public void testEncodePingFrame() {
            byte[] pingPayload = new byte[8];
            HTTP2Frame frame = new HTTP2Frame(HTTP2Frame.FrameType.PING, (byte) 0x00, 0, pingPayload);

            ByteBuffer encoded = parser.encodeFrame(frame);
            assertThat(encoded).isNotNull();
            assertThat(encoded.remaining()).isEqualTo(9 + 8);
        }
    }

    @Nested
    class BufferPositionTests {

        @Test
        public void testParseDoesNotAdvancePastCompleteFrame() {
            ByteBuffer buffer = ByteBuffer.allocate(30);
            // HTTP/2 Frame: 3-byte length + 1-byte type + 1-byte flags + 4-byte stream ID + payload
            buffer.put(0, (byte) 0x00); // Length byte 1
            buffer.put(1, (byte) 0x00); // Length byte 2
            buffer.put(2, (byte) 0x05); // Length byte 3: 5 bytes payload
            buffer.put(3, (byte) 0x00); // Type: DATA
            buffer.put(4, (byte) 0x00); // Flags: none
            buffer.put(5, (byte) 0x00); // Stream ID byte 1
            buffer.put(6, (byte) 0x00); // Stream ID byte 2
            buffer.put(7, (byte) 0x00); // Stream ID byte 3
            buffer.put(8, (byte) 0x01); // Stream ID byte 4: 1
            buffer.put(9, (byte) 0x01); // Payload byte 1
            buffer.put(10, (byte) 0x02); // Payload byte 2
            buffer.put(11, (byte) 0x03); // Payload byte 3
            buffer.put(12, (byte) 0x04); // Payload byte 4
            buffer.put(13, (byte) 0x05); // Payload byte 5
            buffer.position(0);

            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(buffer.position()).isEqualTo(14); // 9 header + 5 payload
        }

        @Test
        public void testParseResetsPositionOnError() {
            ByteBuffer buffer = ByteBuffer.allocate(30);
            // Invalid frame
            buffer.put(0, (byte) 0x10);
            buffer.put(1, (byte) 0x00);
            buffer.put(2, (byte) 0x00); // Oversized length
            buffer.put(3, (byte) 0x00); // Type: DATA
            buffer.put(4, (byte) 0x00); // Flags: none
            buffer.put(5, (byte) 0x00);
            buffer.put(6, (byte) 0x00);
            buffer.put(7, (byte) 0x00);
            buffer.put(8, (byte) 0x01); // Stream ID: 1
            buffer.position(0);
            int initialPosition = buffer.position();

            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNull();
            // Position should not change on error
            assertThat(buffer.position()).isEqualTo(initialPosition);
        }
    }

    @Nested
    class SpecialDataPayloadsTests {

        @Test
        public void testFrameWithNullPayload() {
            HTTP2Frame frame = new HTTP2Frame(HTTP2Frame.FrameType.SETTINGS, (byte) 0x00, 0, (byte[]) null);

            ByteBuffer encoded = parser.encodeFrame(frame);
            assertThat(encoded).isNotNull();
            assertThat(encoded.remaining()).isEqualTo(9); // Header only
        }

        @Test
        public void testFrameWithBinaryData() {
            byte[] binaryPayload = new byte[]{(byte) 0xFF, (byte) 0xFE, (byte) 0xFD, (byte) 0xFC};
            HTTP2Frame frame = new HTTP2Frame(HTTP2Frame.FrameType.DATA, (byte) 0x00, 1, binaryPayload);

            ByteBuffer encoded = parser.encodeFrame(frame);
            assertThat(encoded).isNotNull();
            encoded.rewind();
            byte[] result = new byte[4];
            encoded.position(9);
            encoded.get(result);
            assertThat(result).isEqualTo(binaryPayload);
        }

        @Test
        public void testFrameWithMaxByteValues() {
            byte[] payload = new byte[256];
            for (int i = 0; i < 256; i++) {
                payload[i] = (byte) i;
            }
            HTTP2Frame frame = new HTTP2Frame(HTTP2Frame.FrameType.DATA, (byte) 0xFF, 0x7FFFFFFF, payload);

            ByteBuffer encoded = parser.encodeFrame(frame);
            assertThat(encoded).isNotNull();
            assertThat(encoded.remaining()).isGreaterThanOrEqualTo(9 + 256);
        }
    }

    @Nested
    class RoundTripTests {

        @Test
        public void testDataFrameRoundTrip() {
            byte[] originalPayload = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
            HTTP2Frame original = new HTTP2Frame(HTTP2Frame.FrameType.DATA, (byte) 0x00, 1, originalPayload);

            ByteBuffer encoded = parser.encodeFrame(original);
            encoded.rewind();

            HTTP2Frame decoded = parser.parseFrame(encoded);
            assertThat(decoded).isNotNull();
            assertThat(decoded.getType()).isEqualTo(HTTP2Frame.FrameType.DATA);
            assertThat(decoded.getStreamId()).isEqualTo(1);
            assertThat(decoded.getPayload()).isEqualTo(originalPayload);
        }

        @Test
        public void testHeadersFrameRoundTrip() {
            byte[] originalPayload = {1, 2, 3};
            HTTP2Frame original = new HTTP2Frame(HTTP2Frame.FrameType.HEADERS, (byte) 0x05, 1, originalPayload);

            ByteBuffer encoded = parser.encodeFrame(original);
            encoded.rewind();

            HTTP2Frame decoded = parser.parseFrame(encoded);
            assertThat(decoded).isNotNull();
            assertThat(decoded.getType()).isEqualTo(HTTP2Frame.FrameType.HEADERS);
            assertThat(decoded.getFlags()).isEqualTo((byte) 0x05);
            assertThat(decoded.isEndStream()).isTrue();
            assertThat(decoded.isEndHeaders()).isTrue();
        }

        @Test
        public void testGoAwayFrameRoundTrip() {
            byte[] originalPayload = new byte[8];
            HTTP2Frame original = new HTTP2Frame(HTTP2Frame.FrameType.GOAWAY, (byte) 0x00, 0, originalPayload);

            ByteBuffer encoded = parser.encodeFrame(original);
            encoded.rewind();

            HTTP2Frame decoded = parser.parseFrame(encoded);
            assertThat(decoded).isNotNull();
            assertThat(decoded.getType()).isEqualTo(HTTP2Frame.FrameType.GOAWAY);
            assertThat(decoded.getStreamId()).isEqualTo(0);
        }
    }
}
