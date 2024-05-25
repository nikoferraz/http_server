package HTTPServer.tests;

import HTTPServer.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Integration tests for SSE end-to-end functionality.
 */
public class SSEIntegrationTest {

    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) {
        System.out.println("Running SSEIntegrationTest...\n");

        testClockHandlerInitialization();
        testStockTickerHandlerInitialization();
        testNotificationHandlerBroadcast();
        testMultipleHandlers();
        testHandlerCallbacks();

        printResults();
    }

    private static void testClockHandlerInitialization() {
        try {
            SSEClockHandler handler = new SSEClockHandler();

            assertTrue("Clock handler created", handler != null);
            assertTrue("Initial connection count is 0", handler.getConnectionCount() == 0);

            handler.shutdown();
            passTest("testClockHandlerInitialization");
        } catch (Exception e) {
            failTest("testClockHandlerInitialization - " + e.getMessage());
        }
    }

    private static void testStockTickerHandlerInitialization() {
        try {
            SSEStockTickerHandler handler = new SSEStockTickerHandler();

            assertTrue("Stock ticker handler created", handler != null);
            assertTrue("Initial connection count is 0", handler.getConnectionCount() == 0);

            var prices = handler.getCurrentPrices();
            assertTrue("Has 5 stocks", prices.size() == 5);
            assertTrue("Contains AAPL", prices.containsKey("AAPL"));
            assertTrue("Contains GOOGL", prices.containsKey("GOOGL"));
            assertTrue("Contains MSFT", prices.containsKey("MSFT"));
            assertTrue("Contains AMZN", prices.containsKey("AMZN"));
            assertTrue("Contains TSLA", prices.containsKey("TSLA"));

            handler.shutdown();
            passTest("testStockTickerHandlerInitialization");
        } catch (Exception e) {
            failTest("testStockTickerHandlerInitialization - " + e.getMessage());
        }
    }

    private static void testNotificationHandlerBroadcast() {
        try {
            SSENotificationHandler handler = new SSENotificationHandler(100);

            // Create a notification
            SSENotificationHandler.Notification notif = new SSENotificationHandler.Notification(
                "Test Alert",
                "This is a test notification",
                SSENotificationHandler.Notification.Level.INFO
            );

            // Broadcast with no listeners (should return 0)
            int sent = handler.broadcast(notif);
            assertTrue("No recipients initially", sent == 0);

            // Check history
            var history = handler.getNotificationHistory(10);
            assertTrue("Notification is in history", history.size() == 1);
            assertTrue("Notification has correct title", history.get(0).title.equals("Test Alert"));

            passTest("testNotificationHandlerBroadcast");
        } catch (Exception e) {
            failTest("testNotificationHandlerBroadcast - " + e.getMessage());
        }
    }

    private static void testMultipleHandlers() {
        try {
            SSEClockHandler clockHandler = new SSEClockHandler();
            SSEStockTickerHandler stockHandler = new SSEStockTickerHandler();
            SSENotificationHandler notifHandler = new SSENotificationHandler();

            assertTrue("All handlers created", clockHandler != null && stockHandler != null && notifHandler != null);

            clockHandler.shutdown();
            stockHandler.shutdown();

            passTest("testMultipleHandlers");
        } catch (Exception e) {
            failTest("testMultipleHandlers - " + e.getMessage());
        }
    }

    private static void testHandlerCallbacks() {
        try {
            AtomicInteger openCount = new AtomicInteger(0);
            AtomicInteger closeCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            SSEHandler testHandler = new SSEHandler() {
                @Override
                public void onOpen(SSEConnection connection) {
                    openCount.incrementAndGet();
                }

                @Override
                public void onClose(SSEConnection connection) {
                    closeCount.incrementAndGet();
                }

                @Override
                public void onError(SSEConnection connection, Throwable error) {
                    errorCount.incrementAndGet();
                }
            };

            // Create mock connection and trigger callbacks
            MockSSEConnection mockConn = new MockSSEConnection("test-id", "127.0.0.1");

            testHandler.onOpen(mockConn);
            assertTrue("onOpen called", openCount.get() == 1);

            testHandler.onClose(mockConn);
            assertTrue("onClose called", closeCount.get() == 1);

            testHandler.onError(mockConn, new Exception("Test error"));
            assertTrue("onError called", errorCount.get() == 1);

            passTest("testHandlerCallbacks");
        } catch (Exception e) {
            failTest("testHandlerCallbacks - " + e.getMessage());
        }
    }

    /**
     * Mock SSE connection for testing handler callbacks.
     */
    private static class MockSSEConnection implements SSEConnection {
        private final String connectionId;
        private final String clientIp;

        MockSSEConnection(String connectionId, String clientIp) {
            this.connectionId = connectionId;
            this.clientIp = clientIp;
        }

        @Override
        public void open() {}

        @Override
        public void sendEvent(SSEEvent event) {}

        @Override
        public void close() {}

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public ConnectionState getState() {
            return ConnectionState.OPEN;
        }

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
            return null;
        }

        @Override
        public long getCreatedAt() {
            return System.currentTimeMillis();
        }

        @Override
        public long getEventsSent() {
            return 0;
        }

        @Override
        public long getBytesTransmitted() {
            return 0;
        }

        @Override
        public int getQueueSize() {
            return 0;
        }

        @Override
        public long getConnectionDuration() {
            return 0;
        }

        @Override
        public long getLastActivityTime() {
            return System.currentTimeMillis();
        }
    }

    private static void assertTrue(String message, boolean condition) {
        if (!condition) {
            System.out.println("  FAIL: " + message);
            testsFailed++;
        }
    }

    private static void passTest(String testName) {
        System.out.println("PASS: " + testName);
        testsPassed++;
    }

    private static void failTest(String testName) {
        System.out.println("FAIL: " + testName);
        testsFailed++;
    }

    private static void printResults() {
        System.out.println("\n" + "=".repeat(50));
        System.out.println("Tests passed: " + testsPassed);
        System.out.println("Tests failed: " + testsFailed);
        System.out.println("Total tests: " + (testsPassed + testsFailed));
        System.out.println("=".repeat(50));
    }
}
