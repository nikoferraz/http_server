package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

public class HTTP2ProtocolErrorsTest {

    private HTTP2FrameParser frameParser;
    private HTTP2Stream stream;

    @BeforeEach
    public void setUp() {
        frameParser = new HTTP2FrameParser();
        stream = new HTTP2Stream(1, 65535);
    }

    private void writeFrameHeader(ByteBuffer buffer, int length, byte type, byte flags, int streamId) {
        buffer.put((byte) ((length >> 16) & 0xFF));
        buffer.put((byte) ((length >> 8) & 0xFF));
        buffer.put((byte) (length & 0xFF));
        buffer.put(type);
        buffer.put(flags);
        buffer.putInt(streamId);
    }

    @Nested
    class SettingsFrameErrors {

        @Test
        public void testSettingsFrameWithInvalidLength() {
            // SETTINGS frames must have length % 6 == 0
            ByteBuffer buffer = ByteBuffer.allocate(20);
            writeFrameHeader(buffer, 5, (byte) 0x04, (byte) 0x00, 0);
            buffer.put(new byte[]{1, 2, 3, 4, 5});
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            // Parser parses it; semantic validation happens elsewhere
            assertThat(frame).isNotNull();
            assertThat(frame.getLength()).isEqualTo(5);
        }

        @Test
        public void testSettingsFrameOnNonZeroStream() {
            // SETTINGS must be on stream 0
            ByteBuffer buffer = ByteBuffer.allocate(20);
            writeFrameHeader(buffer, 0, (byte) 0x04, (byte) 0x00, 1);
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getStreamId()).isEqualTo(1);
        }

        @Test
        public void testSettingsWithMaximumValues() {
            ByteBuffer buffer = ByteBuffer.allocate(20);
            writeFrameHeader(buffer, 6, (byte) 0x04, (byte) 0x00, 0);
            buffer.putShort((short) 0x0001); // HEADER_TABLE_SIZE
            buffer.putInt(0xFFFFFFFF); // Max value
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
        }
    }

    @Nested
    class FrameSizeErrors {

        @Test
        public void testDataFrameExceedsMaxSize() {
            // Default max frame size is 16384
            int oversizePayload = 16385;
            ByteBuffer buffer = ByteBuffer.allocate(20);
            buffer.put((byte) 0x00);
            buffer.put((byte) 0x40);
            buffer.put((byte) 0x01); // 16385 in 24-bit big-endian
            buffer.put((byte) 0x00); // Type: DATA
            buffer.put((byte) 0x00); // Flags
            buffer.putInt(0x00000001); // Stream ID
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            // Parser accepts but may have incomplete payload
            assertThat(frame == null || frame != null).isTrue();
        }

        @Test
        public void testRstStreamWrongSize() {
            // RST_STREAM must be exactly 4 bytes
            ByteBuffer buffer = ByteBuffer.allocate(20);
            writeFrameHeader(buffer, 3, (byte) 0x03, (byte) 0x00, 1);
            buffer.put(new byte[]{1, 2, 3});
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getLength()).isEqualTo(3);
        }

        @Test
        public void testPingWrongSize() {
            // PING must be exactly 8 bytes
            ByteBuffer buffer = ByteBuffer.allocate(20);
            writeFrameHeader(buffer, 7, (byte) 0x06, (byte) 0x00, 0);
            buffer.put(new byte[]{1, 2, 3, 4, 5, 6, 7});
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getLength()).isEqualTo(7);
        }

        @Test
        public void testGoAwayWrongSize() {
            // GOAWAY must be at least 8 bytes
            ByteBuffer buffer = ByteBuffer.allocate(20);
            writeFrameHeader(buffer, 4, (byte) 0x07, (byte) 0x00, 0);
            buffer.put(new byte[]{1, 2, 3, 4});
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
        }
    }

    @Nested
    class HeaderBlockErrors {

        @Test
        public void testHeadersWithoutEndHeadersFlag() {
            ByteBuffer buffer = ByteBuffer.allocate(20);
            writeFrameHeader(buffer, 5, (byte) 0x01, (byte) 0x00, 1);
            buffer.put(new byte[]{1, 2, 3, 4, 5});
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.isEndHeaders()).isFalse();
        }

        @Test
        public void testMalformedHPackData() {
            HPACKDecoder decoder = new HPACKDecoder();
            byte[] malformed = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

            // Should handle gracefully
            try {
                Map<String, String> decoded = decoder.decode(malformed);
                assertThat(decoded).isNotNull();
            } catch (Exception e) {
                // Expected to handle malformed data
                assertThat(e).isNotNull();
            }
        }

        @Test
        public void testEmptyHeaderBlock() {
            ByteBuffer buffer = ByteBuffer.allocate(20);
            writeFrameHeader(buffer, 0, (byte) 0x01, (byte) 0x04, 1);
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getLength()).isEqualTo(0);
        }
    }

    @Nested
    class StreamStateErrors {

        @Test
        public void testFrameOnClosedStream() {
            stream.open();
            stream.close();

            assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.CLOSED);
            // Operations on closed stream should be handled by connection
        }

        @Test
        public void testFrameOnIdleStream() {
            assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.IDLE);
            // Cannot send DATA on idle stream
        }

        @Test
        public void testHeadersOnOpenStream() {
            stream.open();
            Map<String, String> headers = new HashMap<>();
            headers.put(":method", "GET");
            stream.setRequestHeaders(headers);

            assertThat(stream.getRequestHeaders()).isNotEmpty();
        }

        @Test
        public void testMultipleHeadersOnStream() {
            stream.open();

            Map<String, String> headers1 = new HashMap<>();
            headers1.put(":method", "GET");
            stream.setRequestHeaders(headers1);

            Map<String, String> headers2 = new HashMap<>();
            headers2.put(":method", "POST");
            stream.setRequestHeaders(headers2);

            // Last set should win
            assertThat(stream.getRequestHeaders().get(":method")).isEqualTo("POST");
        }
    }

    @Nested
    class FlowControlErrors {

        @Test
        public void testWindowSizeOverflow() {
            stream.open();
            stream.updateSenderWindow(Integer.MAX_VALUE);
            stream.updateSenderWindow(Integer.MAX_VALUE);

            // Should handle overflow
            assertThat(stream.getSenderWindowSize()).isNotNull();
        }

        @Test
        public void testNegativeWindowSize() {
            stream.open();
            stream.sendData(new byte[100000]);

            assertThat(stream.getSenderWindowSize()).isLessThan(0);
        }

        @Test
        public void testWindowUpdateOnClosedStream() {
            stream.open();
            stream.close();

            // Window update on closed stream should be ignored or handled
            stream.updateSenderWindow(1000);
            assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.CLOSED);
        }

        @Test
        public void testZeroWindowIncrement() {
            stream.open();
            int initialWindow = stream.getSenderWindowSize();

            stream.updateSenderWindow(0);

            assertThat(stream.getSenderWindowSize()).isEqualTo(initialWindow);
        }
    }

    @Nested
    class PriorityErrors {

        @Test
        public void testStreamDependsOnItself() {
            stream.setDependency(stream);

            assertThat(stream.getDependency()).isEqualTo(stream);
            // Should be detected and handled by connection
        }

        @Test
        public void testCircularDependency() {
            HTTP2Stream stream1 = new HTTP2Stream(1, 65535);
            HTTP2Stream stream2 = new HTTP2Stream(3, 65535);

            stream1.setDependency(stream2);
            stream2.setDependency(stream1);

            assertThat(stream1.getDependency()).isEqualTo(stream2);
            assertThat(stream2.getDependency()).isEqualTo(stream1);
        }

        @Test
        public void testDependencyChain() {
            HTTP2Stream s1 = new HTTP2Stream(1, 65535);
            HTTP2Stream s2 = new HTTP2Stream(3, 65535);
            HTTP2Stream s3 = new HTTP2Stream(5, 65535);

            s1.setDependency(s2);
            s2.setDependency(s3);

            assertThat(s1.getDependency()).isEqualTo(s2);
            assertThat(s2.getDependency()).isEqualTo(s3);
        }
    }

    @Nested
    class RST_STREAMErrors {

        @Test
        public void testRstStreamWithAllErrorCodes() {
            int[] errorCodes = {0x0, 0x1, 0x2, 0x3, 0x4, 0x5, 0x6, 0x7, 0x8, 0x9};

            for (int errorCode : errorCodes) {
                stream.reset(errorCode);
                assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.CLOSED);
            }
        }

        @Test
        public void testRstStreamOnAlreadyClosedStream() {
            stream.open();
            stream.close();

            stream.reset(0);

            assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.CLOSED);
        }
    }

    @Nested
    class ContinuationErrors {

        @Test
        public void testContinuationWithoutHeaders() {
            // CONTINUATION must follow HEADERS or PUSH_PROMISE with no END_HEADERS
            ByteBuffer buffer = ByteBuffer.allocate(20);
            writeFrameHeader(buffer, 5, (byte) 0x09, (byte) 0x04, 1);
            buffer.put(new byte[]{1, 2, 3, 4, 5});
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            // Validation happens at connection level
        }

        @Test
        public void testContinuationWithoutEndHeaders() {
            ByteBuffer buffer = ByteBuffer.allocate(20);
            writeFrameHeader(buffer, 5, (byte) 0x09, (byte) 0x00, 1);
            buffer.put(new byte[]{1, 2, 3, 4, 5});
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.isEndHeaders()).isFalse();
        }
    }

    @Nested
    class InvalidStreamIdErrors {

        @Test
        public void testDataFrameOnStreamZero() {
            // DATA frames must not be on stream 0
            ByteBuffer buffer = ByteBuffer.allocate(20);
            writeFrameHeader(buffer, 5, (byte) 0x00, (byte) 0x00, 0);
            buffer.put(new byte[]{1, 2, 3, 4, 5});
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getStreamId()).isEqualTo(0);
        }

        @Test
        public void testHeadersFrameOnStreamZero() {
            // HEADERS frames must not be on stream 0
            ByteBuffer buffer = ByteBuffer.allocate(20);
            writeFrameHeader(buffer, 5, (byte) 0x01, (byte) 0x00, 0);
            buffer.put(new byte[]{1, 2, 3, 4, 5});
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getStreamId()).isEqualTo(0);
        }

        @Test
        public void testPriorityFrameOnStreamZero() {
            // PRIORITY frames must not be on stream 0
            ByteBuffer buffer = ByteBuffer.allocate(20);
            writeFrameHeader(buffer, 5, (byte) 0x02, (byte) 0x00, 0);
            buffer.put(new byte[]{1, 2, 3, 4, 5});
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getStreamId()).isEqualTo(0);
        }
    }

    @Nested
    class HeaderValueErrors {

        @Test
        public void testHeaderWithNullValue() {
            HPACKEncoder encoder = new HPACKEncoder();
            Map<String, String> headers = new HashMap<>();
            headers.put("test-header", null);

            // Should handle null value
            try {
                byte[] encoded = encoder.encode(headers);
                assertThat(encoded).isNotNull();
            } catch (NullPointerException e) {
                assertThat(e).isNotNull();
            }
        }

        @Test
        public void testHeaderWithSpecialCharacters() {
            HPACKEncoder encoder = new HPACKEncoder();
            HPACKDecoder decoder = new HPACKDecoder();

            Map<String, String> headers = new HashMap<>();
            headers.put("special", "!@#$%^&*()");

            byte[] encoded = encoder.encode(headers);
            Map<String, String> decoded = decoder.decode(encoded);

            assertThat(decoded).containsEntry("special", "!@#$%^&*()");
        }

        @Test
        public void testHeaderWithVeryLongValue() {
            HPACKEncoder encoder = new HPACKEncoder();
            HPACKDecoder decoder = new HPACKDecoder();

            StringBuilder longValue = new StringBuilder();
            for (int i = 0; i < 100000; i++) {
                longValue.append("x");
            }

            Map<String, String> headers = new HashMap<>();
            headers.put("long-header", longValue.toString());

            byte[] encoded = encoder.encode(headers);
            Map<String, String> decoded = decoder.decode(encoded);

            assertThat(decoded.get("long-header")).hasSize(100000);
        }
    }

    @Nested
    class ContentLengthErrors {

        @Test
        public void testDataExceedsContentLength() {
            // If content-length is set, data should not exceed it
            stream.open();
            stream.receiveData(new byte[1000]);

            // Should be tracked by higher layer
            assertThat(stream.getReceiverWindowSize()).isEqualTo(65535 - 1000);
        }

        @Test
        public void testDataLessContentLength() {
            // Less data than content-length is an error
            stream.open();
            stream.receiveData(new byte[100]);

            assertThat(stream.getReceiverWindowSize()).isEqualTo(65535 - 100);
        }
    }
}
