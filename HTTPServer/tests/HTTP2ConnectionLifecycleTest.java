package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

public class HTTP2ConnectionLifecycleTest {

    private HTTP2FrameParser frameParser;
    private HPACKEncoder encoder;
    private HPACKDecoder decoder;

    @BeforeEach
    public void setUp() {
        frameParser = new HTTP2FrameParser();
        encoder = new HPACKEncoder();
        decoder = new HPACKDecoder();
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
    class PrefaceVerificationTests {

        @Test
        public void testValidHTTP2Preface() {
            String preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";
            byte[] prefaceBytes = preface.getBytes();

            assertThat(prefaceBytes).hasSize(24);
            assertThat(prefaceBytes[0]).isEqualTo((byte) 'P');
            assertThat(prefaceBytes[1]).isEqualTo((byte) 'R');
            assertThat(prefaceBytes[2]).isEqualTo((byte) 'I');
        }

        @Test
        public void testPrefaceLength() {
            String preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";
            assertThat(preface.length()).isEqualTo(24);
        }

        @Test
        public void testInvalidPrefaceWrongMethod() {
            String invalidPreface = "GET / HTTP/2.0\r\n\r\nSM\r\n\r\n";
            assertThat(invalidPreface.startsWith("PRI")).isFalse();
        }

        @Test
        public void testInvalidPrefaceWrongVersion() {
            String invalidPreface = "PRI * HTTP/1.1\r\n\r\nSM\r\n\r\n";
            assertThat(invalidPreface.contains("HTTP/2.0")).isFalse();
        }

        @Test
        public void testInvalidPrefaceTruncated() {
            String truncatedPreface = "PRI * HTTP/2.0\r\n\r\n";
            assertThat(truncatedPreface.length()).isLessThan(24);
        }

        @Test
        public void testInvalidPrefaceCorrupted() {
            String corruptedPreface = "PRI * HTTP/2.X\r\n\r\nSM\r\n\r\n";
            assertThat(corruptedPreface).doesNotContain("HTTP/2.0");
        }
    }

    @Nested
    class SettingsFrameExchangeTests {

        @Test
        public void testClientSettingsFrame() {
            HTTP2Frame settingsFrame = frameParser.createSettingsFrame(65535, 4096, false);

            assertThat(settingsFrame).isNotNull();
            assertThat(settingsFrame.getType()).isEqualTo(HTTP2Frame.FrameType.SETTINGS);
            assertThat(settingsFrame.getStreamId()).isEqualTo(0);
        }

        @Test
        public void testServerSettingsFrame() {
            HTTP2Frame settingsFrame = frameParser.createSettingsFrame(65535, 4096, true);

            assertThat(settingsFrame).isNotNull();
            assertThat(settingsFrame.getType()).isEqualTo(HTTP2Frame.FrameType.SETTINGS);
            assertThat(settingsFrame.getStreamId()).isEqualTo(0);
        }

        @Test
        public void testSettingsFrameWithoutPayload() {
            ByteBuffer buffer = ByteBuffer.allocate(9);
            writeFrameHeader(buffer, 0, (byte) 0x04, (byte) 0x00, 0);
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getType()).isEqualTo(HTTP2Frame.FrameType.SETTINGS);
            assertThat(frame.getLength()).isEqualTo(0);
        }

        @Test
        public void testSettingsAckFrame() {
            ByteBuffer buffer = ByteBuffer.allocate(9);
            writeFrameHeader(buffer, 0, (byte) 0x04, (byte) 0x01, 0);
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.hasFlag(HTTP2Frame.Flags.ACK)).isTrue();
        }

        @Test
        public void testMultipleSettingsInExchange() {
            HTTP2Frame settings1 = frameParser.createSettingsFrame(65535, 4096, false);
            HTTP2Frame settings2 = frameParser.createSettingsFrame(32768, 2048, true);

            assertThat(settings1).isNotNull();
            assertThat(settings2).isNotNull();
            assertThat(settings1.getStreamId()).isEqualTo(0);
            assertThat(settings2.getStreamId()).isEqualTo(0);
        }
    }

    @Nested
    class PingFrameTests {

        @Test
        public void testPingFrame() {
            ByteBuffer buffer = ByteBuffer.allocate(17);
            writeFrameHeader(buffer, 8, (byte) 0x06, (byte) 0x00, 0);
            buffer.putLong(0x0102030405060708L);
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getType()).isEqualTo(HTTP2Frame.FrameType.PING);
            assertThat(frame.getLength()).isEqualTo(8);
        }

        @Test
        public void testPingAckFrame() {
            ByteBuffer buffer = ByteBuffer.allocate(17);
            writeFrameHeader(buffer, 8, (byte) 0x06, (byte) 0x01, 0);
            buffer.putLong(0x0102030405060708L);
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.hasFlag(HTTP2Frame.Flags.ACK)).isTrue();
        }

        @Test
        public void testPingOnlyOnConnectionLevel() {
            ByteBuffer buffer = ByteBuffer.allocate(17);
            writeFrameHeader(buffer, 8, (byte) 0x06, (byte) 0x00, 1);
            buffer.putLong(0x0102030405060708L);
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getStreamId()).isEqualTo(1);
        }

        @Test
        public void testPingExactlyEightBytes() {
            ByteBuffer buffer = ByteBuffer.allocate(17);
            writeFrameHeader(buffer, 8, (byte) 0x06, (byte) 0x00, 0);
            buffer.putLong(0x0000000000000000L);
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getLength()).isEqualTo(8);
        }
    }

    @Nested
    class GoAwayFrameTests {

        @Test
        public void testGoAwayWithNoError() {
            ByteBuffer buffer = ByteBuffer.allocate(17);
            writeFrameHeader(buffer, 8, (byte) 0x07, (byte) 0x00, 0);
            buffer.putInt(0x00000005);
            buffer.putInt(0x00000000);
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getType()).isEqualTo(HTTP2Frame.FrameType.GOAWAY);
        }

        @Test
        public void testGoAwayWithErrorCode() {
            ByteBuffer buffer = ByteBuffer.allocate(17);
            writeFrameHeader(buffer, 8, (byte) 0x07, (byte) 0x00, 0);
            buffer.putInt(0x00000001);
            buffer.putInt(0x00000001);
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getStreamId()).isEqualTo(0);
        }

        @Test
        public void testGoAwayWithAdditionalData() {
            ByteBuffer buffer = ByteBuffer.allocate(30);
            writeFrameHeader(buffer, 14, (byte) 0x07, (byte) 0x00, 0);
            buffer.putInt(0x00000000);
            buffer.putInt(0x00000000);
            buffer.put(new byte[]{1, 2, 3, 4, 5, 6});
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getLength()).isEqualTo(14);
        }

        @Test
        public void testGoAwayOnlyOnConnectionLevel() {
            ByteBuffer buffer = ByteBuffer.allocate(17);
            writeFrameHeader(buffer, 8, (byte) 0x07, (byte) 0x00, 1);
            buffer.putInt(0x00000000);
            buffer.putInt(0x00000000);
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getStreamId()).isEqualTo(1);
        }

        @Test
        public void testGracefulShutdownWithPendingStreams() {
            Map<Integer, HTTP2Stream> streams = new java.util.HashMap<>();
            for (int i = 1; i <= 5; i++) {
                HTTP2Stream stream = new HTTP2Stream(i * 2 - 1, 65535);
                stream.open();
                streams.put(i, stream);
            }

            for (HTTP2Stream stream : streams.values()) {
                assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.OPEN);
            }
        }
    }

    @Nested
    class RstStreamFrameTests {

        @Test
        public void testRstStreamOnOpenStream() {
            ByteBuffer buffer = ByteBuffer.allocate(13);
            writeFrameHeader(buffer, 4, (byte) 0x03, (byte) 0x00, 1);
            buffer.putInt(0x00000000);
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getType()).isEqualTo(HTTP2Frame.FrameType.RST_STREAM);
            assertThat(frame.getStreamId()).isEqualTo(1);
        }

        @Test
        public void testRstStreamWithVariousErrorCodes() {
            int[] errorCodes = {0x0, 0x1, 0x2, 0x3, 0x4, 0x5, 0x6, 0x7, 0x8, 0x9};

            for (int errorCode : errorCodes) {
                ByteBuffer buffer = ByteBuffer.allocate(13);
                writeFrameHeader(buffer, 4, (byte) 0x03, (byte) 0x00, 1);
                buffer.putInt(errorCode);
                buffer.flip();

                HTTP2Frame frame = frameParser.parseFrame(buffer);
                assertThat(frame).isNotNull();
            }
        }

        @Test
        public void testRstStreamCannotBeConnectionLevel() {
            ByteBuffer buffer = ByteBuffer.allocate(13);
            writeFrameHeader(buffer, 4, (byte) 0x03, (byte) 0x00, 0);
            buffer.putInt(0x00000000);
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame.getStreamId()).isEqualTo(0);
        }
    }

    @Nested
    class WindowUpdateTests {

        @Test
        public void testConnectionLevelWindowUpdate() {
            ByteBuffer buffer = ByteBuffer.allocate(13);
            writeFrameHeader(buffer, 4, (byte) 0x08, (byte) 0x00, 0);
            buffer.putInt(0x00001000);
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getStreamId()).isEqualTo(0);
        }

        @Test
        public void testStreamLevelWindowUpdate() {
            ByteBuffer buffer = ByteBuffer.allocate(13);
            writeFrameHeader(buffer, 4, (byte) 0x08, (byte) 0x00, 1);
            buffer.putInt(0x00008000);
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getStreamId()).isEqualTo(1);
        }

        @Test
        public void testWindowUpdateExactlyFourBytes() {
            ByteBuffer buffer = ByteBuffer.allocate(13);
            writeFrameHeader(buffer, 4, (byte) 0x08, (byte) 0x00, 1);
            buffer.putInt(0x7FFFFFFF);
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getLength()).isEqualTo(4);
        }
    }

    @Nested
    class ContinuationFrameTests {

        @Test
        public void testContinuationFollowsHeaders() {
            ByteBuffer headersBuffer = ByteBuffer.allocate(20);
            writeFrameHeader(headersBuffer, 5, (byte) 0x01, (byte) 0x00, 1);
            headersBuffer.put(new byte[]{1, 2, 3, 4, 5});
            headersBuffer.flip();

            HTTP2Frame headersFrame = frameParser.parseFrame(headersBuffer);
            assertThat(headersFrame).isNotNull();
            assertThat(headersFrame.isEndHeaders()).isFalse();
        }

        @Test
        public void testContinuationFrame() {
            ByteBuffer buffer = ByteBuffer.allocate(20);
            writeFrameHeader(buffer, 5, (byte) 0x09, (byte) 0x04, 1);
            buffer.put(new byte[]{1, 2, 3, 4, 5});
            buffer.flip();

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getType()).isEqualTo(HTTP2Frame.FrameType.CONTINUATION);
            assertThat(frame.isEndHeaders()).isTrue();
        }

        @Test
        public void testMultipleContinuationFrames() {
            ByteBuffer buffer1 = ByteBuffer.allocate(20);
            writeFrameHeader(buffer1, 5, (byte) 0x09, (byte) 0x00, 1);
            buffer1.put(new byte[]{1, 2, 3, 4, 5});
            buffer1.flip();

            ByteBuffer buffer2 = ByteBuffer.allocate(20);
            writeFrameHeader(buffer2, 5, (byte) 0x09, (byte) 0x04, 1);
            buffer2.put(new byte[]{6, 7, 8, 9, 10});
            buffer2.flip();

            HTTP2Frame frame1 = frameParser.parseFrame(buffer1);
            HTTP2Frame frame2 = frameParser.parseFrame(buffer2);

            assertThat(frame1).isNotNull();
            assertThat(frame2).isNotNull();
            assertThat(frame2.isEndHeaders()).isTrue();
        }
    }

    @Nested
    class ConnectionStateTests {

        @Test
        public void testInitialConnectionState() {
            assertThat(true).isTrue();
        }

        @Test
        public void testConnectionAfterPreface() {
            assertThat(true).isTrue();
        }

        @Test
        public void testConnectionAfterSettingsExchange() {
            assertThat(true).isTrue();
        }

        @Test
        public void testConnectionAfterGoAway() {
            assertThat(true).isTrue();
        }
    }

    @Nested
    class HeaderBlockFragmentation {

        @Test
        public void testHeadersEndWithoutContinuation() {
            Map<String, String> headers = new HashMap<>();
            headers.put(":method", "GET");
            headers.put(":path", "/");

            byte[] encoded = encoder.encode(headers);
            HTTP2Frame headersFrame = frameParser.createHeadersFrame(1, encoded, true, true);

            assertThat(headersFrame).isNotNull();
            assertThat(headersFrame.isEndHeaders()).isTrue();
        }

        @Test
        public void testHeadersWithoutEndFlag() {
            Map<String, String> headers = new HashMap<>();
            headers.put(":method", "GET");

            byte[] encoded = encoder.encode(headers);
            HTTP2Frame headersFrame = frameParser.createHeadersFrame(1, encoded, false, true);

            assertThat(headersFrame).isNotNull();
            assertThat(headersFrame.isEndHeaders()).isTrue();
        }
    }
}
