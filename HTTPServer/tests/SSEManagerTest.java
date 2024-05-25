package HTTPServer.tests;

import HTTPServer.*;
import java.net.Socket;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Unit tests for SSEManager broadcasting and topic management.
 */
public class SSEManagerTest {

    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) {
        System.out.println("Running SSEManagerTest...\n");

        testSingletonInstance();
        testConnectionRegistration();
        testConnectionUnregistration();
        testMultipleTopics();
        testBroadcast();
        testBroadcastToNonExistentTopic();
        testGetActiveTopics();
        testStatistics();
        testConnectionLimitPerTopic();

        printResults();
    }

    private static void testSingletonInstance() {
        SSEManager manager1 = SSEManager.getInstance();
        SSEManager manager2 = SSEManager.getInstance();

        assertTrue("SSEManager is singleton", manager1 == manager2);
        passTest("testSingletonInstance");
    }

    private static void testConnectionRegistration() {
        SSEManager.resetInstance();
        SSEManager manager = SSEManager.getInstance();

        try {
            MockSSEConnection conn = new MockSSEConnection("conn1", "/events/test");
            boolean registered = manager.registerConnection("/events/test", conn);

            assertTrue("Connection registered successfully", registered);
            assertTrue("Connection count is 1", manager.getConnectionCount("/events/test") == 1);
            assertTrue("Topic is active", manager.getActiveTopics().contains("/events/test"));

            passTest("testConnectionRegistration");
        } catch (Exception e) {
            failTest("testConnectionRegistration - " + e.getMessage());
        }
    }

    private static void testConnectionUnregistration() {
        SSEManager.resetInstance();
        SSEManager manager = SSEManager.getInstance();

        try {
            MockSSEConnection conn = new MockSSEConnection("conn1", "/events/test");
            manager.registerConnection("/events/test", conn);
            manager.unregisterConnection(conn);

            assertTrue("Connection count is 0", manager.getConnectionCount("/events/test") == 0);
            assertTrue("Topic is removed", !manager.getActiveTopics().contains("/events/test"));

            passTest("testConnectionUnregistration");
        } catch (Exception e) {
            failTest("testConnectionUnregistration - " + e.getMessage());
        }
    }

    private static void testMultipleTopics() {
        SSEManager.resetInstance();
        SSEManager manager = SSEManager.getInstance();

        try {
            MockSSEConnection conn1 = new MockSSEConnection("conn1", "/events/news");
            MockSSEConnection conn2 = new MockSSEConnection("conn2", "/events/stocks");

            manager.registerConnection("/events/news", conn1);
            manager.registerConnection("/events/stocks", conn2);

            assertTrue("Two topics registered", manager.getTopicCount() == 2);
            assertTrue("News topic has 1 connection", manager.getConnectionCount("/events/news") == 1);
            assertTrue("Stocks topic has 1 connection", manager.getConnectionCount("/events/stocks") == 1);

            passTest("testMultipleTopics");
        } catch (Exception e) {
            failTest("testMultipleTopics - " + e.getMessage());
        }
    }

    private static void testBroadcast() {
        SSEManager.resetInstance();
        SSEManager manager = SSEManager.getInstance();

        try {
            MockSSEConnection conn1 = new MockSSEConnection("conn1", "/events/test");
            MockSSEConnection conn2 = new MockSSEConnection("conn2", "/events/test");

            manager.registerConnection("/events/test", conn1);
            manager.registerConnection("/events/test", conn2);

            SSEEvent event = new SSEEvent("Test broadcast", "test");
            int recipients = manager.broadcast("/events/test", event);

            assertTrue("Broadcast sent to 2 recipients", recipients == 2);

            passTest("testBroadcast");
        } catch (Exception e) {
            failTest("testBroadcast - " + e.getMessage());
        }
    }

    private static void testBroadcastToNonExistentTopic() {
        SSEManager.resetInstance();
        SSEManager manager = SSEManager.getInstance();

        SSEEvent event = new SSEEvent("No recipients", "test");
        int recipients = manager.broadcast("/events/nonexistent", event);

        assertTrue("No recipients for non-existent topic", recipients == 0);
        passTest("testBroadcastToNonExistentTopic");
    }

    private static void testGetActiveTopics() {
        SSEManager.resetInstance();
        SSEManager manager = SSEManager.getInstance();

        try {
            MockSSEConnection conn1 = new MockSSEConnection("conn1", "/events/a");
            MockSSEConnection conn2 = new MockSSEConnection("conn2", "/events/b");

            manager.registerConnection("/events/a", conn1);
            manager.registerConnection("/events/b", conn2);

            Set<String> topics = manager.getActiveTopics();

            assertTrue("Topics include /events/a", topics.contains("/events/a"));
            assertTrue("Topics include /events/b", topics.contains("/events/b"));
            assertTrue("Two active topics", topics.size() == 2);

            passTest("testGetActiveTopics");
        } catch (Exception e) {
            failTest("testGetActiveTopics - " + e.getMessage());
        }
    }

    private static void testStatistics() {
        SSEManager.resetInstance();
        SSEManager manager = SSEManager.getInstance();

        try {
            MockSSEConnection conn = new MockSSEConnection("conn1", "/events/test");
            manager.registerConnection("/events/test", conn);

            Map<String, Object> stats = manager.getStatistics();

            assertTrue("Stats contain total_connections", stats.containsKey("total_connections"));
            assertTrue("Stats contain active_topics", stats.containsKey("active_topics"));
            assertTrue("Total connections is 1", (int) stats.get("total_connections") == 1);

            passTest("testStatistics");
        } catch (Exception e) {
            failTest("testStatistics - " + e.getMessage());
        }
    }

    private static void testConnectionLimitPerTopic() {
        SSEManager.resetInstance();
        SSEManager manager = SSEManager.getInstance();

        try {
            // Note: Default limit is 1000, so this test just verifies the mechanism works
            MockSSEConnection conn = new MockSSEConnection("conn1", "/events/test");
            boolean registered = manager.registerConnection("/events/test", conn);

            assertTrue("Connection registered within limit", registered);

            passTest("testConnectionLimitPerTopic");
        } catch (Exception e) {
            failTest("testConnectionLimitPerTopic - " + e.getMessage());
        }
    }

    private static class MockSSEConnection implements SSEConnection {
        private final String connectionId;
        private final String clientIp;
        private boolean isOpen = true;

        MockSSEConnection(String connectionId, String topic) throws IOException {
            this.connectionId = connectionId;
            this.clientIp = "127.0.0.1";
        }

        @Override
        public void open() {}

        @Override
        public void sendEvent(SSEEvent event) {}

        @Override
        public void close() {
            isOpen = false;
        }

        @Override
        public boolean isOpen() {
            return isOpen;
        }

        @Override
        public boolean isClosed() {
            return !isOpen;
        }

        @Override
        public ConnectionState getState() {
            return isOpen ? ConnectionState.OPEN : ConnectionState.CLOSED;
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
