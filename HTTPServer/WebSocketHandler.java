package HTTPServer;

/**
 * Application interface for WebSocket message handling.
 *
 * Implement this interface to handle WebSocket events for your application.
 * All callback methods are invoked within the context of the WebSocket connection's
 * thread, so implementations should be thread-safe and non-blocking.
 */
public interface WebSocketHandler {

    /**
     * Called when a WebSocket connection is established.
     *
     * @param connection the WebSocket connection
     */
    void onOpen(WebSocketConnection connection);

    /**
     * Called when a text message is received.
     *
     * @param connection the WebSocket connection
     * @param message the received text message
     */
    void onMessage(WebSocketConnection connection, String message);

    /**
     * Called when a binary message is received.
     *
     * @param connection the WebSocket connection
     * @param message the received binary message
     */
    void onMessage(WebSocketConnection connection, byte[] message);

    /**
     * Called when an error occurs on the connection.
     *
     * @param connection the WebSocket connection (may be null if error occurs before connection establishes)
     * @param error the throwable that occurred
     */
    void onError(WebSocketConnection connection, Throwable error);

    /**
     * Called when the connection is closed.
     *
     * @param connection the WebSocket connection
     * @param statusCode the close status code
     * @param reason the close reason (may be null or empty)
     */
    void onClose(WebSocketConnection connection, int statusCode, String reason);
}
