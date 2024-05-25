package HTTPServer;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Implementation of SSEConnection for managing a single Server-Sent Events connection.
 * Handles event formatting, keepalive, and graceful closure.
 *
 * Thread safety: All public methods are thread-safe.
 * Events are queued and sent sequentially to maintain ordering.
 */
public class SSEConnectionImpl implements SSEConnection {

    private static final Logger logger = Logger.getLogger(SSEConnectionImpl.class.getName());

    private final String connectionId;
    private final Socket socket;
    private final OutputStream outputStream;
    private final SSEHandler handler;
    private final String clientIp;
    private final String lastEventId;
    private final long createdAt;
    private final BlockingQueue<SSEEvent> eventQueue;
    private final int maxQueueSize;
    private final long keepaliveIntervalMs;
    private final MetricsCollector metrics;

    private volatile ConnectionState state;
    private final AtomicBoolean closed;
    private final AtomicLong lastActivityTime;
    private final AtomicLong eventsSent;
    private final AtomicLong bytesTransmitted;

    private Thread senderThread;
    private final Object stateLock = new Object();

    private static final int DEFAULT_QUEUE_SIZE = 100;
    private static final long DEFAULT_KEEPALIVE_MS = 20000; // 20 seconds
    private static final long ACTIVITY_CHECK_INTERVAL_MS = 5000; // 5 seconds
    private static final long INACTIVITY_TIMEOUT_MS = 60000; // 60 seconds

    public SSEConnectionImpl(String connectionId, Socket socket, SSEHandler handler,
                           String lastEventId, MetricsCollector metrics) throws IOException {
        this(connectionId, socket, handler, lastEventId, DEFAULT_QUEUE_SIZE, DEFAULT_KEEPALIVE_MS, metrics);
    }

    public SSEConnectionImpl(String connectionId, Socket socket, SSEHandler handler,
                           String lastEventId, int maxQueueSize, long keepaliveIntervalMs,
                           MetricsCollector metrics) throws IOException {
        if (socket == null || socket.getOutputStream() == null) {
            throw new IllegalArgumentException("Socket and output stream cannot be null");
        }

        this.connectionId = connectionId;
        this.socket = socket;
        this.outputStream = new BufferedOutputStream(socket.getOutputStream());
        this.handler = handler;
        this.lastEventId = lastEventId;
        this.clientIp = socket.getRemoteSocketAddress().toString();
        this.createdAt = System.currentTimeMillis();
        this.maxQueueSize = maxQueueSize;
        this.keepaliveIntervalMs = keepaliveIntervalMs;
        this.metrics = metrics;

        this.eventQueue = new LinkedBlockingQueue<>(maxQueueSize);
        this.closed = new AtomicBoolean(false);
        this.lastActivityTime = new AtomicLong(System.currentTimeMillis());
        this.eventsSent = new AtomicLong(0);
        this.bytesTransmitted = new AtomicLong(0);
        this.state = ConnectionState.CONNECTING;
    }

    /**
     * Activates the SSE connection and starts event processing.
     */
    @Override
    public void open() {
        synchronized (stateLock) {
            if (state != ConnectionState.CONNECTING) {
                throw new IllegalStateException("Connection is not in CONNECTING state");
            }
            state = ConnectionState.OPEN;
        }

        if (metrics != null) {
            metrics.incrementCounter("sse_connections_created");
            metrics.incrementGauge("sse_active_connections");
        }

        if (handler != null) {
            try {
                handler.onOpen(this);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error in handler onOpen callback", e);
                close();
                return;
            }
        }

        // Start the sender thread to process events and keepalive
        senderThread = Thread.ofVirtual().start(this::runSender);
    }

    /**
     * Sends an event to the client. If the queue is full, this method will block
     * or drop the event based on backpressure handling.
     */
    @Override
    public void sendEvent(SSEEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Event cannot be null");
        }

        if (!isOpen()) {
            throw new IllegalStateException("Connection is not open");
        }

        try {
            // Try to add with timeout to prevent indefinite blocking
            boolean added = eventQueue.offer(event, 5, TimeUnit.SECONDS);
            if (!added) {
                logger.fine("Event queue full for connection " + connectionId + ", dropping event");
                if (metrics != null) {
                    metrics.incrementCounter("sse_events_dropped");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while queuing event", e);
        }
    }

    /**
     * Closes the connection gracefully.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return; // Already closed
        }

        synchronized (stateLock) {
            state = ConnectionState.CLOSED;
        }

        if (metrics != null) {
            metrics.decrementGauge("sse_active_connections");
            long duration = System.currentTimeMillis() - createdAt;
            metrics.observeHistogram("sse_connection_duration_ms", duration);
        }

        // Interrupt sender thread
        if (senderThread != null) {
            senderThread.interrupt();
        }

        // Close streams
        try {
            if (outputStream != null) {
                outputStream.flush();
                outputStream.close();
            }
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            logger.log(Level.FINE, "Error closing SSE connection", e);
        }

        // Notify handler
        if (handler != null) {
            try {
                handler.onClose(this);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error in handler onClose callback", e);
            }
        }
    }

    /**
     * Main event sender loop. Runs in a virtual thread.
     */
    private void runSender() {
        try {
            long lastKeepaliveTime = System.currentTimeMillis();

            while (!closed.get() && state == ConnectionState.OPEN) {
                long now = System.currentTimeMillis();
                long timeSinceLastKeepalive = now - lastKeepaliveTime;

                try {
                    // Wait for an event with timeout for keepalive
                    long timeout = Math.max(keepaliveIntervalMs - timeSinceLastKeepalive, 100);
                    SSEEvent event = eventQueue.poll(timeout, TimeUnit.MILLISECONDS);

                    if (event != null) {
                        writeEvent(event);
                        lastKeepaliveTime = System.currentTimeMillis();
                        lastActivityTime.set(lastKeepaliveTime);
                        eventsSent.incrementAndGet();

                        if (metrics != null) {
                            metrics.incrementCounter("sse_events_sent");
                        }
                    } else if (timeSinceLastKeepalive >= keepaliveIntervalMs) {
                        // Send keepalive comment
                        writeKeepalive();
                        lastKeepaliveTime = System.currentTimeMillis();
                        lastActivityTime.set(lastKeepaliveTime);

                        if (metrics != null) {
                            metrics.incrementCounter("sse_keepalive_sent");
                        }
                    }

                    // Check for inactivity timeout
                    if (now - lastActivityTime.get() > INACTIVITY_TIMEOUT_MS) {
                        logger.fine("SSE connection " + connectionId + " inactive, closing");
                        close();
                    }

                } catch (InterruptedException e) {
                    if (!closed.get()) {
                        logger.fine("SSE sender thread interrupted");
                    }
                    break;
                } catch (IOException e) {
                    logger.log(Level.FINE, "IO error writing SSE event, closing connection", e);
                    if (handler != null) {
                        try {
                            handler.onError(this, e);
                        } catch (Exception he) {
                            logger.log(Level.WARNING, "Error in handler onError callback", he);
                        }
                    }
                    close();
                    break;
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Unexpected error in SSE sender thread", e);
        } finally {
            if (!closed.get()) {
                close();
            }
        }
    }

    /**
     * Writes an event to the output stream.
     */
    private void writeEvent(SSEEvent event) throws IOException {
        byte[] bytes = event.toBytes();
        outputStream.write(bytes);
        outputStream.flush();
        bytesTransmitted.addAndGet(bytes.length);
    }

    /**
     * Writes a keepalive comment to prevent proxy/firewall timeout.
     */
    private void writeKeepalive() throws IOException {
        byte[] bytes = SSEEvent.keepaliveComment();
        outputStream.write(bytes);
        outputStream.flush();
        bytesTransmitted.addAndGet(bytes.length);
    }

    // State queries
    @Override
    public boolean isOpen() {
        return state == ConnectionState.OPEN && !closed.get();
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public ConnectionState getState() {
        return state;
    }

    // Metrics and diagnostics
    @Override
    public String getConnectionId() {
        return connectionId;
    }

    @Override
    public String getClientIp() {
        return clientIp;
    }

    @Override
    public String getLastEventId() {
        return lastEventId;
    }

    @Override
    public long getCreatedAt() {
        return createdAt;
    }

    @Override
    public long getEventsSent() {
        return eventsSent.get();
    }

    @Override
    public long getBytesTransmitted() {
        return bytesTransmitted.get();
    }

    @Override
    public int getQueueSize() {
        return eventQueue.size();
    }

    @Override
    public long getConnectionDuration() {
        return System.currentTimeMillis() - createdAt;
    }

    @Override
    public long getLastActivityTime() {
        return lastActivityTime.get();
    }
}
