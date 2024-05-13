package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.*;

public class HTTP2FrameParserTest {

    private HTTP2FrameParser parser;

    @BeforeEach
    public void setUp() {
        parser = new HTTP2FrameParser();
    }

    @Test
    public void testParseDataFrame() {
        ByteBuffer buffer = ByteBuffer.allocate(100);
        buffer.putInt(0, 0x000010); // Length: 16 bytes
        buffer.put(4, (byte) 0x00); // Type: DATA
        buffer.put(5, (byte) 0x00); // Flags: none
        buffer.putInt(6, 0x00000001); // Stream ID: 1
        buffer.put(10, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
        buffer.limit(26);

        HTTP2Frame frame = parser.parseFrame(buffer);

        assertThat(frame).isNotNull();
        assertThat(frame.getType()).isEqualTo(HTTP2Frame.FrameType.DATA);
        assertThat(frame.getStreamId()).isEqualTo(1);
        assertThat(frame.getLength()).isEqualTo(16);
        assertThat(frame.getPayload()).hasSize(16);
    }

    @Test
    public void testParseHeadersFrame() {
        ByteBuffer buffer = ByteBuffer.allocate(100);
        buffer.putInt(0, 0x000010); // Length: 16 bytes
        buffer.put(4, (byte) 0x01); // Type: HEADERS
        buffer.put(5, (byte) 0x05); // Flags: END_STREAM | END_HEADERS
        buffer.putInt(6, 0x00000001); // Stream ID: 1
        buffer.put(10, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
        buffer.limit(26);

        HTTP2Frame frame = parser.parseFrame(buffer);

        assertThat(frame).isNotNull();
        assertThat(frame.getType()).isEqualTo(HTTP2Frame.FrameType.HEADERS);
        assertThat(frame.getStreamId()).isEqualTo(1);
        assertThat(frame.hasFlag(HTTP2Frame.Flags.END_STREAM)).isTrue();
        assertThat(frame.hasFlag(HTTP2Frame.Flags.END_HEADERS)).isTrue();
    }

    @Test
    public void testParseSettingsFrame() {
        ByteBuffer buffer = ByteBuffer.allocate(100);
        buffer.putInt(0, 0x000012); // Length: 18 bytes (3 settings)
        buffer.put(4, (byte) 0x04); // Type: SETTINGS
        buffer.put(5, (byte) 0x00); // Flags: none
        buffer.putInt(6, 0x00000000); // Stream ID: 0
        // Setting 1: HEADER_TABLE_SIZE = 4096
        buffer.putShort(10, (short) 0x0001);
        buffer.putInt(12, 4096);
        // Setting 2: ENABLE_PUSH = 0
        buffer.putShort(16, (short) 0x0002);
        buffer.putInt(18, 0);
        // Setting 3: INITIAL_WINDOW_SIZE = 65535
        buffer.putShort(22, (short) 0x0007);
        buffer.putInt(24, 65535);
        buffer.limit(28);

        HTTP2Frame frame = parser.parseFrame(buffer);

        assertThat(frame).isNotNull();
        assertThat(frame.getType()).isEqualTo(HTTP2Frame.FrameType.SETTINGS);
        assertThat(frame.getStreamId()).isEqualTo(0);
    }

    @Test
    public void testParseWindowUpdateFrame() {
        ByteBuffer buffer = ByteBuffer.allocate(100);
        buffer.putInt(0, 0x000004); // Length: 4 bytes
        buffer.put(4, (byte) 0x08); // Type: WINDOW_UPDATE
        buffer.put(5, (byte) 0x00); // Flags: none
        buffer.putInt(6, 0x00000001); // Stream ID: 1
        buffer.putInt(10, 0x00001000); // Window increment: 4096
        buffer.limit(14);

        HTTP2Frame frame = parser.parseFrame(buffer);

        assertThat(frame).isNotNull();
        assertThat(frame.getType()).isEqualTo(HTTP2Frame.FrameType.WINDOW_UPDATE);
        assertThat(frame.getStreamId()).isEqualTo(1);
    }

    @Test
    public void testParseGoAwayFrame() {
        ByteBuffer buffer = ByteBuffer.allocate(100);
        buffer.putInt(0, 0x000008); // Length: 8 bytes
        buffer.put(4, (byte) 0x07); // Type: GOAWAY
        buffer.put(5, (byte) 0x00); // Flags: none
        buffer.putInt(6, 0x00000000); // Stream ID: 0
        buffer.putInt(10, 0x00000001); // Last-Stream-ID: 1
        buffer.putInt(14, 0x00000000); // Error code: NO_ERROR
        buffer.limit(18);

        HTTP2Frame frame = parser.parseFrame(buffer);

        assertThat(frame).isNotNull();
        assertThat(frame.getType()).isEqualTo(HTTP2Frame.FrameType.GOAWAY);
    }

    @Test
    public void testParseRstStreamFrame() {
        ByteBuffer buffer = ByteBuffer.allocate(100);
        buffer.putInt(0, 0x000004); // Length: 4 bytes
        buffer.put(4, (byte) 0x03); // Type: RST_STREAM
        buffer.put(5, (byte) 0x00); // Flags: none
        buffer.putInt(6, 0x00000001); // Stream ID: 1
        buffer.putInt(10, 0x00000000); // Error code: NO_ERROR
        buffer.limit(14);

        HTTP2Frame frame = parser.parseFrame(buffer);

        assertThat(frame).isNotNull();
        assertThat(frame.getType()).isEqualTo(HTTP2Frame.FrameType.RST_STREAM);
        assertThat(frame.getStreamId()).isEqualTo(1);
    }

    @Test
    public void testParsePingFrame() {
        ByteBuffer buffer = ByteBuffer.allocate(100);
        buffer.putInt(0, 0x000008); // Length: 8 bytes
        buffer.put(4, (byte) 0x06); // Type: PING
        buffer.put(5, (byte) 0x00); // Flags: none
        buffer.putInt(6, 0x00000000); // Stream ID: 0
        buffer.putLong(10, 0x0102030405060708L); // Opaque data
        buffer.limit(18);

        HTTP2Frame frame = parser.parseFrame(buffer);

        assertThat(frame).isNotNull();
        assertThat(frame.getType()).isEqualTo(HTTP2Frame.FrameType.PING);
        assertThat(frame.getStreamId()).isEqualTo(0);
    }

    @Test
    public void testEncodeDataFrame() {
        byte[] payload = {1, 2, 3, 4, 5};
        HTTP2Frame frame = new HTTP2Frame(
            HTTP2Frame.FrameType.DATA,
            (byte) 0x00,
            1,
            payload
        );

        ByteBuffer encoded = parser.encodeFrame(frame);

        assertThat(encoded).isNotNull();
        assertThat(encoded.remaining()).isGreaterThanOrEqualTo(9 + 5); // Header + payload
        encoded.rewind();
        int length = encoded.getInt() >>> 8; // First 3 bytes
        assertThat(length).isEqualTo(5);
    }

    @Test
    public void testStreamIdExtractionIgnoresPriorityBit() {
        ByteBuffer buffer = ByteBuffer.allocate(100);
        buffer.putInt(0, 0x000010); // Length: 16 bytes
        buffer.put(4, (byte) 0x01); // Type: HEADERS
        buffer.put(5, (byte) 0x00); // Flags: none
        buffer.putInt(6, 0x7FFFFFFF); // Stream ID with priority bit set
        buffer.put(10, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
        buffer.limit(26);

        HTTP2Frame frame = parser.parseFrame(buffer);

        assertThat(frame.getStreamId()).isEqualTo(0x7FFFFFFF & 0x7FFFFFFF); // Priority bit masked
    }

    @Test
    public void testMultipleFramesParsing() {
        ByteBuffer buffer = ByteBuffer.allocate(200);

        // First DATA frame
        buffer.putInt(0, 0x000005); // Length: 5 bytes
        buffer.put(4, (byte) 0x00); // Type: DATA
        buffer.put(5, (byte) 0x00); // Flags: none
        buffer.putInt(6, 0x00000001); // Stream ID: 1
        buffer.put(10, new byte[]{1, 2, 3, 4, 5});

        // Second DATA frame
        buffer.putInt(19, 0x000005); // Length: 5 bytes
        buffer.put(23, (byte) 0x00); // Type: DATA
        buffer.put(24, (byte) 0x00); // Flags: none
        buffer.putInt(25, 0x00000002); // Stream ID: 2
        buffer.put(29, new byte[]{6, 7, 8, 9, 10});
        buffer.limit(38);

        HTTP2Frame frame1 = parser.parseFrame(buffer);
        assertThat(frame1.getStreamId()).isEqualTo(1);
        assertThat(frame1.getLength()).isEqualTo(5);

        buffer.position(19);
        HTTP2Frame frame2 = parser.parseFrame(buffer);
        assertThat(frame2.getStreamId()).isEqualTo(2);
        assertThat(frame2.getLength()).isEqualTo(5);
    }

    @Test
    public void testInvalidFrameType() {
        ByteBuffer buffer = ByteBuffer.allocate(100);
        buffer.putInt(0, 0x000000); // Length: 0 bytes
        buffer.put(4, (byte) 0xFF); // Type: Invalid (255)
        buffer.put(5, (byte) 0x00); // Flags: none
        buffer.putInt(6, 0x00000001); // Stream ID: 1
        buffer.limit(9);

        HTTP2Frame frame = parser.parseFrame(buffer);
        assertThat(frame).isNull(); // Invalid frame type
    }
}
