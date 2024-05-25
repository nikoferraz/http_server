package HTTPServer.tests;

import HTTPServer.*;
import java.io.File;
import java.util.logging.Logger;

/**
 * Example usage of Server-Sent Events (SSE) support.
 *
 * This demonstrates how to:
 * 1. Set up SSE handlers with the HTTP server
 * 2. Use example handlers (clock, stock ticker, notifications)
 * 3. Create custom SSE handlers
 * 4. Broadcast events to clients
 */
public class SSEExampleUsage {

    public static void main(String[] args) {
        System.out.println("Server-Sent Events (SSE) Example Usage\n");
        System.out.println("=".repeat(60) + "\n");

        exampleBasicSetup();
        System.out.println();
        exampleClockHandler();
        System.out.println();
        exampleStockTickerHandler();
        System.out.println();
        exampleNotificationHandler();
        System.out.println();
        exampleCustomHandler();
        System.out.println();
        exampleBroadcasting();
    }

    /**
     * Example 1: Basic SSE setup with ProcessRequest
     */
    private static void exampleBasicSetup() {
        System.out.println("Example 1: Basic SSE Setup");
        System.out.println("-".repeat(60));

        System.out.println("""
            // In your HTTP server initialization:
            ProcessRequest handler = new ProcessRequest(webroot, socket, logger, config);

            // Create and set SSE handler
            SSEHandler sseHandler = new SSEClockHandler();
            handler.setSSEHandler(sseHandler);

            // SSE requests to /events/* paths will be handled automatically
            // Client: GET /events/clock HTTP/1.1
            //         Accept: text/event-stream
            """);
    }

    /**
     * Example 2: Using the clock handler
     */
    private static void exampleClockHandler() {
        System.out.println("Example 2: Clock Handler");
        System.out.println("-".repeat(60));

        System.out.println("""
            // Create clock handler
            SSEClockHandler clockHandler = new SSEClockHandler();

            // Associate with request handler
            ProcessRequest handler = new ProcessRequest(webroot, socket, logger);
            handler.setSSEHandler(clockHandler);

            // Features:
            // - Sends server time every second
            // - Event type: "time"
            // - Data format: {"timestamp":"2025-11-05T14:30:45Z","unixTime":1730903445000}
            // - Automatic connection tracking

            // Monitor connections
            System.out.println("Connected clients: " + clockHandler.getConnectionCount());

            // Graceful shutdown
            clockHandler.shutdown();
            """);
    }

    /**
     * Example 3: Using the stock ticker handler
     */
    private static void exampleStockTickerHandler() {
        System.out.println("Example 3: Stock Ticker Handler");
        System.out.println("-".repeat(60));

        System.out.println("""
            // Create stock ticker handler
            SSEStockTickerHandler stockHandler = new SSEStockTickerHandler();

            // Set as SSE handler
            ProcessRequest handler = new ProcessRequest(webroot, socket, logger);
            handler.setSSEHandler(stockHandler);

            // Features:
            // - Tracks 5 stocks: AAPL, GOOGL, MSFT, AMZN, TSLA
            // - Updates every 2 seconds
            // - Event type: "price_update" for updates
            // - Event type: "price_initial" for new connections
            // - Includes stock symbol, price, and change percentage

            // Get current prices
            var prices = stockHandler.getCurrentPrices();
            prices.forEach((symbol, price) ->
                System.out.println(symbol + ": $" + price)
            );

            // Shutdown
            stockHandler.shutdown();
            """);
    }

    /**
     * Example 4: Using the notification handler
     */
    private static void exampleNotificationHandler() {
        System.out.println("Example 4: Notification Handler");
        System.out.println("-".repeat(60));

        System.out.println("""
            // Create notification handler
            SSENotificationHandler notifHandler = new SSENotificationHandler(1000);

            // Set as SSE handler
            ProcessRequest handler = new ProcessRequest(webroot, socket, logger);
            handler.setSSEHandler(notifHandler);

            // Create and broadcast notifications
            SSENotificationHandler.Notification notif = new SSENotificationHandler.Notification(
                "System Alert",
                "Maintenance window scheduled for 2:00 AM",
                SSENotificationHandler.Notification.Level.WARNING
            );

            int recipientsCount = notifHandler.broadcast(notif);
            System.out.println("Sent to " + recipientsCount + " clients");

            // Get notification history
            var history = notifHandler.getNotificationHistory(10);
            history.forEach(n -> System.out.println(n.title));

            // Clear history
            notifHandler.clearHistory();
            """);
    }

    /**
     * Example 5: Creating a custom SSE handler
     */
    private static void exampleCustomHandler() {
        System.out.println("Example 5: Custom SSE Handler");
        System.out.println("-".repeat(60));

        System.out.println("""
            // Implement custom handler
            public class MySensorHandler implements SSEHandler {
                private final CopyOnWriteArrayList<SSEConnection> clients = new CopyOnWriteArrayList<>();

                @Override
                public void onOpen(SSEConnection connection) {
                    clients.add(connection);
                    System.out.println("Sensor client connected");
                }

                @Override
                public void onClose(SSEConnection connection) {
                    clients.remove(connection);
                    System.out.println("Sensor client disconnected");
                }

                @Override
                public void onError(SSEConnection connection, Throwable error) {
                    System.err.println("Error: " + error.getMessage());
                }

                // Publish sensor readings
                public void publishSensorReading(String sensorId, double value) {
                    String data = String.format(
                        "{\\"sensor\\":\\"%s\\",\\"value\\":%.2f}",
                        sensorId, value
                    );
                    SSEEvent event = new SSEEvent(data, "sensor_reading", sensorId);

                    for (SSEConnection client : clients) {
                        if (client.isOpen()) {
                            client.sendEvent(event);
                        }
                    }
                }
            }

            // Use custom handler
            MySensorHandler sensorHandler = new MySensorHandler();
            handler.setSSEHandler(sensorHandler);
            """);
    }

    /**
     * Example 6: Broadcasting with SSEManager
     */
    private static void exampleBroadcasting() {
        System.out.println("Example 6: Broadcasting with SSEManager");
        System.out.println("-".repeat(60));

        System.out.println("""
            // Get the SSE manager singleton
            SSEManager manager = SSEManager.getInstance();

            // Broadcast to a specific topic
            SSEEvent event = new SSEEvent(
                "{\\"message\\":\\"Hello, everyone!\\"}",
                "announcement",
                "1001"
            );

            int recipientCount = manager.broadcast("/events/announcements", event);
            System.out.println("Sent to " + recipientCount + " clients on topic");

            // Broadcast to multiple topics
            var topicList = java.util.List.of(
                "/events/news",
                "/events/alerts",
                "/events/notifications"
            );
            int totalRecipients = manager.broadcastToTopics(topicList, event);
            System.out.println("Sent to " + totalRecipients + " total clients");

            // Get statistics
            var stats = manager.getStatistics();
            System.out.println("Active connections: " + stats.get("total_connections"));
            System.out.println("Active topics: " + stats.get("active_topics"));

            // Get connections for a topic
            var connections = manager.getConnections("/events/news");
            System.out.println("Connections on /events/news: " + connections.size());

            // Graceful shutdown
            manager.closeAllConnections();
            """);
    }
}
