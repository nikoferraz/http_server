package HTTPServer;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class HTTP2Frame {

    public enum FrameType {
        DATA(0x00),
        HEADERS(0x01),
        PRIORITY(0x02),
        RST_STREAM(0x03),
        SETTINGS(0x04),
        PUSH_PROMISE(0x05),
        PING(0x06),
        GOAWAY(0x07),
        WINDOW_UPDATE(0x08),
        CONTINUATION(0x09);

        private final byte code;

        FrameType(int code) {
            this.code = (byte) code;
        }

        public byte getCode() {
            return code;
        }

        public static FrameType fromCode(byte code) {
            for (FrameType type : FrameType.values()) {
                if (type.code == code) {
                    return type;
                }
            }
            return null;
        }
    }

    public static class Flags {
        public static final byte END_STREAM = 0x01;
        public static final byte END_HEADERS = 0x04;
        public static final byte PADDED = 0x08;
        public static final byte PRIORITY = 0x20;
        public static final byte ACK = 0x01;
    }

    private final FrameType type;
    private final byte flags;
    private final int streamId;
    private final byte[] payload;
    private final int length;

    public HTTP2Frame(FrameType type, byte flags, int streamId, byte[] payload) {
        this.type = type;
        this.flags = flags;
        this.streamId = streamId & 0x7FFFFFFF; // Remove reserved bit
        this.payload = payload;
        this.length = payload != null ? payload.length : 0;
    }

    public HTTP2Frame(FrameType type, byte flags, int streamId, int length) {
        this.type = type;
        this.flags = flags;
        this.streamId = streamId & 0x7FFFFFFF;
        this.length = length;
        this.payload = new byte[length];
    }

    public FrameType getType() {
        return type;
    }

    public byte getFlags() {
        return flags;
    }

    public int getStreamId() {
        return streamId;
    }

    public byte[] getPayload() {
        return payload;
    }

    public int getLength() {
        return length;
    }

    public boolean hasFlag(byte flag) {
        return (flags & flag) == flag;
    }

    public boolean isEndStream() {
        return hasFlag(Flags.END_STREAM);
    }

    public boolean isEndHeaders() {
        return hasFlag(Flags.END_HEADERS);
    }

    public ByteBuffer toByteBuffer() {
        ByteBuffer buffer = ByteBuffer.allocate(9 + length);

        // Frame header (9 bytes)
        // Length (3 bytes, big-endian)
        buffer.put((byte) ((length >> 16) & 0xFF));
        buffer.put((byte) ((length >> 8) & 0xFF));
        buffer.put((byte) (length & 0xFF));

        // Type (1 byte)
        buffer.put(type.getCode());

        // Flags (1 byte)
        buffer.put(flags);

        // Stream ID (4 bytes, most significant bit reserved and set to 0)
        buffer.putInt(streamId & 0x7FFFFFFF);

        // Payload
        if (payload != null && payload.length > 0) {
            buffer.put(payload);
        }

        buffer.flip();
        return buffer;
    }

    @Override
    public String toString() {
        return String.format(
            "HTTP2Frame{type=%s, flags=0x%02x, streamId=%d, length=%d, payload=%s}",
            type,
            flags & 0xFF,
            streamId,
            length,
            payload != null ? Arrays.toString(payload) : "null"
        );
    }
}
