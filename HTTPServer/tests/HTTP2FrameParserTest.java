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

    private void writeFrameHeader(ByteBuffer buffer, int length, byte type, byte flags, int streamId) {
        buffer.put((byte) ((length >> 16) & 0xFF));
        buffer.put((byte) ((length >> 8) & 0xFF));
        buffer.put((byte) (length & 0xFF));
        buffer.put(type);
        buffer.put(flags);
        buffer.putInt(streamId);
    }

    @Test
    public void testParseDataFrame() {
        ByteBuffer buffer = ByteBuffer.allocate(100);
        writeFrameHeader(buffer, 16, (byte) 0x00, (byte) 0x00, 1);
        buffer.put(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
        buffer.flip();

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
        writeFrameHeader(buffer, 16, (byte) 0x01, (byte) 0x05, 1);
        buffer.put(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
        buffer.flip();

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
        writeFrameHeader(buffer, 18, (byte) 0x04, (byte) 0x00, 0);
        buffer.putShort((short) 0x0001);
        buffer.putInt(4096);
        buffer.putShort((short) 0x0002);
        buffer.putInt(0);
        buffer.putShort((short) 0x0007);
        buffer.putInt(65535);
        buffer.flip();

        HTTP2Frame frame = parser.parseFrame(buffer);

        assertThat(frame).isNotNull();
        assertThat(frame.getType()).isEqualTo(HTTP2Frame.FrameType.SETTINGS);
        assertThat(frame.getStreamId()).isEqualTo(0);
    }

    @Test
    public void testParseWindowUpdateFrame() {
        ByteBuffer buffer = ByteBuffer.allocate(100);
        writeFrameHeader(buffer, 4, (byte) 0x08, (byte) 0x00, 1);
        buffer.putInt(0x00001000);
        buffer.flip();

        HTTP2Frame frame = parser.parseFrame(buffer);

        assertThat(frame).isNotNull();
        assertThat(frame.getType()).isEqualTo(HTTP2Frame.FrameType.WINDOW_UPDATE);
        assertThat(frame.getStreamId()).isEqualTo(1);
    }

    @Test
    public void testParseGoAwayFrame() {
        ByteBuffer buffer = ByteBuffer.allocate(100);
        writeFrameHeader(buffer, 8, (byte) 0x07, (byte) 0x00, 0);
        buffer.putInt(0x00000001);
        buffer.putInt(0x00000000);
        buffer.flip();

        HTTP2Frame frame = parser.parseFrame(buffer);

        assertThat(frame).isNotNull();
        assertThat(frame.getType()).isEqualTo(HTTP2Frame.FrameType.GOAWAY);
    }

    @Test
    public void testParseRstStreamFrame() {
        ByteBuffer buffer = ByteBuffer.allocate(100);
        writeFrameHeader(buffer, 4, (byte) 0x03, (byte) 0x00, 1);
        buffer.putInt(0x00000000);
        buffer.flip();

        HTTP2Frame frame = parser.parseFrame(buffer);

        assertThat(frame).isNotNull();
        assertThat(frame.getType()).isEqualTo(HTTP2Frame.FrameType.RST_STREAM);
        assertThat(frame.getStreamId()).isEqualTo(1);
    }

    @Test
    public void testParsePingFrame() {
        ByteBuffer buffer = ByteBuffer.allocate(100);
        writeFrameHeader(buffer, 8, (byte) 0x06, (byte) 0x00, 0);
        buffer.putLong(0x0102030405060708L);
        buffer.flip();

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
        assertThat(encoded.remaining()).isGreaterThanOrEqualTo(9 + 5);
        encoded.rewind();
        int length = ((encoded.get() & 0xFF) << 16) |
                     ((encoded.get() & 0xFF) << 8) |
                     (encoded.get() & 0xFF);
        assertThat(length).isEqualTo(5);
    }

    @Test
    public void testStreamIdExtractionIgnoresPriorityBit() {
        ByteBuffer buffer = ByteBuffer.allocate(100);
        writeFrameHeader(buffer, 16, (byte) 0x01, (byte) 0x00, 0x7FFFFFFF);
        buffer.put(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
        buffer.flip();

        HTTP2Frame frame = parser.parseFrame(buffer);

        assertThat(frame).isNotNull();
        assertThat(frame.getStreamId()).isEqualTo(0x7FFFFFFF & 0x7FFFFFFF);
    }

    @Test
    public void testMultipleFramesParsing() {
        ByteBuffer buffer = ByteBuffer.allocate(200);

        writeFrameHeader(buffer, 5, (byte) 0x00, (byte) 0x00, 1);
        buffer.put(new byte[]{1, 2, 3, 4, 5});

        writeFrameHeader(buffer, 5, (byte) 0x00, (byte) 0x00, 2);
        buffer.put(new byte[]{6, 7, 8, 9, 10});
        buffer.flip();

        HTTP2Frame frame1 = parser.parseFrame(buffer);
        assertThat(frame1).isNotNull();
        assertThat(frame1.getStreamId()).isEqualTo(1);
        assertThat(frame1.getLength()).isEqualTo(5);

        HTTP2Frame frame2 = parser.parseFrame(buffer);
        assertThat(frame2).isNotNull();
        assertThat(frame2.getStreamId()).isEqualTo(2);
        assertThat(frame2.getLength()).isEqualTo(5);
    }

    @Test
    public void testInvalidFrameType() {
        ByteBuffer buffer = ByteBuffer.allocate(100);
        writeFrameHeader(buffer, 0, (byte) 0xFF, (byte) 0x00, 1);
        buffer.flip();

        HTTP2Frame frame = parser.parseFrame(buffer);
        assertThat(frame).isNull();
    }
}
