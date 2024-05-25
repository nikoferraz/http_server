package HTTPServer;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Example SSE handler for broadcasting notifications.
 *
 * Applications can send notifications through this handler to all connected clients.
 * Useful for real-time alerts, updates, and announcements.
 */
public class SSENotificationHandler implements SSEHandler {

    private static final Logger logger = Logger.getLogger(SSENotificationHandler.class.getName());

    public static class Notification {
        public enum Level {
            INFO, WARNING, ERROR, SUCCESS
        }

        public final String id;
        public final String title;
        public final String message;
        public final Level level;
        public final long timestamp;
        public final Map<String, String> metadata;

        public Notification(String title, String message, Level level) {
            this(UUID.randomUUID().toString(), title, message, level, new HashMap<>());
        }

        public Notification(String id, String title, String message, Level level, Map<String, String> metadata) {
            this.id = id;
            this.title = title;
            this.message = message;
            this.level = level;
            this.timestamp = Instant.now().toEpochMilli();
            this.metadata = metadata != null ? metadata : new HashMap<>();
        }

        public String toJSON() {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"id\":\"").append(escapeJson(id)).append("\",");
            sb.append("\"title\":\"").append(escapeJson(title)).append("\",");
            sb.append("\"message\":\"").append(escapeJson(message)).append("\",");
            sb.append("\"level\":\"").append(level.name()).append("\",");
            sb.append("\"timestamp\":").append(timestamp);

            if (!metadata.isEmpty()) {
                sb.append(",\"metadata\":{");
                boolean first = true;
                for (Map.Entry<String, String> entry : metadata.entrySet()) {
                    if (!first) sb.append(",");
                    sb.append("\"").append(escapeJson(entry.getKey())).append("\":\"")
                      .append(escapeJson(entry.getValue())).append("\"");
                    first = false;
                }
                sb.append("}");
            }

            sb.append("}");
            return sb.toString();
        }

        private static String escapeJson(String str) {
            if (str == null) return "";
            return str.replace("\"", "\\\"")
                      .replace("\\", "\\\\")
                      .replace("\n", "\\n")
                      .replace("\r", "\\r")
                      .replace("\t", "\\t");
        }
    }

    private final CopyOnWriteArrayList<SSEConnection> connections = new CopyOnWriteArrayList<>();
    private final Deque<Notification> notificationHistory = new LinkedList<>();
    private final Object historyLock = new Object();
    private final int maxHistorySize;
    private long eventIdCounter = 0;

    public SSENotificationHandler() {
        this(1000); // Default history size
    }

    public SSENotificationHandler(int maxHistorySize) {
        this.maxHistorySize = maxHistorySize;
    }

    /**
     * Broadcasts a notification to all connected clients.
     *
     * @param notification the notification to broadcast
     * @return number of clients that received the notification
     */
    public int broadcast(Notification notification) {
        if (notification == null) {
            throw new IllegalArgumentException("Notification cannot be null");
        }

        // Store in history
        synchronized (historyLock) {
            notificationHistory.addFirst(notification);
            while (notificationHistory.size() > maxHistorySize) {
                notificationHistory.removeLast();
            }
        }

        // Send to all connected clients
        String eventData = notification.toJSON();
        SSEEvent event = new SSEEvent(
            eventData,
            "notification",
            notification.id
        );

        int sent = 0;
        for (SSEConnection connection : connections) {
            if (connection.isOpen()) {
                try {
                    connection.sendEvent(event);
                    sent++;
                } catch (IllegalStateException e) {
                    logger.log(Level.FINE, "Connection closed, skipping notification");
                }
            }
        }

        // Clean up closed connections
        connections.removeIf(conn -> !conn.isOpen());

        logger.fine("Notification broadcast to " + sent + " clients: " + notification.title);
        return sent;
    }

    /**
     * Sends notification history to a newly connected client.
     *
     * @param connection the newly connected client
     */
    private void sendNotificationHistory(SSEConnection connection) {
        // Send most recent notifications in reverse order (oldest first)
        List<Notification> recent;
        synchronized (historyLock) {
            recent = new ArrayList<>(notificationHistory);
        }
        Collections.reverse(recent);

        for (Notification notification : recent) {
            String eventData = notification.toJSON();
            SSEEvent event = new SSEEvent(
                eventData,
                "notification_history",
                notification.id
            );
            try {
                connection.sendEvent(event);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error sending notification history", e);
                break;
            }
        }
    }

    @Override
    public void onOpen(SSEConnection connection) {
        connections.add(connection);
        logger.fine("Notification client connected from " + connection.getClientIp() +
                   ", total clients: " + connections.size());

        // Send history to new client
        sendNotificationHistory(connection);
    }

    @Override
    public void onClose(SSEConnection connection) {
        connections.remove(connection);
        logger.fine("Notification client disconnected, remaining clients: " + connections.size());
    }

    @Override
    public void onError(SSEConnection connection, Throwable error) {
        logger.log(Level.WARNING, "Notification handler error for " + connection.getClientIp(), error);
    }

    public int getConnectionCount() {
        return connections.size();
    }

    public List<Notification> getNotificationHistory(int limit) {
        synchronized (historyLock) {
            List<Notification> history = new ArrayList<>(notificationHistory);
            if (history.size() > limit) {
                return history.subList(0, limit);
            }
            return history;
        }
    }

    public void clearHistory() {
        synchronized (historyLock) {
            notificationHistory.clear();
        }
    }
}
