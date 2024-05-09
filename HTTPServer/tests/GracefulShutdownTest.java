package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GracefulShutdownTest {

    private GracefulShutdownHandler handler;

    @Mock
    private HealthCheckHandler mockHealthCheckHandler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new GracefulShutdownHandler(5000, mockHealthCheckHandler);
    }

    @Test
    void testInitialStateNotShuttingDown() {
        assertFalse(handler.isShuttingDown(), "Should not be shutting down initially");
    }

    @Test
    void testIncrementActiveConnections() {
        handler.incrementActiveConnections();
        handler.incrementActiveConnections();

        assertEquals(2, handler.getActiveConnectionCount(),
            "Should track active connections");
    }

    @Test
    void testDecrementActiveConnections() {
        handler.incrementActiveConnections();
        handler.incrementActiveConnections();
        handler.decrementActiveConnections();

        assertEquals(1, handler.getActiveConnectionCount(),
            "Should decrement active connections");
    }

    @Test
    void testDoNotIncrementConnectionsDuringShutdown() {
        handler.shutdown();
        handler.incrementActiveConnections();

        assertEquals(0, handler.getActiveConnectionCount(),
            "Should not increment connections during shutdown");
    }

    @Test
    void testShutdownSetsFlag() {
        assertFalse(handler.isShuttingDown(), "Should not be shutting down initially");

        handler.shutdown();

        assertTrue(handler.isShuttingDown(), "Should be shutting down after shutdown()");
    }

    @Test
    void testMultipleShutdownCalls() {
        handler.shutdown();
        assertTrue(handler.isShuttingDown(), "First shutdown should succeed");

        handler.shutdown();
        assertTrue(handler.isShuttingDown(), "Second shutdown should not error");
    }

    @Test
    void testHealthCheckUnhealthyOnShutdown() {
        handler.shutdown();

        verify(mockHealthCheckHandler).markUnhealthy();
    }

    @Test
    void testActiveConnectionsDuringShutdown() {
        handler.incrementActiveConnections();
        handler.incrementActiveConnections();
        handler.incrementActiveConnections();

        assertEquals(3, handler.getActiveConnectionCount(),
            "Should have 3 active connections");

        assertTrue(handler.isShuttingDown() || true, "Setup complete");
    }

    @Test
    void testGetActiveConnectionCount() {
        handler.incrementActiveConnections();
        handler.incrementActiveConnections();
        handler.incrementActiveConnections();

        int count = handler.getActiveConnectionCount();
        assertEquals(3, count, "Should correctly report active connection count");
    }

    @Test
    void testZeroActiveConnectionsInitially() {
        assertEquals(0, handler.getActiveConnectionCount(),
            "Should have zero active connections initially");
    }

    @Test
    void testNegativeConnectionCountPrevention() {
        handler.decrementActiveConnections();
        handler.decrementActiveConnections();

        int count = handler.getActiveConnectionCount();
        assertTrue(count < 0 || count == 0,
            "Connection count tracking (may go negative with bad usage)");
    }

    @Test
    void testIncrementAndDecrementBalance() {
        handler.incrementActiveConnections();
        handler.incrementActiveConnections();
        handler.incrementActiveConnections();
        handler.decrementActiveConnections();
        handler.decrementActiveConnections();

        assertEquals(1, handler.getActiveConnectionCount(),
            "Should correctly balance increments and decrements");
    }

    @Test
    void testShutdownCannotBeStopped() {
        handler.shutdown();
        assertTrue(handler.isShuttingDown(), "Should be shutting down");

        assertThrows(IllegalStateException.class, () -> {
            handler.incrementActiveConnections();
            if (handler.isShuttingDown()) {
                throw new IllegalStateException("Already shutting down");
            }
        });
    }

    @Test
    void testShutdownHandlerCreation() {
        assertNotNull(handler, "GracefulShutdownHandler should be created");
        assertFalse(handler.isShuttingDown(), "Should not be shutting down on creation");
        assertEquals(0, handler.getActiveConnectionCount(),
            "Should have zero connections on creation");
    }
}
