package HTTPServer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Provides virtual thread support with automatic fallback to platform threads.
 *
 * Virtual Threads (Java 21+):
 * - Lightweight alternative to platform threads
 * - 1KB memory per thread vs 1MB for platform threads
 * - 10,000+ concurrent threads instead of 20-100
 * - Transparent to application code
 *
 * Fallback:
 * - Java < 21 uses ThreadPoolExecutor with bounded queue
 * - Same concurrent capacity limits as original implementation
 * - Compatible with existing Servlet code
 */
public class VirtualThreadsSupport {

    private static final int THREAD_POOL_SIZE = 20;
    private static final int REQUEST_QUEUE_LIMIT = 100;

    /**
     * Detects if current JVM supports virtual threads.
     * Virtual threads are available in Java 21+.
     *
     * @return true if Executors.newVirtualThreadPerTaskExecutor() is available
     */
    public boolean supportsVirtualThreads() {
        try {
            // Check if newVirtualThreadPerTaskExecutor exists (Java 21+)
            Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Parses Java version from system property.
     * Handles both old (1.8) and new (11, 21) version formats.
     *
     * Examples:
     * - "11.0.5" → 11
     * - "21.0.1" → 21
     * - "1.8.0_291" → 8
     *
     * @return Java major version number (8, 11, 21, etc.)
     */
    public int getJavaVersion() {
        String version = System.getProperty("java.version");

        // Handle old Java format (1.8, 1.7, etc.)
        if (version.startsWith("1.")) {
            version = version.substring(2, 3);
            return Integer.parseInt(version);
        }

        // Handle new Java format (9+)
        int dotIndex = version.indexOf(".");
        if (dotIndex > 0) {
            version = version.substring(0, dotIndex);
        }

        return Integer.parseInt(version);
    }

    /**
     * Creates an ExecutorService using virtual threads if available.
     * Falls back to bounded ThreadPoolExecutor on older Java versions.
     *
     * Virtual Threads (Java 21+):
     * - newVirtualThreadPerTaskExecutor() creates one virtual thread per task
     * - Automatically manages millions of virtual threads
     * - No bounded queue needed (scales to OS limits)
     * - Much better performance than platform thread pools
     *
     * Fallback (Java < 21):
     * - Creates bounded ThreadPoolExecutor (20 threads, 100 queue limit)
     * - Same configuration as original implementation
     * - Ensures backward compatibility
     *
     * @return ExecutorService ready for use
     */
    public ExecutorService createExecutor() {
        if (supportsVirtualThreads()) {
            try {
                ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
                System.out.println("INFO: Virtual threads enabled (Java " + getJavaVersion() + "+)");
                return virtualExecutor;
            } catch (Exception e) {
                // Fallback if method exists but fails
                System.err.println("ERROR: Failed to create virtual thread executor: " + e.getMessage());
                return createFallbackExecutor();
            }
        } else {
            System.out.println("INFO: Virtual threads not supported (Java " + getJavaVersion() + "), using platform threads");
            return createFallbackExecutor();
        }
    }

    /**
     * Creates a bounded ThreadPoolExecutor for Java < 21.
     * Uses the original configuration from Servlet.java.
     *
     * @return bounded ThreadPoolExecutor with queue limit
     */
    private ExecutorService createFallbackExecutor() {
        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(REQUEST_QUEUE_LIMIT);
        return new ThreadPoolExecutor(
            THREAD_POOL_SIZE,
            THREAD_POOL_SIZE,
            0L, TimeUnit.MILLISECONDS,
            queue,
            new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
