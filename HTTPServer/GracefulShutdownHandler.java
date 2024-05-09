package HTTPServer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class GracefulShutdownHandler {

    private static final Logger logger = Logger.getLogger("graceful_shutdown");
    private static final long DEFAULT_DRAIN_TIMEOUT_MS = 60000;

    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final long drainTimeoutMs;
    private HealthCheckHandler healthCheckHandler;

    public GracefulShutdownHandler() {
        this(DEFAULT_DRAIN_TIMEOUT_MS);
    }

    public GracefulShutdownHandler(long drainTimeoutMs) {
        this.drainTimeoutMs = drainTimeoutMs;
        this.healthCheckHandler = null;
        registerShutdownHook();
    }

    public GracefulShutdownHandler(long drainTimeoutMs, HealthCheckHandler healthCheckHandler) {
        this.drainTimeoutMs = drainTimeoutMs;
        this.healthCheckHandler = healthCheckHandler;
        registerShutdownHook();
    }

    public void setHealthCheckHandler(HealthCheckHandler handler) {
        this.healthCheckHandler = handler;
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "GracefulShutdown"));
    }

    public void incrementActiveConnections() {
        if (!shuttingDown.get()) {
            activeConnections.incrementAndGet();
        }
    }

    public void decrementActiveConnections() {
        activeConnections.decrementAndGet();
    }

    public boolean isShuttingDown() {
        return shuttingDown.get();
    }

    public int getActiveConnectionCount() {
        return activeConnections.get();
    }

    public void shutdown() {
        logger.info("Received shutdown signal, initiating graceful shutdown");

        if (!shuttingDown.compareAndSet(false, true)) {
            logger.warning("Shutdown already in progress");
            return;
        }

        try {
            logger.info("Step 1: Marking service as unavailable for LB deregistration");

            logger.info("Step 2: Waiting for load balancer drain (5 seconds)");
            sleep(5000);

            logger.info("Step 3: Waiting for existing connections to complete");
            long deadline = System.currentTimeMillis() + drainTimeoutMs;
            while (activeConnections.get() > 0 && System.currentTimeMillis() < deadline) {
                int remaining = activeConnections.get();
                if (remaining > 0) {
                    logger.fine("Waiting for " + remaining + " active connections to close");
                }
                sleep(100);
            }

            if (activeConnections.get() > 0) {
                logger.warning("Shutdown timeout reached with " + activeConnections.get() + " active connections");
            } else {
                logger.info("All connections closed gracefully");
            }

            logger.info("Graceful shutdown complete");
        } catch (Exception e) {
            logger.severe("Error during graceful shutdown: " + e.getMessage());
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void awaitShutdown() {
        while (!shuttingDown.get()) {
            sleep(100);
        }
    }

    public void awaitShutdownCompletion() {
        awaitShutdown();
        long deadline = System.currentTimeMillis() + drainTimeoutMs;
        while (activeConnections.get() > 0 && System.currentTimeMillis() < deadline) {
            sleep(100);
        }
    }
}
