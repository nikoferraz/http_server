package HTTPServer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * WebSocket Frame implementation per RFC 6455.
 *
 * Frame Format:
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-------+-+-------------+-------------------------------+
 * |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
 * |I|S|S|S|(4)    |A|     (7)     |             (0/16/64)         |
 * |N|V|V|V|       |S|             |   (if payload len==126/127)   |
 * | |1|2|3|       |K|             |                               |
 * +-+-+-+-+-------+-+-------------+                               +
 * |                     Masking-key (32 bits)                     |
 * +-------------------------------+-------------------------------+
 * | Masking-key (continued)       |          Payload Data         |
 * +-------------------------------+-------------------------------+
 * |                     Payload Data continued ...                |
 * +---------------------------------------------------------------+
 */
public class WebSocketFrame {

    public static final byte OPCODE_CONTINUATION = 0x0;
    public static final byte OPCODE_TEXT = 0x1;
    public static final byte OPCODE_BINARY = 0x2;
    public static final byte OPCODE_CLOSE = 0x8;
    public static final byte OPCODE_PING = 0x9;
    public static final byte OPCODE_PONG = 0xA;

    private static final byte FIN_BIT_MASK = (byte) 0x80;
    private static final byte OPCODE_MASK = 0x0F;
    private static final byte MASK_BIT_MASK = (byte) 0x80;
    private static final byte PAYLOAD_LENGTH_MASK = 0x7F;

    private static final int MIN_FRAME_SIZE = 2;
    private static final int PAYLOAD_16_BIT_THRESHOLD = 126;
    private static final int PAYLOAD_64_BIT_THRESHOLD = 127;

    private final boolean fin;
    private final byte opcode;
    private final boolean masked;
    private final byte[] maskingKey;
    private final byte[] payload;

    public WebSocketFrame(boolean fin, byte opcode, boolean masked, byte[] payload) {
        this(fin, opcode, masked, masked ? new byte[4] : null, payload);
    }

    public WebSocketFrame(boolean fin, byte opcode, boolean masked, byte[] maskingKey, byte[] payload) {
        if (maskingKey != null && maskingKey.length != 4) {
            throw new IllegalArgumentException("Masking key must be exactly 4 bytes");
        }
        if (masked && maskingKey == null) {
            throw new IllegalArgumentException("Masked frames must have a masking key");
        }

        this.fin = fin;
        this.opcode = (byte) (opcode & OPCODE_MASK);
        this.masked = masked;
        this.maskingKey = maskingKey;
        this.payload = payload != null ? payload : new byte[0];
    }

    public boolean isFin() {
        return fin;
    }

    public byte getOpcode() {
        return opcode;
    }

    public boolean isMasked() {
        return masked;
    }

    public byte[] getMaskingKey() {
        return maskingKey;
    }

    public byte[] getPayload() {
        return payload;
    }

    public int getPayloadLength() {
        return payload.length;
    }

    public String getPayloadAsText() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    public boolean isControlFrame() {
        return (opcode & 0x8) != 0;
    }

    public boolean isContinuationFrame() {
        return opcode == OPCODE_CONTINUATION;
    }

    public boolean isTextFrame() {
        return opcode == OPCODE_TEXT;
    }

    public boolean isBinaryFrame() {
        return opcode == OPCODE_BINARY;
    }

    public boolean isCloseFrame() {
        return opcode == OPCODE_CLOSE;
    }

    public boolean isPingFrame() {
        return opcode == OPCODE_PING;
    }

    public boolean isPongFrame() {
        return opcode == OPCODE_PONG;
    }

    /**
     * Encodes this frame into bytes for transmission.
     */
    public byte[] encode() {
        int headerSize = calculateHeaderSize(payload.length);
        ByteBuffer buffer = ByteBuffer.allocate(headerSize + payload.length);

        // First byte: FIN + RSV + opcode
        byte byte0 = (byte) (opcode & OPCODE_MASK);
        if (fin) {
            byte0 |= FIN_BIT_MASK;
        }
        buffer.put(byte0);

        // Second byte: MASK + payload length
        byte byte1;
        int payloadLen = payload.length;

        if (payloadLen < PAYLOAD_16_BIT_THRESHOLD) {
            byte1 = (byte) payloadLen;
            if (masked) {
                byte1 |= MASK_BIT_MASK;
            }
            buffer.put(byte1);
        } else if (payloadLen < 0x10000) {
            byte1 = PAYLOAD_16_BIT_THRESHOLD;
            if (masked) {
                byte1 |= MASK_BIT_MASK;
            }
            buffer.put(byte1);
            buffer.putShort((short) payloadLen);
        } else {
            byte1 = PAYLOAD_64_BIT_THRESHOLD;
            if (masked) {
                byte1 |= MASK_BIT_MASK;
            }
            buffer.put(byte1);
            buffer.putLong(payloadLen);
        }

        // Masking key (if masked)
        if (masked) {
            buffer.put(maskingKey);
        }

        // Payload
        if (payload.length > 0) {
            if (masked) {
                buffer.put(maskPayload(payload, maskingKey));
            } else {
                buffer.put(payload);
            }
        }

        buffer.flip();
        return buffer.array();
    }

    /**
     * Parses a WebSocket frame from bytes.
     *
     * @return a Frame object, or null if not enough data is available
     */
    public static WebSocketFrame parse(ByteBuffer buffer) throws WebSocketException {
        if (buffer.remaining() < MIN_FRAME_SIZE) {
            return null; // Need more data
        }

        int startPos = buffer.position();

        // First byte: FIN + RSV + opcode
        byte byte0 = buffer.get();
        boolean fin = (byte0 & FIN_BIT_MASK) != 0;
        byte rsv = (byte) ((byte0 & 0x70) >> 4);
        if (rsv != 0) {
            throw new WebSocketException("RSV bits must be 0");
        }
        byte opcode = (byte) (byte0 & OPCODE_MASK);

        // Second byte: MASK + payload length
        byte byte1 = buffer.get();
        boolean masked = (byte1 & MASK_BIT_MASK) != 0;
        long payloadLength = byte1 & PAYLOAD_LENGTH_MASK;

        // Extended payload length
        if (payloadLength == PAYLOAD_16_BIT_THRESHOLD) {
            if (buffer.remaining() < 2) {
                buffer.position(startPos);
                return null;
            }
            payloadLength = buffer.getShort() & 0xFFFFL;
        } else if (payloadLength == PAYLOAD_64_BIT_THRESHOLD) {
            if (buffer.remaining() < 8) {
                buffer.position(startPos);
                return null;
            }
            payloadLength = buffer.getLong();
            if (payloadLength < 0) {
                throw new WebSocketException("Payload length cannot be negative");
            }
        }

        if (payloadLength > Integer.MAX_VALUE) {
            throw new WebSocketException("Payload length exceeds maximum allowed size");
        }

        int payloadLen = (int) payloadLength;

        // Masking key
        byte[] maskingKey = null;
        if (masked) {
            if (buffer.remaining() < 4) {
                buffer.position(startPos);
                return null;
            }
            maskingKey = new byte[4];
            buffer.get(maskingKey);
        }

        // Payload data
        if (buffer.remaining() < payloadLen) {
            buffer.position(startPos);
            return null;
        }

        byte[] payload = new byte[payloadLen];
        buffer.get(payload);

        // Unmask if necessary
        if (masked) {
            unmaskPayload(payload, maskingKey);
        }

        return new WebSocketFrame(fin, opcode, masked, maskingKey, payload);
    }

    /**
     * Calculates the size of the frame header based on payload length.
     */
    private static int calculateHeaderSize(int payloadLength) {
        int headerSize = 2; // FIN, RSV, opcode, MASK, basic payload length
        if (payloadLength >= PAYLOAD_16_BIT_THRESHOLD && payloadLength < 0x10000) {
            headerSize += 2; // 16-bit extended payload length
        } else if (payloadLength >= 0x10000) {
            headerSize += 8; // 64-bit extended payload length
        }
        headerSize += 4; // Masking key (if masked)
        return headerSize;
    }

    /**
     * Masks payload data with the given 4-byte masking key.
     */
    private static byte[] maskPayload(byte[] payload, byte[] maskingKey) {
        byte[] masked = new byte[payload.length];
        for (int i = 0; i < payload.length; i++) {
            masked[i] = (byte) (payload[i] ^ maskingKey[i % 4]);
        }
        return masked;
    }

    /**
     * Unmasks payload data with the given 4-byte masking key (in-place).
     */
    private static void unmaskPayload(byte[] payload, byte[] maskingKey) {
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= maskingKey[i % 4];
        }
    }

    @Override
    public String toString() {
        String opcodeStr = switch (opcode) {
            case OPCODE_CONTINUATION -> "CONTINUATION";
            case OPCODE_TEXT -> "TEXT";
            case OPCODE_BINARY -> "BINARY";
            case OPCODE_CLOSE -> "CLOSE";
            case OPCODE_PING -> "PING";
            case OPCODE_PONG -> "PONG";
            default -> String.format("UNKNOWN(0x%X)", opcode);
        };

        return String.format("WebSocketFrame{opcode=%s, fin=%b, masked=%b, payloadLen=%d}",
                opcodeStr, fin, masked, payload.length);
    }

    public static class WebSocketException extends Exception {
        public WebSocketException(String message) {
            super(message);
        }

        public WebSocketException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
