package HTTPServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * WebSocket connection implementation per RFC 6455.
 *
 * Manages the full-duplex communication between client and server after
 * the opening handshake. Handles frame parsing, message assembly,
 * control frames, and connection lifecycle.
 */
public class WebSocketConnection {

    private static final Logger logger = Logger.getLogger(WebSocketConnection.class.getName());

    // Close status codes per RFC 6455
    public static final int STATUS_NORMAL_CLOSURE = 1000;
    public static final int STATUS_GOING_AWAY = 1001;
    public static final int STATUS_PROTOCOL_ERROR = 1002;
    public static final int STATUS_UNSUPPORTED_DATA = 1003;
    public static final int STATUS_INVALID_FRAME_PAYLOAD_DATA = 1007;
    public static final int STATUS_POLICY_VIOLATION = 1008;
    public static final int STATUS_MESSAGE_TOO_BIG = 1009;
    public static final int STATUS_MISSING_EXTENSION = 1010;
    public static final int STATUS_INTERNAL_ERROR = 1011;

    // Configuration defaults
    private static final long IDLE_TIMEOUT_MS = 60_000; // 60 seconds
    private static final int MAX_FRAME_SIZE = 1024 * 1024; // 1 MB
    private static final int MAX_MESSAGE_SIZE = 10 * 1024 * 1024; // 10 MB
    private static final int PING_INTERVAL_MS = 30_000; // 30 seconds
    private static final int BUFFER_SIZE = 8192;

    private enum ConnectionState {
        OPEN, CLOSING, CLOSED
    }

    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;
    private final WebSocketHandler handler;
    private final MetricsCollector metrics;
    private final String connectionId;

    private ConnectionState state = ConnectionState.OPEN;
    private final ReentrantLock stateLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private ByteBuffer readBuffer;
    private ByteBuffer assemblyBuffer;
    private final ReentrantLock readLock = new ReentrantLock();
    private final ReentrantLock writeLock = new ReentrantLock();

    private byte lastOpcode = WebSocketFrame.OPCODE_CONTINUATION;
    private long lastActivityTime = System.currentTimeMillis();

    private long messagesReceived = 0;
    private long messagesSent = 0;
    private long bytesReceived = 0;
    private long bytesSent = 0;

    public WebSocketConnection(Socket socket, WebSocketHandler handler, MetricsCollector metrics, String connectionId)
            throws IOException {
        this.socket = socket;
        this.handler = handler;
        this.metrics = metrics;
        this.connectionId = connectionId;

        this.input = socket.getInputStream();
        this.output = socket.getOutputStream();

        // Allocate buffers
        this.readBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        this.assemblyBuffer = ByteBuffer.allocate(MAX_MESSAGE_SIZE);

        // Set socket options
        socket.setTcpNoDelay(true);
        socket.setSoTimeout((int) IDLE_TIMEOUT_MS);
    }

    /**
     * Starts the WebSocket connection event loop.
     * This should be called in a dedicated thread (typically a virtual thread).
     */
    public void handleConnection() {
        try {
            handler.onOpen(this);
            recordMetric("websocket.connections.opened");

            while (!closed.get()) {
                try {
                    readFrame();
                } catch (WebSocketFrame.WebSocketException e) {
                    logger.log(Level.WARNING, "WebSocket frame error: " + e.getMessage(), e);
                    handler.onError(this, e);
                    close(STATUS_PROTOCOL_ERROR, "Invalid frame");
                    break;
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "WebSocket connection error: " + e.getMessage(), e);
            handler.onError(this, e);
        } finally {
            try {
                close(STATUS_GOING_AWAY, "Connection terminated");
            } catch (Exception e) {
                logger.log(Level.FINE, "Error during cleanup", e);
            }
        }
    }

    /**
     * Reads and processes a single frame from the client.
     */
    private void readFrame() throws IOException, WebSocketFrame.WebSocketException {
        readLock.lock();
        try {
            // Read data into buffer
            int bytesRead = input.read(readBuffer.array(), readBuffer.position(), readBuffer.remaining());
            if (bytesRead == -1) {
                // Connection closed by client
                close(STATUS_GOING_AWAY, "Client closed connection");
                return;
            }

            if (bytesRead > 0) {
                readBuffer.position(readBuffer.position() + bytesRead);
                lastActivityTime = System.currentTimeMillis();
                bytesReceived += bytesRead;

                readBuffer.flip();

                // Try to parse frames
                while (readBuffer.hasRemaining()) {
                    int startPos = readBuffer.position();
                    WebSocketFrame frame = WebSocketFrame.parse(readBuffer);

                    if (frame == null) {
                        // Not enough data yet
                        readBuffer.position(startPos);
                        readBuffer.compact();
                        break;
                    }

                    // Process frame
                    processFrame(frame);
                }

                readBuffer.compact();
            }
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Processes a received frame.
     */
    private void processFrame(WebSocketFrame frame) throws IOException, WebSocketFrame.WebSocketException {
        if (frame.getPayloadLength() > MAX_FRAME_SIZE) {
            throw new WebSocketFrame.WebSocketException("Frame exceeds maximum size");
        }

        // Handle control frames first (they can't be fragmented)
        if (frame.isControlFrame()) {
            processControlFrame(frame);
            return;
        }

        // Handle data frames (can be fragmented)
        if (frame.isTextFrame()) {
            // Text frame starts a new message
            lastOpcode = WebSocketFrame.OPCODE_TEXT;
            assemblyBuffer.clear();
            assemblyBuffer.put(frame.getPayload());

            if (frame.isFin()) {
                String message = new String(assemblyBuffer.array(), 0, assemblyBuffer.position(), StandardCharsets.UTF_8);
                messagesReceived++;
                handler.onMessage(this, message);
                recordMetric("websocket.messages.received");
            }
        } else if (frame.isBinaryFrame()) {
            // Binary frame starts a new message
            lastOpcode = WebSocketFrame.OPCODE_BINARY;
            assemblyBuffer.clear();
            assemblyBuffer.put(frame.getPayload());

            if (frame.isFin()) {
                byte[] message = new byte[assemblyBuffer.position()];
                assemblyBuffer.get(0, message);
                messagesReceived++;
                handler.onMessage(this, message);
                recordMetric("websocket.messages.received");
            }
        } else if (frame.isContinuationFrame()) {
            // Continuation frame
            if (assemblyBuffer.position() + frame.getPayloadLength() > MAX_MESSAGE_SIZE) {
                throw new WebSocketFrame.WebSocketException("Message exceeds maximum size");
            }

            assemblyBuffer.put(frame.getPayload());

            if (frame.isFin()) {
                if (lastOpcode == WebSocketFrame.OPCODE_TEXT) {
                    String message = new String(assemblyBuffer.array(), 0, assemblyBuffer.position(), StandardCharsets.UTF_8);
                    messagesReceived++;
                    handler.onMessage(this, message);
                    recordMetric("websocket.messages.received");
                } else if (lastOpcode == WebSocketFrame.OPCODE_BINARY) {
                    byte[] message = new byte[assemblyBuffer.position()];
                    assemblyBuffer.get(0, message);
                    messagesReceived++;
                    handler.onMessage(this, message);
                    recordMetric("websocket.messages.received");
                }
            }
        }
    }

    /**
     * Processes a control frame (ping, pong, close).
     */
    private void processControlFrame(WebSocketFrame frame) throws IOException {
        if (frame.isPingFrame()) {
            // Respond with pong
            byte[] pongPayload = frame.getPayload();
            sendFrame(new WebSocketFrame(true, WebSocketFrame.OPCODE_PONG, false, pongPayload));
        } else if (frame.isCloseFrame()) {
            // Handle close frame
            int statusCode = STATUS_NORMAL_CLOSURE;
            String reason = "";

            if (frame.getPayloadLength() >= 2) {
                ByteBuffer buf = ByteBuffer.wrap(frame.getPayload());
                statusCode = buf.getShort() & 0xFFFF;

                if (frame.getPayloadLength() > 2) {
                    byte[] reasonBytes = new byte[frame.getPayloadLength() - 2];
                    buf.get(reasonBytes);
                    reason = new String(reasonBytes, StandardCharsets.UTF_8);
                }
            }

            // Send close response if we haven't already initiated close
            stateLock.lock();
            try {
                if (state == ConnectionState.OPEN) {
                    state = ConnectionState.CLOSING;
                    sendFrame(new WebSocketFrame(true, WebSocketFrame.OPCODE_CLOSE, false, frame.getPayload()));
                }
            } finally {
                stateLock.unlock();
            }

            close(statusCode, reason);
        }
    }

    /**
     * Sends a text message.
     */
    public void sendMessage(String message) throws IOException {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        sendFrame(new WebSocketFrame(true, WebSocketFrame.OPCODE_TEXT, false, payload));
    }

    /**
     * Sends a binary message.
     */
    public void sendMessage(byte[] message) throws IOException {
        sendFrame(new WebSocketFrame(true, WebSocketFrame.OPCODE_BINARY, false, message));
    }

    /**
     * Sends a ping frame.
     */
    public void sendPing(byte[] payload) throws IOException {
        if (payload == null) {
            payload = new byte[0];
        }
        sendFrame(new WebSocketFrame(true, WebSocketFrame.OPCODE_PING, false, payload));
    }

    /**
     * Sends a WebSocket frame.
     */
    private void sendFrame(WebSocketFrame frame) throws IOException {
        writeLock.lock();
        try {
            if (closed.get()) {
                throw new IOException("Connection is closed");
            }

            byte[] frameBytes = frame.encode();
            output.write(frameBytes);
            output.flush();

            bytesSent += frameBytes.length;
            if (!frame.isControlFrame()) {
                messagesSent++;
                recordMetric("websocket.messages.sent");
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Closes the WebSocket connection with the given status code and reason.
     */
    public void close(int statusCode, String reason) throws IOException {
        stateLock.lock();
        try {
            if (closed.getAndSet(true)) {
                return; // Already closed
            }

            if (state == ConnectionState.OPEN) {
                state = ConnectionState.CLOSING;

                // Send close frame
                ByteBuffer closePayload = ByteBuffer.allocate(reason != null ? 2 + reason.length() : 2);
                closePayload.putShort((short) statusCode);
                if (reason != null && !reason.isEmpty()) {
                    closePayload.put(reason.getBytes(StandardCharsets.UTF_8));
                }
                closePayload.flip();

                try {
                    sendFrame(new WebSocketFrame(true, WebSocketFrame.OPCODE_CLOSE, false, closePayload.array()));
                } catch (IOException e) {
                    logger.log(Level.FINE, "Error sending close frame", e);
                }
            }

            state = ConnectionState.CLOSED;
        } finally {
            stateLock.unlock();
        }

        // Close socket
        try {
            socket.close();
        } catch (IOException e) {
            logger.log(Level.FINE, "Error closing socket", e);
        }

        recordMetric("websocket.connections.closed");
        handler.onClose(this, statusCode, reason != null ? reason : "");
    }

    /**
     * Returns true if the connection is open.
     */
    public boolean isOpen() {
        return !closed.get() && state == ConnectionState.OPEN;
    }

    /**
     * Returns the connection ID.
     */
    public String getConnectionId() {
        return connectionId;
    }

    /**
     * Returns the remote address of the client.
     */
    public String getRemoteAddress() {
        return socket.getInetAddress().getHostAddress();
    }

    /**
     * Returns the remote port of the client.
     */
    public int getRemotePort() {
        return socket.getPort();
    }

    /**
     * Returns statistics about this connection.
     */
    public ConnectionStats getStats() {
        return new ConnectionStats(
                messagesReceived,
                messagesSent,
                bytesReceived,
                bytesSent,
                System.currentTimeMillis() - lastActivityTime
        );
    }

    private void recordMetric(String metricName) {
        if (metrics != null) {
            metrics.incrementCounter(metricName);
        }
    }

    /**
     * Statistics about a WebSocket connection.
     */
    public static class ConnectionStats {
        public final long messagesReceived;
        public final long messagesSent;
        public final long bytesReceived;
        public final long bytesSent;
        public final long idleTimeMs;

        public ConnectionStats(long messagesReceived, long messagesSent, long bytesReceived, long bytesSent, long idleTimeMs) {
            this.messagesReceived = messagesReceived;
            this.messagesSent = messagesSent;
            this.bytesReceived = bytesReceived;
            this.bytesSent = bytesSent;
            this.idleTimeMs = idleTimeMs;
        }

        @Override
        public String toString() {
            return String.format("ConnectionStats{msgs_rx=%d, msgs_tx=%d, bytes_rx=%d, bytes_tx=%d, idle_ms=%d}",
                    messagesReceived, messagesSent, bytesReceived, bytesSent, idleTimeMs);
        }
    }
}
