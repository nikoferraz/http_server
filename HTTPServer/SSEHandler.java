package HTTPServer;

/**
 * Application interface for Server-Sent Events handling.
 *
 * Implement this interface to provide custom SSE event handling logic.
 * All callback methods are invoked within the context of the SSE connection's thread.
 * Implementations should be thread-safe and non-blocking where possible.
 */
public interface SSEHandler {

    /**
     * Called when an SSE connection is established and ready to send events.
     *
     * @param connection the SSE connection
     */
    void onOpen(SSEConnection connection);

    /**
     * Called when the SSE connection is closed.
     *
     * @param connection the SSE connection
     */
    void onClose(SSEConnection connection);

    /**
     * Called when an error occurs on the connection.
     *
     * @param connection the SSE connection
     * @param error the throwable that occurred
     */
    void onError(SSEConnection connection, Throwable error);
}
