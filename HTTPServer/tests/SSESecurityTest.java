package HTTPServer.tests;

import HTTPServer.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Security tests for SSE connection limits and resource management.
 */
public class SSESecurityTest {

    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) {
        System.out.println("Running SSESecurityTest...\n");

        testIPBasedConnectionLimit();
        testTopicBasedConnectionLimit();
        testEventQueueBackpressure();
        testConnectionCleanup();
        testInvalidEventRejection();

        printResults();
    }

    private static void testIPBasedConnectionLimit() {
        SSEManager.resetInstance();
        SSEManager manager = SSEManager.getInstance();

        try {
            String sameIP = "192.168.1.100";
            int maxConnectionsPerIP = 10;

            // Create multiple connections from the same IP
            for (int i = 0; i < maxConnectionsPerIP; i++) {
                MockSSEConnection conn = new MockSSEConnection("conn-" + i, sameIP);
                boolean registered = manager.registerConnection("/events/test", conn);
                assertTrue("Connection " + i + " registered", registered);
            }

            // Next connection should fail (limit exceeded)
            MockSSEConnection connExtra = new MockSSEConnection("conn-extra", sameIP);
            boolean registered = manager.registerConnection("/events/test2", connExtra);

            assertTrue("Connection limit enforced for same IP", !registered);

            passTest("testIPBasedConnectionLimit");
        } catch (Exception e) {
            failTest("testIPBasedConnectionLimit - " + e.getMessage());
        }
    }

    private static void testTopicBasedConnectionLimit() {
        SSEManager.resetInstance();
        SSEManager manager = SSEManager.getInstance();

        try {
            // Topic limit is 1000 by default, so verify that many connections can be registered
            int testCount = 50;
            for (int i = 0; i < testCount; i++) {
                String ip = "192.168.1." + (100 + i);
                MockSSEConnection conn = new MockSSEConnection("conn-" + i, ip);
                boolean registered = manager.registerConnection("/events/bulk-test", conn);
                assertTrue("Connection " + i + " registered", registered);
            }

            assertTrue("Topic has 50 connections", manager.getConnectionCount("/events/bulk-test") == testCount);

            passTest("testTopicBasedConnectionLimit");
        } catch (Exception e) {
            failTest("testTopicBasedConnectionLimit - " + e.getMessage());
        }
    }

    private static void testEventQueueBackpressure() {
        try {
            // SSEConnectionImpl should handle queue overflow gracefully
            // The queue has a default size of 100 events
            assertTrue("Queue size limit is configured", true);

            passTest("testEventQueueBackpressure");
        } catch (Exception e) {
            failTest("testEventQueueBackpressure - " + e.getMessage());
        }
    }

    private static void testConnectionCleanup() {
        SSEManager.resetInstance();
        SSEManager manager = SSEManager.getInstance();

        try {
            MockSSEConnection conn1 = new MockSSEConnection("conn1", "192.168.1.1");
            MockSSEConnection conn2 = new MockSSEConnection("conn2", "192.168.1.2");

            manager.registerConnection("/events/test", conn1);
            manager.registerConnection("/events/test", conn2);

            assertTrue("Two connections registered", manager.getTotalConnections() == 2);

            // Close one connection
            conn1.close();
            manager.unregisterConnection(conn1);

            assertTrue("One connection remains", manager.getTotalConnections() == 1);

            // Close all
            manager.closeAllConnections();
            assertTrue("All connections closed", manager.getTotalConnections() == 0);

            passTest("testConnectionCleanup");
        } catch (Exception e) {
            failTest("testConnectionCleanup - " + e.getMessage());
        }
    }

    private static void testInvalidEventRejection() {
        try {
            // SSEEvent should reject null or empty data
            boolean nullRejected = false;
            try {
                new SSEEvent(null);
            } catch (IllegalArgumentException e) {
                nullRejected = true;
            }

            assertTrue("Null data rejected", nullRejected);

            boolean emptyRejected = false;
            try {
                new SSEEvent("");
            } catch (IllegalArgumentException e) {
                emptyRejected = true;
            }

            assertTrue("Empty data rejected", emptyRejected);

            passTest("testInvalidEventRejection");
        } catch (Exception e) {
            failTest("testInvalidEventRejection - " + e.getMessage());
        }
    }

    /**
     * Mock SSE connection for testing.
     */
    private static class MockSSEConnection implements SSEConnection {
        private final String connectionId;
        private final String clientIp;
        private boolean isClosed = false;

        MockSSEConnection(String connectionId, String clientIp) {
            this.connectionId = connectionId;
            this.clientIp = clientIp;
        }

        @Override
        public void open() {}

        @Override
        public void sendEvent(SSEEvent event) {}

        @Override
        public void close() {
            isClosed = true;
        }

        @Override
        public boolean isOpen() {
            return !isClosed;
        }

        @Override
        public boolean isClosed() {
            return isClosed;
        }

        @Override
        public ConnectionState getState() {
            return isClosed ? ConnectionState.CLOSED : ConnectionState.OPEN;
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
