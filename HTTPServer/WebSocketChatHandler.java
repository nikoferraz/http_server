package HTTPServer;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * WebSocket chat room handler - broadcasts messages to all connected clients.
 *
 * Example usage:
 *   WebSocketHandler chatHandler = new WebSocketChatHandler("general");
 *   // Pass to ProcessRequest via setWebSocketHandler()
 *
 * All text messages are broadcast to all connected clients.
 * When a client connects or disconnects, a notification is sent to all others.
 */
public class WebSocketChatHandler implements WebSocketHandler {

    private static final Logger logger = Logger.getLogger(WebSocketChatHandler.class.getName());
    private final String roomName;
    private final Set<WebSocketConnection> clients = Collections.synchronizedSet(new HashSet<>());

    public WebSocketChatHandler(String roomName) {
        this.roomName = roomName;
    }

    @Override
    public void onOpen(WebSocketConnection connection) {
        clients.add(connection);
        logger.info("Client joined " + roomName + ": " + connection.getConnectionId() +
                   " (total: " + clients.size() + ")");

        // Notify all clients that someone joined
        String joinMessage = connection.getConnectionId() + " joined the chat";
        broadcastMessage(joinMessage, connection);
    }

    @Override
    public void onMessage(WebSocketConnection connection, String message) {
        logger.fine("Chat message from " + connection.getConnectionId() + ": " + message);

        // Format and broadcast message
        String formattedMessage = "[" + connection.getConnectionId() + "]: " + message;
        broadcastMessage(formattedMessage, null);
    }

    @Override
    public void onMessage(WebSocketConnection connection, byte[] message) {
        logger.fine("Received binary message from " + connection.getConnectionId() +
                   ", length: " + message.length);
        // For binary messages, just broadcast to all
        broadcastBinary(message, null);
    }

    @Override
    public void onError(WebSocketConnection connection, Throwable error) {
        logger.log(Level.WARNING, "Chat error for " + connection.getConnectionId(), error);
    }

    @Override
    public void onClose(WebSocketConnection connection, int statusCode, String reason) {
        clients.remove(connection);
        logger.info("Client left " + roomName + ": " + connection.getConnectionId() +
                   " (total: " + clients.size() + ")");

        // Notify all remaining clients
        String leaveMessage = connection.getConnectionId() + " left the chat";
        broadcastMessage(leaveMessage, null);
    }

    /**
     * Broadcasts a text message to all connected clients except the sender.
     */
    private void broadcastMessage(String message, WebSocketConnection sender) {
        for (WebSocketConnection client : clients) {
            if (client != sender) {
                try {
                    client.sendMessage(message);
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Failed to broadcast to " + client.getConnectionId(), e);
                }
            }
        }
    }

    /**
     * Broadcasts a binary message to all connected clients except the sender.
     */
    private void broadcastBinary(byte[] message, WebSocketConnection sender) {
        for (WebSocketConnection client : clients) {
            if (client != sender) {
                try {
                    client.sendMessage(message);
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Failed to broadcast binary to " + client.getConnectionId(), e);
                }
            }
        }
    }

    /**
     * Returns the number of connected clients.
     */
    public int getClientCount() {
        return clients.size();
    }

    /**
     * Returns the room name.
     */
    public String getRoomName() {
        return roomName;
    }
}
