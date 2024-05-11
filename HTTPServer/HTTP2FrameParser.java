package HTTPServer;

import java.nio.ByteBuffer;

public class HTTP2FrameParser {

    private static final int FRAME_HEADER_SIZE = 9;
    private static final int MAX_FRAME_SIZE = 16384; // Default max frame size
    private static final int MAX_PAYLOAD_SIZE = 16777215; // 2^24 - 1

    public HTTP2FrameParser() {
    }

    public HTTP2Frame parseFrame(ByteBuffer buffer) {
        if (buffer.remaining() < FRAME_HEADER_SIZE) {
            return null;
        }

        int startPosition = buffer.position();

        // Parse 3-byte length (big-endian)
        int length = ((buffer.get() & 0xFF) << 16)
                   | ((buffer.get() & 0xFF) << 8)
                   | (buffer.get() & 0xFF);

        if (length > MAX_PAYLOAD_SIZE) {
            return null;
        }

        // Parse type
        byte typeCode = buffer.get();
        HTTP2Frame.FrameType type = HTTP2Frame.FrameType.fromCode(typeCode);
        if (type == null) {
            buffer.position(startPosition);
            return null;
        }

        // Parse flags
        byte flags = buffer.get();

        // Parse stream ID (4 bytes, first bit reserved)
        int streamIdRaw = buffer.getInt();
        int streamId = streamIdRaw & 0x7FFFFFFF;

        // Check if we have the complete payload
        if (buffer.remaining() < length) {
            buffer.position(startPosition);
            return null;
        }

        // Parse payload
        byte[] payload = new byte[length];
        buffer.get(payload);

        return new HTTP2Frame(type, flags, streamId, payload);
    }

    public ByteBuffer encodeFrame(HTTP2Frame frame) {
        byte[] payload = frame.getPayload();
        int payloadLength = payload != null ? payload.length : 0;

        if (payloadLength > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("Payload size exceeds maximum allowed size");
        }

        ByteBuffer buffer = ByteBuffer.allocate(FRAME_HEADER_SIZE + payloadLength);

        // Encode length (3 bytes, big-endian)
        buffer.put((byte) ((payloadLength >> 16) & 0xFF));
        buffer.put((byte) ((payloadLength >> 8) & 0xFF));
        buffer.put((byte) (payloadLength & 0xFF));

        // Encode type
        buffer.put(frame.getType().getCode());

        // Encode flags
        buffer.put(frame.getFlags());

        // Encode stream ID
        buffer.putInt(frame.getStreamId() & 0x7FFFFFFF);

        // Encode payload
        if (payload != null && payload.length > 0) {
            buffer.put(payload);
        }

        buffer.flip();
        return buffer;
    }

    public boolean isValidFrameSize(int size) {
        return size >= 0 && size <= MAX_PAYLOAD_SIZE;
    }

    public HTTP2Frame createSettingsFrame(int initialWindowSize, int headerTableSize, boolean enablePush) {
        ByteBuffer payload = ByteBuffer.allocate(18);

        // HEADER_TABLE_SIZE (0x0001)
        payload.putShort((short) 0x0001);
        payload.putInt(headerTableSize);

        // ENABLE_PUSH (0x0002)
        payload.putShort((short) 0x0002);
        payload.putInt(enablePush ? 1 : 0);

        // INITIAL_WINDOW_SIZE (0x0007)
        payload.putShort((short) 0x0007);
        payload.putInt(initialWindowSize);

        payload.flip();
        return new HTTP2Frame(HTTP2Frame.FrameType.SETTINGS, (byte) 0x00, 0, payload.array());
    }

    public HTTP2Frame createWindowUpdateFrame(int streamId, int windowIncrement) {
        ByteBuffer payload = ByteBuffer.allocate(4);
        payload.putInt(windowIncrement & 0x7FFFFFFF); // Reserve bit must be 0
        payload.flip();
        return new HTTP2Frame(HTTP2Frame.FrameType.WINDOW_UPDATE, (byte) 0x00, streamId, payload.array());
    }

    public HTTP2Frame createGoAwayFrame(int lastStreamId, int errorCode, String debugData) {
        byte[] debugBytes = debugData != null ? debugData.getBytes() : new byte[0];
        ByteBuffer payload = ByteBuffer.allocate(8 + debugBytes.length);
        payload.putInt(lastStreamId & 0x7FFFFFFF);
        payload.putInt(errorCode);
        if (debugBytes.length > 0) {
            payload.put(debugBytes);
        }
        payload.flip();
        return new HTTP2Frame(HTTP2Frame.FrameType.GOAWAY, (byte) 0x00, 0, payload.array());
    }

    public HTTP2Frame createRstStreamFrame(int streamId, int errorCode) {
        ByteBuffer payload = ByteBuffer.allocate(4);
        payload.putInt(errorCode);
        payload.flip();
        return new HTTP2Frame(HTTP2Frame.FrameType.RST_STREAM, (byte) 0x00, streamId, payload.array());
    }

    public HTTP2Frame createPingFrame(long opaqueData) {
        ByteBuffer payload = ByteBuffer.allocate(8);
        payload.putLong(opaqueData);
        payload.flip();
        return new HTTP2Frame(HTTP2Frame.FrameType.PING, (byte) 0x00, 0, payload.array());
    }

    public HTTP2Frame createDataFrame(int streamId, byte[] data, boolean endStream) {
        byte flags = endStream ? HTTP2Frame.Flags.END_STREAM : 0;
        return new HTTP2Frame(HTTP2Frame.FrameType.DATA, flags, streamId, data);
    }

    public HTTP2Frame createHeadersFrame(int streamId, byte[] encodedHeaders, boolean endStream, boolean endHeaders) {
        byte flags = 0;
        if (endStream) {
            flags |= HTTP2Frame.Flags.END_STREAM;
        }
        if (endHeaders) {
            flags |= HTTP2Frame.Flags.END_HEADERS;
        }
        return new HTTP2Frame(HTTP2Frame.FrameType.HEADERS, flags, streamId, encodedHeaders);
    }
}
