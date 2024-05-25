package HTTPServer;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Example SSE handler that sends server time events.
 *
 * Sends the current server time every second to all connected clients.
 * Format: ISO 8601 timestamp with timezone.
 */
public class SSEClockHandler implements SSEHandler {

    private static final Logger logger = Logger.getLogger(SSEClockHandler.class.getName());
    private static final DateTimeFormatter formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private final CopyOnWriteArrayList<SSEConnection> connections = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;
    private Thread timerThread;

    public SSEClockHandler() {
        startClockThread();
    }

    private void startClockThread() {
        timerThread = Thread.ofVirtual().start(() -> {
            try {
                while (running) {
                    Thread.sleep(1000); // Send time every second

                    ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
                    String timestamp = now.format(formatter);
                    String data = "{\"timestamp\":\"" + timestamp + "\",\"unixTime\":" + now.toInstant().toEpochMilli() + "}";

                    SSEEvent event = new SSEEvent(data, "time");

                    // Send to all connected clients
                    for (SSEConnection connection : connections) {
                        if (connection.isOpen()) {
                            try {
                                connection.sendEvent(event);
                            } catch (IllegalStateException e) {
                                logger.log(Level.FINE, "Connection closed, skipping time event");
                            }
                        }
                    }

                    // Clean up closed connections
                    connections.removeIf(conn -> !conn.isOpen());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.fine("Clock thread interrupted");
            }
        });
    }

    @Override
    public void onOpen(SSEConnection connection) {
        connections.add(connection);
        logger.fine("Clock client connected from " + connection.getClientIp() +
                   ", total clients: " + connections.size());
    }

    @Override
    public void onClose(SSEConnection connection) {
        connections.remove(connection);
        logger.fine("Clock client disconnected, remaining clients: " + connections.size());
    }

    @Override
    public void onError(SSEConnection connection, Throwable error) {
        logger.log(Level.WARNING, "Clock handler error for " + connection.getClientIp(), error);
    }

    public void shutdown() {
        running = false;
        if (timerThread != null) {
            timerThread.interrupt();
        }
    }

    public int getConnectionCount() {
        return connections.size();
    }
}
