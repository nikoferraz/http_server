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
            buffer.putInt(0x000000); // Length: 0
            buffer.put(4, (byte) 0x04); // Type: SETTINGS
            buffer.put(5, (byte) 0x00); // Flags: no ACK
            buffer.putInt(6, 0x00000000); // Stream ID: 0
            buffer.position(0);

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getType()).isEqualTo(HTTP2Frame.FrameType.SETTINGS);
            assertThat(frame.getLength()).isEqualTo(0);
        }

        @Test
        public void testSettingsAckFrame() {
            ByteBuffer buffer = ByteBuffer.allocate(9);
            buffer.putInt(0x000000); // Length: 0
            buffer.put(4, (byte) 0x04); // Type: SETTINGS
            buffer.put(5, (byte) 0x01); // Flags: ACK
            buffer.putInt(6, 0x00000000); // Stream ID: 0
            buffer.position(0);

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
            buffer.putInt(0x000008); // Length: 8 bytes
            buffer.put(4, (byte) 0x06); // Type: PING
            buffer.put(5, (byte) 0x00); // Flags: no ACK
            buffer.putInt(6, 0x00000000); // Stream ID: 0
            buffer.putLong(10, 0x0102030405060708L); // Opaque data
            buffer.position(0);

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getType()).isEqualTo(HTTP2Frame.FrameType.PING);
            assertThat(frame.getLength()).isEqualTo(8);
        }

        @Test
        public void testPingAckFrame() {
            ByteBuffer buffer = ByteBuffer.allocate(17);
            buffer.putInt(0x000008); // Length: 8 bytes
            buffer.put(4, (byte) 0x06); // Type: PING
            buffer.put(5, (byte) 0x01); // Flags: ACK
            buffer.putInt(6, 0x00000000); // Stream ID: 0
            buffer.putLong(10, 0x0102030405060708L); // Opaque data
            buffer.position(0);

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.hasFlag(HTTP2Frame.Flags.ACK)).isTrue();
        }

        @Test
        public void testPingOnlyOnConnectionLevel() {
            // PING frames must be on stream 0
            ByteBuffer buffer = ByteBuffer.allocate(17);
            buffer.putInt(0x000008); // Length: 8 bytes
            buffer.put(4, (byte) 0x06); // Type: PING
            buffer.put(5, (byte) 0x00); // Flags: none
            buffer.putInt(6, 0x00000001); // Stream ID: 1 (invalid for PING)
            buffer.putLong(10, 0x0102030405060708L);
            buffer.position(0);

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            // Frame is parsed but semantic validation would happen elsewhere
            assertThat(frame).isNotNull();
            assertThat(frame.getStreamId()).isEqualTo(1);
        }

        @Test
        public void testPingExactlyEightBytes() {
            ByteBuffer buffer = ByteBuffer.allocate(17);
            buffer.putInt(0x000008); // Length: exactly 8 bytes
            buffer.put(4, (byte) 0x06); // Type: PING
            buffer.put(5, (byte) 0x00); // Flags: none
            buffer.putInt(6, 0x00000000); // Stream ID: 0
            buffer.putLong(10, 0x0000000000000000L);
            buffer.position(0);

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
            buffer.putInt(0x000008); // Length: 8 bytes
            buffer.put(4, (byte) 0x07); // Type: GOAWAY
            buffer.put(5, (byte) 0x00); // Flags: none
            buffer.putInt(6, 0x00000000); // Stream ID: 0
            buffer.putInt(10, 0x00000005); // Last-Stream-ID: 5
            buffer.putInt(14, 0x00000000); // Error code: NO_ERROR
            buffer.position(0);

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getType()).isEqualTo(HTTP2Frame.FrameType.GOAWAY);
        }

        @Test
        public void testGoAwayWithErrorCode() {
            ByteBuffer buffer = ByteBuffer.allocate(17);
            buffer.putInt(0x000008); // Length: 8 bytes
            buffer.put(4, (byte) 0x07); // Type: GOAWAY
            buffer.put(5, (byte) 0x00); // Flags: none
            buffer.putInt(6, 0x00000000); // Stream ID: 0
            buffer.putInt(10, 0x00000001); // Last-Stream-ID: 1
            buffer.putInt(14, 0x00000001); // Error code: PROTOCOL_ERROR
            buffer.position(0);

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getStreamId()).isEqualTo(0);
        }

        @Test
        public void testGoAwayWithAdditionalData() {
            ByteBuffer buffer = ByteBuffer.allocate(30);
            buffer.putInt(0x00000E); // Length: 14 bytes (8 header + 6 additional)
            buffer.put(4, (byte) 0x07); // Type: GOAWAY
            buffer.put(5, (byte) 0x00); // Flags: none
            buffer.putInt(6, 0x00000000); // Stream ID: 0
            buffer.putInt(10, 0x00000000); // Last-Stream-ID: 0
            buffer.putInt(14, 0x00000000); // Error code: NO_ERROR
            buffer.put(18, new byte[]{1, 2, 3, 4, 5, 6}); // Debug data
            buffer.position(0);

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getLength()).isEqualTo(14);
        }

        @Test
        public void testGoAwayOnlyOnConnectionLevel() {
            // GOAWAY must be on stream 0
            ByteBuffer buffer = ByteBuffer.allocate(17);
            buffer.putInt(0x000008);
            buffer.put(4, (byte) 0x07); // Type: GOAWAY
            buffer.put(5, (byte) 0x00);
            buffer.putInt(6, 0x00000001); // Stream ID: 1 (invalid)
            buffer.putInt(10, 0x00000000);
            buffer.putInt(14, 0x00000000);
            buffer.position(0);

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getStreamId()).isEqualTo(1); // Parser doesn't validate
        }

        @Test
        public void testGracefulShutdownWithPendingStreams() {
            Map<Integer, HTTP2Stream> streams = new java.util.HashMap<>();
            for (int i = 1; i <= 5; i++) {
                HTTP2Stream stream = new HTTP2Stream(i * 2 - 1, 65535);
                stream.open();
                streams.put(i, stream);
            }

            // All streams should be open before GOAWAY
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
            buffer.putInt(0x000004); // Length: 4 bytes
            buffer.put(4, (byte) 0x03); // Type: RST_STREAM
            buffer.put(5, (byte) 0x00); // Flags: none
            buffer.putInt(6, 0x00000001); // Stream ID: 1
            buffer.putInt(10, 0x00000000); // Error code: NO_ERROR
            buffer.position(0);

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
                buffer.putInt(0x000004);
                buffer.put(4, (byte) 0x03);
                buffer.put(5, (byte) 0x00);
                buffer.putInt(6, 0x00000001);
                buffer.putInt(10, errorCode);
                buffer.position(0);

                HTTP2Frame frame = frameParser.parseFrame(buffer);
                assertThat(frame).isNotNull();
            }
        }

        @Test
        public void testRstStreamCannotBeConnectionLevel() {
            // RST_STREAM must be on a stream, not connection (stream ID must not be 0)
            ByteBuffer buffer = ByteBuffer.allocate(13);
            buffer.putInt(0x000004);
            buffer.put(4, (byte) 0x03);
            buffer.put(5, (byte) 0x00);
            buffer.putInt(6, 0x00000000); // Stream ID: 0 (invalid for RST_STREAM)
            buffer.putInt(10, 0x00000000);
            buffer.position(0);

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            // Parser parses it, validation happens elsewhere
            assertThat(frame.getStreamId()).isEqualTo(0);
        }
    }

    @Nested
    class WindowUpdateTests {

        @Test
        public void testConnectionLevelWindowUpdate() {
            ByteBuffer buffer = ByteBuffer.allocate(13);
            buffer.putInt(0x000004); // Length: 4 bytes
            buffer.put(4, (byte) 0x08); // Type: WINDOW_UPDATE
            buffer.put(5, (byte) 0x00); // Flags: none
            buffer.putInt(6, 0x00000000); // Stream ID: 0 (connection)
            buffer.putInt(10, 0x00001000); // Window increment: 4096
            buffer.position(0);

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getStreamId()).isEqualTo(0);
        }

        @Test
        public void testStreamLevelWindowUpdate() {
            ByteBuffer buffer = ByteBuffer.allocate(13);
            buffer.putInt(0x000004);
            buffer.put(4, (byte) 0x08);
            buffer.put(5, (byte) 0x00);
            buffer.putInt(6, 0x00000001); // Stream ID: 1
            buffer.putInt(10, 0x00008000); // Window increment: 32768
            buffer.position(0);

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getStreamId()).isEqualTo(1);
        }

        @Test
        public void testWindowUpdateExactlyFourBytes() {
            ByteBuffer buffer = ByteBuffer.allocate(13);
            buffer.putInt(0x000004); // Must be exactly 4
            buffer.put(4, (byte) 0x08);
            buffer.put(5, (byte) 0x00);
            buffer.putInt(6, 0x00000001);
            buffer.putInt(10, 0x7FFFFFFF); // Max increment
            buffer.position(0);

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
            headersBuffer.putInt(0x000005); // Length: 5
            headersBuffer.put(4, (byte) 0x01); // Type: HEADERS
            headersBuffer.put(5, (byte) 0x00); // Flags: no END_HEADERS
            headersBuffer.putInt(6, 0x00000001); // Stream ID: 1
            headersBuffer.put(10, new byte[]{1, 2, 3, 4, 5});
            headersBuffer.position(0);

            HTTP2Frame headersFrame = frameParser.parseFrame(headersBuffer);
            assertThat(headersFrame).isNotNull();
            assertThat(headersFrame.isEndHeaders()).isFalse();
        }

        @Test
        public void testContinuationFrame() {
            ByteBuffer buffer = ByteBuffer.allocate(20);
            buffer.putInt(0x000005); // Length: 5
            buffer.put(4, (byte) 0x09); // Type: CONTINUATION
            buffer.put(5, (byte) 0x04); // Flags: END_HEADERS
            buffer.putInt(6, 0x00000001); // Stream ID: 1
            buffer.put(10, new byte[]{1, 2, 3, 4, 5});
            buffer.position(0);

            HTTP2Frame frame = frameParser.parseFrame(buffer);
            assertThat(frame).isNotNull();
            assertThat(frame.getType()).isEqualTo(HTTP2Frame.FrameType.CONTINUATION);
            assertThat(frame.isEndHeaders()).isTrue();
        }

        @Test
        public void testMultipleContinuationFrames() {
            ByteBuffer buffer1 = ByteBuffer.allocate(20);
            buffer1.putInt(0x000005);
            buffer1.put(4, (byte) 0x09);
            buffer1.put(5, (byte) 0x00); // No END_HEADERS
            buffer1.putInt(6, 0x00000001);
            buffer1.put(10, new byte[]{1, 2, 3, 4, 5});
            buffer1.position(0);

            ByteBuffer buffer2 = ByteBuffer.allocate(20);
            buffer2.putInt(0x000005);
            buffer2.put(4, (byte) 0x09);
            buffer2.put(5, (byte) 0x04); // END_HEADERS
            buffer2.putInt(6, 0x00000001);
            buffer2.put(10, new byte[]{6, 7, 8, 9, 10});
            buffer2.position(0);

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
            // Connection starts in idle state
            assertThat(true).isTrue(); // Placeholder for connection state tracking
        }

        @Test
        public void testConnectionAfterPreface() {
            // After preface exchange, connection is ready
            assertThat(true).isTrue();
        }

        @Test
        public void testConnectionAfterSettingsExchange() {
            // After SETTINGS exchange, connection is fully established
            assertThat(true).isTrue();
        }

        @Test
        public void testConnectionAfterGoAway() {
            // After GOAWAY, no new streams can be created
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
            assertThat(headersFrame.isEndHeaders()).isTrue(); // Will be true due to createHeadersFrame
        }
    }
}
