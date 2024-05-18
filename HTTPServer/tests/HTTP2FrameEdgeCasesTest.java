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
            buffer.put((byte) 0xFF); // 255
            buffer.put((byte) 0xFF); // 255
            buffer.put((byte) 0xFF); // 255 = 16,777,215 bytes
            buffer.put((byte) 0x00); // Type: DATA
            buffer.put((byte) 0x00); // Flags: none
            buffer.putInt(0x00000001); // Stream ID: 1
            buffer.position(0);

            // Parse should accept this size but return null due to missing payload
            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNull(); // Incomplete payload
        }

        @Test
        public void testOversizedFrame() {
            // Payload larger than max (16 MB + 1)
            ByteBuffer buffer = ByteBuffer.allocate(9);
            int oversizePayload = 0x1000000; // 16,777,216 (exceeds max)
            buffer.put((byte) ((oversizePayload >> 16) & 0xFF));
            buffer.put((byte) ((oversizePayload >> 8) & 0xFF));
            buffer.put((byte) (oversizePayload & 0xFF));
            buffer.put((byte) 0x00); // Type: DATA
            buffer.put((byte) 0x00); // Flags: none
            buffer.putInt(0x00000001); // Stream ID: 1
            buffer.position(0);

            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNull(); // Should reject oversized frame
        }

        @Test
        public void testZeroLengthFrame() {
            ByteBuffer buffer = ByteBuffer.allocate(9);
            buffer.putInt(0x000000); // Length: 0 bytes
            buffer.put(4, (byte) 0x04); // Type: SETTINGS
            buffer.put(5, (byte) 0x00); // Flags: none
            buffer.putInt(6, 0x00000000); // Stream ID: 0
            buffer.limit(9);

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

        @Test
        public void testInvalidFrameType() {
            ByteBuffer buffer = ByteBuffer.allocate(9);
            buffer.putInt(0x000000); // Length: 0
            buffer.put(4, (byte) 0xFF); // Type: Invalid (255)
            buffer.put(5, (byte) 0x00); // Flags: none
            buffer.putInt(6, 0x00000001); // Stream ID: 1
            buffer.position(0);

            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNull(); // Should reject unknown frame type
        }

        @Test
        public void testInvalidFrameTypeWithPayload() {
            ByteBuffer buffer = ByteBuffer.allocate(20);
            buffer.putInt(0x000005); // Length: 5 bytes
            buffer.put(4, (byte) 0xAB); // Type: Invalid (171)
            buffer.put(5, (byte) 0x00); // Flags: none
            buffer.putInt(6, 0x00000001); // Stream ID: 1
            buffer.put(10, new byte[]{1, 2, 3, 4, 5});
            buffer.limit(15);
            buffer.position(0);

            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNull();
        }
    }

    @Nested
    class StreamIdValidationTests {

        @Test
        public void testStreamIdZero() {
            // Stream ID 0 is reserved for connection-level frames
            ByteBuffer buffer = ByteBuffer.allocate(9);
            buffer.putInt(0x000000); // Length: 0
            buffer.put(4, (byte) 0x04); // Type: SETTINGS
            buffer.put(5, (byte) 0x00); // Flags: none
            buffer.putInt(6, 0x00000000); // Stream ID: 0 (valid for SETTINGS)
            buffer.position(0);

            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getStreamId()).isEqualTo(0);
        }

        @Test
        public void testMaxStreamId() {
            // Maximum valid stream ID: 2^31 - 1
            ByteBuffer buffer = ByteBuffer.allocate(9);
            buffer.putInt(0x000000); // Length: 0
            buffer.put(4, (byte) 0x00); // Type: DATA
            buffer.put(5, (byte) 0x00); // Flags: none
            buffer.putInt(6, 0x7FFFFFFF); // Stream ID: 2147483647
            buffer.position(0);

            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getStreamId()).isEqualTo(0x7FFFFFFF);
        }

        @Test
        public void testStreamIdReservedBitMasked() {
            // Reserved bit (most significant bit) should be masked
            ByteBuffer buffer = ByteBuffer.allocate(9);
            buffer.putInt(0x000000); // Length: 0
            buffer.put(4, (byte) 0x00); // Type: DATA
            buffer.put(5, (byte) 0x00); // Flags: none
            buffer.putInt(6, 0xFFFFFFFF); // Stream ID with reserved bit set
            buffer.position(0);

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

        @Test
        public void testAllFlagsSet() {
            ByteBuffer buffer = ByteBuffer.allocate(20);
            buffer.putInt(0x000005); // Length: 5 bytes
            buffer.put(4, (byte) 0x00); // Type: DATA
            buffer.put(5, (byte) 0xFF); // Flags: All bits set
            buffer.putInt(6, 0x00000001); // Stream ID: 1
            buffer.put(10, new byte[]{1, 2, 3, 4, 5});
            buffer.limit(15);
            buffer.position(0);

            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getFlags()).isEqualTo((byte) 0xFF);
        }

        @Test
        public void testNoFlagsSet() {
            ByteBuffer buffer = ByteBuffer.allocate(20);
            buffer.putInt(0x000005); // Length: 5 bytes
            buffer.put(4, (byte) 0x00); // Type: DATA
            buffer.put(5, (byte) 0x00); // Flags: None
            buffer.putInt(6, 0x00000001); // Stream ID: 1
            buffer.put(10, new byte[]{1, 2, 3, 4, 5});
            buffer.limit(15);
            buffer.position(0);

            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getFlags()).isEqualTo((byte) 0x00);
        }

        @Test
        public void testEndStreamFlag() {
            ByteBuffer buffer = ByteBuffer.allocate(20);
            buffer.putInt(0x000005); // Length: 5 bytes
            buffer.put(4, (byte) 0x00); // Type: DATA
            buffer.put(5, (byte) 0x01); // Flags: END_STREAM
            buffer.putInt(6, 0x00000001); // Stream ID: 1
            buffer.put(10, new byte[]{1, 2, 3, 4, 5});
            buffer.limit(15);
            buffer.position(0);

            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame.isEndStream()).isTrue();
        }

        @Test
        public void testEndHeadersFlag() {
            ByteBuffer buffer = ByteBuffer.allocate(20);
            buffer.putInt(0x000005); // Length: 5 bytes
            buffer.put(4, (byte) 0x01); // Type: HEADERS
            buffer.put(5, (byte) 0x04); // Flags: END_HEADERS
            buffer.putInt(6, 0x00000001); // Stream ID: 1
            buffer.put(10, new byte[]{1, 2, 3, 4, 5});
            buffer.limit(15);
            buffer.position(0);

            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame.isEndHeaders()).isTrue();
        }
    }

    @Nested
    class IncompleteFrameTests {

        @Test
        public void testIncompleteHeader() {
            ByteBuffer buffer = ByteBuffer.allocate(5); // Less than 9 byte header
            buffer.putInt(0x000005); // Length: 5 bytes
            buffer.put(4, (byte) 0x00); // Type: DATA (incomplete)
            buffer.position(0);

            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNull();
        }

        @Test
        public void testIncompletePayload() {
            ByteBuffer buffer = ByteBuffer.allocate(15);
            buffer.putInt(0x000010); // Length: 16 bytes
            buffer.put(4, (byte) 0x00); // Type: DATA
            buffer.put(5, (byte) 0x00); // Flags: none
            buffer.putInt(6, 0x00000001); // Stream ID: 1
            buffer.put(10, new byte[]{1, 2, 3, 4}); // Only 4 bytes, need 16
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
            // Frame 1
            buffer.putInt(0, 0x000005); // Length: 5 bytes
            buffer.put(4, (byte) 0x00); // Type: DATA
            buffer.put(5, (byte) 0x00); // Flags: none
            buffer.putInt(6, 0x00000001); // Stream ID: 1
            buffer.put(10, new byte[]{1, 2, 3, 4, 5});
            buffer.position(0);

            HTTP2Frame frame = parser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(buffer.position()).isEqualTo(15); // Advanced by frame size
        }

        @Test
        public void testParseResetsPositionOnError() {
            ByteBuffer buffer = ByteBuffer.allocate(30);
            // Invalid frame
            buffer.putInt(0, 0x1000000); // Oversized length
            buffer.put(4, (byte) 0x00); // Type: DATA
            buffer.put(5, (byte) 0x00); // Flags: none
            buffer.putInt(6, 0x00000001); // Stream ID: 1
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
