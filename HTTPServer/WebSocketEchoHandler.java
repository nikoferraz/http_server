package HTTPServer;

import java.io.IOException;

/**
 * WebSocket echo server handler - echoes back all messages received.
 *
 * Example usage:
 *   WebSocketHandler echoHandler = new WebSocketEchoHandler();
 *   // Pass to ProcessRequest via setWebSocketHandler()
 */
public class WebSocketEchoHandler implements WebSocketHandler {

    private static final java.util.logging.Logger logger =
            java.util.logging.Logger.getLogger(WebSocketEchoHandler.class.getName());

    @Override
    public void onOpen(WebSocketConnection connection) {
        logger.info("WebSocket connection opened: " + connection.getConnectionId());
    }

    @Override
    public void onMessage(WebSocketConnection connection, String message) {
        try {
            logger.fine("Received text message: " + message);
            connection.sendMessage("Echo: " + message);
        } catch (IOException e) {
            logger.log(java.util.logging.Level.WARNING, "Error sending echo message", e);
        }
    }

    @Override
    public void onMessage(WebSocketConnection connection, byte[] message) {
        try {
            logger.fine("Received binary message, length: " + message.length);
            connection.sendMessage(message);
        } catch (IOException e) {
            logger.log(java.util.logging.Level.WARNING, "Error sending echo message", e);
        }
    }

    @Override
    public void onError(WebSocketConnection connection, Throwable error) {
        logger.log(java.util.logging.Level.WARNING, "WebSocket error: " + error.getMessage(), error);
    }

    @Override
    public void onClose(WebSocketConnection connection, int statusCode, String reason) {
        logger.info("WebSocket connection closed: " + connection.getConnectionId() +
                   ", status=" + statusCode + ", reason=" + reason);
    }
}
