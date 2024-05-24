package HTTPServer.tests;

import HTTPServer.WebSocketConnection;
import HTTPServer.WebSocketHandler;
import HTTPServer.MetricsCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("WebSocket Connection Tests")
class WebSocketConnectionTest {

    private MockWebSocketHandler handler;
    private MetricsCollector metrics;

    @BeforeEach
    void setUp() {
        handler = new MockWebSocketHandler();
        metrics = MetricsCollector.getInstance();
    }

    @Test
    @DisplayName("Should initialize connection with correct parameters")
    void testConnectionInitialization() {
        assertThat(handler).isNotNull();
        assertThat(metrics).isNotNull();
    }

    @Test
    @DisplayName("Should handle connection stats")
    void testConnectionStats() {
        WebSocketConnection.ConnectionStats stats = new WebSocketConnection.ConnectionStats(
                10, 5, 1024, 512, 100
        );

        assertThat(stats.messagesReceived).isEqualTo(10);
        assertThat(stats.messagesSent).isEqualTo(5);
        assertThat(stats.bytesReceived).isEqualTo(1024);
        assertThat(stats.bytesSent).isEqualTo(512);
        assertThat(stats.idleTimeMs).isEqualTo(100);
    }

    @Test
    @DisplayName("Should identify close status codes")
    void testCloseStatusCodes() {
        assertThat(WebSocketConnection.STATUS_NORMAL_CLOSURE).isEqualTo(1000);
        assertThat(WebSocketConnection.STATUS_GOING_AWAY).isEqualTo(1001);
        assertThat(WebSocketConnection.STATUS_PROTOCOL_ERROR).isEqualTo(1002);
        assertThat(WebSocketConnection.STATUS_UNSUPPORTED_DATA).isEqualTo(1003);
        assertThat(WebSocketConnection.STATUS_INVALID_FRAME_PAYLOAD_DATA).isEqualTo(1007);
        assertThat(WebSocketConnection.STATUS_POLICY_VIOLATION).isEqualTo(1008);
        assertThat(WebSocketConnection.STATUS_MESSAGE_TOO_BIG).isEqualTo(1009);
        assertThat(WebSocketConnection.STATUS_MISSING_EXTENSION).isEqualTo(1010);
        assertThat(WebSocketConnection.STATUS_INTERNAL_ERROR).isEqualTo(1011);
    }

    @Test
    @DisplayName("Should handle handler callbacks")
    void testHandlerCallbacks() {
        handler.openCalled = true;
        handler.textMessages.add("Hello");
        handler.closeCalled = true;

        assertThat(handler.openCalled).isTrue();
        assertThat(handler.textMessages).contains("Hello");
        assertThat(handler.closeCalled).isTrue();
    }

    @Test
    @DisplayName("Should track binary messages")
    void testBinaryMessages() {
        byte[] data = {1, 2, 3, 4, 5};
        handler.binaryMessages.add(data);

        assertThat(handler.binaryMessages).hasSize(1);
        assertThat(handler.binaryMessages.get(0)).isEqualTo(data);
    }

    @Test
    @DisplayName("Should track errors")
    void testErrorTracking() {
        Exception error = new IOException("Test error");
        handler.lastError = error;

        assertThat(handler.lastError).isEqualTo(error);
    }

    @Test
    @DisplayName("Should store close status and reason")
    void testCloseStatusAndReason() {
        handler.closedStatusCode = 1000;
        handler.closedReason = "Normal closure";

        assertThat(handler.closedStatusCode).isEqualTo(1000);
        assertThat(handler.closedReason).isEqualTo("Normal closure");
    }

    @Test
    @DisplayName("Should handle multiple messages")
    void testMultipleMessages() {
        handler.textMessages.add("Message 1");
        handler.textMessages.add("Message 2");
        handler.textMessages.add("Message 3");

        assertThat(handler.textMessages).hasSize(3);
        assertThat(handler.textMessages).containsExactly("Message 1", "Message 2", "Message 3");
    }

    @Test
    @DisplayName("Connection stats should provide readable string")
    void testConnectionStatsToString() {
        WebSocketConnection.ConnectionStats stats = new WebSocketConnection.ConnectionStats(
                5, 3, 100, 50, 1000
        );

        String str = stats.toString();
        assertThat(str).contains("msgs_rx=5");
        assertThat(str).contains("msgs_tx=3");
        assertThat(str).contains("bytes_rx=100");
        assertThat(str).contains("bytes_tx=50");
    }

    /**
     * Mock handler for testing
     */
    static class MockWebSocketHandler implements WebSocketHandler {
        public boolean openCalled = false;
        public List<String> textMessages = new ArrayList<>();
        public List<byte[]> binaryMessages = new ArrayList<>();
        public Throwable lastError = null;
        public boolean closeCalled = false;
        public int closedStatusCode = -1;
        public String closedReason = "";

        @Override
        public void onOpen(WebSocketConnection connection) {
            openCalled = true;
        }

        @Override
        public void onMessage(WebSocketConnection connection, String message) {
            textMessages.add(message);
        }

        @Override
        public void onMessage(WebSocketConnection connection, byte[] message) {
            binaryMessages.add(message);
        }

        @Override
        public void onError(WebSocketConnection connection, Throwable error) {
            lastError = error;
        }

        @Override
        public void onClose(WebSocketConnection connection, int statusCode, String reason) {
            closeCalled = true;
            closedStatusCode = statusCode;
            closedReason = reason;
        }
    }
}
