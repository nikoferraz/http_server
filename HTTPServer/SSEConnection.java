package HTTPServer;

/**
 * Interface for Server-Sent Events connections.
 * Implementations handle event formatting, keepalive, and graceful closure.
 *
 * Connection lifecycle:
 *   1. CONNECTING - Handshake phase
 *   2. OPEN - Ready to send events
 *   3. CLOSED - Connection terminated
 *
 * Thread safety: All public methods are thread-safe.
 * Events are queued and sent sequentially to maintain ordering.
 */
public interface SSEConnection {

    enum ConnectionState {
        CONNECTING,
        OPEN,
        CLOSED
    }

    /**
     * Activates the SSE connection and starts event processing.
     */
    void open();

    /**
     * Sends an event to the client.
     */
    void sendEvent(SSEEvent event);

    /**
     * Closes the connection gracefully.
     */
    void close();

    // State queries
    boolean isOpen();

    boolean isClosed();

    ConnectionState getState();

    // Metrics and diagnostics
    String getConnectionId();

    String getClientIp();

    String getLastEventId();

    long getCreatedAt();

    long getEventsSent();

    long getBytesTransmitted();

    int getQueueSize();

    long getConnectionDuration();

    long getLastActivityTime();
}
