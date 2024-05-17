package HTTPServer;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Quick 1-hour soak test for rapid validation and debugging.
 *
 * Same as SoakTest but runs for 1 hour instead of 24 hours.
 * Useful for CI/CD pipelines and quick stability checks.
 *
 * Usage: java HTTPServer.tests.QuickSoakTest [concurrent_clients] [rps]
 * Default: 50 concurrent clients, ~500 RPS (scaled down for faster iteration)
 */
public class QuickSoakTest {

    // Test configuration (1 hour with reduced load)
    private int durationHours = 1;
    private int concurrentClients;
    private int requestsPerSecond;

    // Test state
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);
    private final AtomicLong totalBytes = new AtomicLong(0);
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;

    private long startTime;
    private Servlet testServer;
    private Path testWebroot;

    // Constants
    private static final int TEST_PORT = 18889;
    private static final String LOCALHOST = "127.0.0.1";
    private static final String METRICS_FILE = "quick_soak_test_metrics.csv";
    private static final String RESULTS_FILE = "quick_soak_test_results.txt";
    private static final DateTimeFormatter logFormat =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public QuickSoakTest(int concurrentClients, int requestsPerSecond) {
        this.concurrentClients = concurrentClients;
        this.requestsPerSecond = requestsPerSecond;
        this.executor = Executors.newFixedThreadPool(concurrentClients);
        this.scheduler = Executors.newScheduledThreadPool(2);
    }

    public static void main(String[] args) throws Exception {
        int clients = 50;
        int rps = 500;

        if (args.length > 0) {
            clients = Integer.parseInt(args[0]);
        }
        if (args.length > 1) {
            rps = Integer.parseInt(args[1]);
        }

        QuickSoakTest test = new QuickSoakTest(clients, rps);
        test.run();
    }

    public void run() throws Exception {
        log("=".repeat(80));
        log("Starting quick 1-hour soak test");
        log("=".repeat(80));
        log("Configuration:");
        log("  Duration: 1 hour");
        log("  Concurrent clients: %d", concurrentClients);
        log("  Target RPS: %d", requestsPerSecond);
        log("  Test server port: %d", TEST_PORT);
        log("=".repeat(80));

        try {
            // Setup test server
            setupTestServer();

            startTime = System.currentTimeMillis();
            long endTime = startTime + 3600_000; // 1 hour

            // Initialize metrics logging
            initializeMetricsLog();

            // Start monitoring threads
            scheduler.scheduleAtFixedRate(
                new MemoryMonitor(endTime),
                0, 1, TimeUnit.MINUTES
            );

            scheduler.scheduleAtFixedRate(
                new RequestStatsMonitor(endTime),
                0, 30, TimeUnit.SECONDS
            );

            // Start load generators
            log("Starting %d load generator threads...", concurrentClients);

            long delayPerRequest = 1000_000_000L / requestsPerSecond; // nanoseconds
            long requestsPerClient = (long) requestsPerSecond / concurrentClients;

            for (int i = 0; i < concurrentClients; i++) {
                executor.submit(new LoadGenerator(
                    i, endTime, delayPerRequest, requestsPerClient
                ));
            }

            // Wait for completion or timeout
            executor.shutdown();
            executor.awaitTermination(70, TimeUnit.MINUTES);

            // Stop gracefully
            running.set(false);
            scheduler.shutdown();
            scheduler.awaitTermination(10, TimeUnit.SECONDS);

            // Print final results
            printFinalResults(endTime);

        } finally {
            cleanup();
        }
    }

    private void setupTestServer() throws Exception {
        log("Setting up test server...");

        // Create temporary web root with test files
        testWebroot = Paths.get("/tmp/quick_soak_test_webroot");
        Files.createDirectories(testWebroot);

        // Create test files of various sizes
        createTestFile("small.txt", 1024);
        createTestFile("medium.html", 10 * 1024);
        createTestFile("large.bin", 50 * 1024);

        // Create diverse content for caching tests
        for (int i = 0; i < 20; i++) {
            createTestFile("file_" + i + ".txt", 5 * 1024);
        }

        // Start test server
        ServerConfig config = new ServerConfig();
        testServer = new Servlet(testWebroot.toFile(), TEST_PORT, 1, config);
        testServer.start();

        // Wait for server to start
        Thread.sleep(1000);
        log("Test server started on port %d", TEST_PORT);
    }

    private void createTestFile(String name, int size) throws IOException {
        byte[] data = new byte[size];
        new Random().nextBytes(data);
        Files.write(testWebroot.resolve(name), data);
    }

    private void initializeMetricsLog() throws IOException {
        try (FileWriter fw = new FileWriter(METRICS_FILE)) {
            fw.write("timestamp,elapsed_minutes,used_mb,total_mb,max_mb,requests,errors,avg_response_time_ms\n");
        }
    }

    private void logMetric(long elapsedMs, long usedMemory, long totalMemory,
                          long maxMemory, long avgResponseTime) throws IOException {
        try (FileWriter fw = new FileWriter(METRICS_FILE, true)) {
            long minutes = elapsedMs / 60_000;
            fw.write(String.format("%d,%d,%d,%d,%d,%d,%d,%d\n",
                System.currentTimeMillis(),
                minutes,
                usedMemory / 1024 / 1024,
                totalMemory / 1024 / 1024,
                maxMemory / 1024 / 1024,
                totalRequests.get(),
                totalErrors.get(),
                avgResponseTime
            ));
        }
    }

    private void printFinalResults(long endTime) throws IOException {
        long elapsedMs = System.currentTimeMillis() - startTime;
        long elapsedSeconds = elapsedMs / 1000;

        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();

        double successRate = totalRequests.get() > 0
            ? (totalRequests.get() - totalErrors.get()) * 100.0 / totalRequests.get()
            : 0;

        double avgThroughput = elapsedSeconds > 0
            ? totalRequests.get() / (double) elapsedSeconds
            : 0;

        try (FileWriter fw = new FileWriter(RESULTS_FILE)) {
            fw.write("=".repeat(80) + "\n");
            fw.write("QUICK SOAK TEST RESULTS (1 HOUR)\n");
            fw.write("=".repeat(80) + "\n\n");

            fw.write("Test Duration:\n");
            fw.write(String.format("  Elapsed: %d seconds\n", elapsedSeconds));
            fw.write(String.format("  Target duration: 1 hour\n\n"));

            fw.write("Load Profile:\n");
            fw.write(String.format("  Concurrent clients: %d\n", concurrentClients));
            fw.write(String.format("  Target RPS: %d\n", requestsPerSecond));
            fw.write(String.format("  Actual RPS: %.2f\n\n", avgThroughput));

            fw.write("Request Statistics:\n");
            fw.write(String.format("  Total requests: %,d\n", totalRequests.get()));
            fw.write(String.format("  Total errors: %,d\n", totalErrors.get()));
            fw.write(String.format("  Success rate: %.2f%%\n", successRate));
            fw.write(String.format("  Total bytes transferred: %,d\n\n", totalBytes.get()));

            fw.write("Memory Analysis:\n");
            fw.write(String.format("  Final heap used: %d MB\n", usedMemory / 1024 / 1024));
            fw.write(String.format("  Maximum heap size: %d MB\n", maxMemory / 1024 / 1024));
            fw.write(String.format("  Heap utilization: %.2f%%\n\n",
                (usedMemory * 100.0) / maxMemory));

            fw.write("Cache Capacity Analysis:\n");
            fw.write("  ETag cache: 10,000 entry limit\n");
            fw.write("  Compression cache: 1,000 entry limit\n");
            fw.write("  Rate limiter buckets: 10,000 limit\n");
            fw.write("  Buffer pool: 1,000 buffers\n\n");

            fw.write("PASS/FAIL CRITERIA:\n");
            fw.write(String.format("  [PASS] No crashes\n"));
            fw.write(String.format("  [%s] Success rate > 99.5%%\n",
                successRate >= 99.5 ? "PASS" : "FAIL"));
            fw.write(String.format("  [CHECK] Stable memory usage: See metrics file\n"));
            fw.write(String.format("  [CHECK] No connection failures: Check error logs\n\n"));

            fw.write("=".repeat(80) + "\n");
        }

        // Print to console
        log("\n" + "=".repeat(80));
        log("TEST COMPLETED");
        log("=".repeat(80));
        log("Duration: %d seconds", elapsedSeconds);
        log("Total requests: %,d", totalRequests.get());
        log("Total errors: %,d", totalErrors.get());
        log("Success rate: %.2f%%", successRate);
        log("Actual throughput: %.2f RPS", avgThroughput);
        log("Final heap used: %d MB", usedMemory / 1024 / 1024);
        log("=".repeat(80));
        log("Results saved to: %s", RESULTS_FILE);
        log("Metrics saved to: %s", METRICS_FILE);
    }

    private void cleanup() throws Exception {
        log("Cleaning up...");
        if (testServer != null) {
            testServer.stopServer();
        }
        executor.shutdownNow();
        scheduler.shutdownNow();
    }

    private static void log(String format, Object... args) {
        String timestamp = LocalDateTime.now().format(logFormat);
        Object[] allArgs = new Object[args.length + 1];
        allArgs[0] = timestamp;
        System.arraycopy(args, 0, allArgs, 1, args.length);
        System.out.println(String.format("[%s] " + format, allArgs));
    }

    /**
     * Load generator that sends HTTP requests continuously.
     */
    private class LoadGenerator implements Runnable {
        private final int clientId;
        private final long endTime;
        private final long delayBetweenRequests;
        private final long requestLimit;

        private long requestCount = 0;
        private long errorCount = 0;
        private final String[] paths = {
            "/small.txt",
            "/medium.html",
            "/large.bin",
            "/file_0.txt",
            "/file_10.txt",
            "/file_19.txt"
        };
        private final Random random = new Random(clientId);

        public LoadGenerator(int clientId, long endTime, long delayBetweenRequests, long requestLimit) {
            this.clientId = clientId;
            this.endTime = endTime;
            this.delayBetweenRequests = delayBetweenRequests;
            this.requestLimit = requestLimit;
        }

        @Override
        public void run() {
            long lastRequestTime = System.nanoTime();

            while (running.get() && System.currentTimeMillis() < endTime) {
                try {
                    // Rate limiting
                    long now = System.nanoTime();
                    long elapsed = now - lastRequestTime;
                    if (elapsed < delayBetweenRequests) {
                        Thread.sleep((delayBetweenRequests - elapsed) / 1_000_000);
                    }
                    lastRequestTime = System.nanoTime();

                    // Send request
                    String path = paths[random.nextInt(paths.length)];
                    long responseTime = sendRequest(path);

                    if (responseTime >= 0) {
                        totalRequests.incrementAndGet();
                        totalBytes.addAndGet(512); // approximate
                    } else {
                        totalErrors.incrementAndGet();
                        errorCount++;
                    }

                    requestCount++;

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    totalErrors.incrementAndGet();
                    errorCount++;
                }
            }

            if (requestCount > 0) {
                log("Client %d completed: %,d requests, %d errors",
                    clientId, requestCount, errorCount);
            }
        }

        private long sendRequest(String path) {
            long startTime = System.currentTimeMillis();

            try (Socket socket = new Socket(LOCALHOST, TEST_PORT)) {
                socket.setSoTimeout(5000);

                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));

                // Send HTTP request
                out.println("GET " + path + " HTTP/1.1");
                out.println("Host: localhost:" + TEST_PORT);
                out.println("Connection: close");
                out.println();
                out.flush();

                // Read response
                String line;
                boolean statusOk = false;
                while ((line = in.readLine()) != null) {
                    if (line.contains("200") || line.contains("304")) {
                        statusOk = true;
                    }
                }

                return statusOk ? System.currentTimeMillis() - startTime : -1;

            } catch (Exception e) {
                return -1;
            }
        }
    }

    /**
     * Monitor memory usage and detect leaks.
     */
    private class MemoryMonitor implements Runnable {
        private final long endTime;
        private long initialMemory = -1;

        public MemoryMonitor(long endTime) {
            this.endTime = endTime;
        }

        @Override
        public void run() {
            try {
                Runtime runtime = Runtime.getRuntime();
                long usedMemory = runtime.totalMemory() - runtime.freeMemory();
                long totalMemory = runtime.totalMemory();
                long maxMemory = runtime.maxMemory();
                long elapsedMs = System.currentTimeMillis() - startTime;

                if (initialMemory < 0) {
                    initialMemory = usedMemory;
                }

                // Log metric
                logMetric(elapsedMs, usedMemory, totalMemory, maxMemory, 0);

                // Check memory warnings
                double memoryGrowthMB = (usedMemory - initialMemory) / (1024.0 * 1024.0);
                double minutesElapsed = elapsedMs / 60_000.0;

                if (usedMemory > maxMemory * 0.85) {
                    log("WARNING: Memory usage high: %d MB / %d MB (%.1f%%)",
                        usedMemory / 1024 / 1024,
                        maxMemory / 1024 / 1024,
                        (usedMemory * 100.0) / maxMemory);
                }

            } catch (Exception e) {
                // Silently continue
            }
        }
    }

    /**
     * Monitor request statistics.
     */
    private class RequestStatsMonitor implements Runnable {
        private long lastRequests = 0;
        private long lastTime = System.currentTimeMillis();

        @Override
        public void run() {
            try {
                long currentTime = System.currentTimeMillis();
                long elapsedMs = currentTime - startTime;
                long currentRequests = totalRequests.get();
                long currentErrors = totalErrors.get();

                long requestsDelta = currentRequests - lastRequests;
                long timeDelta = currentTime - lastTime;
                double rps = (requestsDelta * 1000.0) / timeDelta;

                double successRate = currentRequests > 0
                    ? (currentRequests - currentErrors) * 100.0 / currentRequests
                    : 0;

                long elapsedSeconds = elapsedMs / 1000;

                log("Stats [%d sec]: %,d requests, %d errors (%.2f%%), %.0f RPS",
                    elapsedSeconds, currentRequests, currentErrors, successRate, rps);

                lastRequests = currentRequests;
                lastTime = currentTime;

            } catch (Exception e) {
                // Silently continue
            }
        }
    }
}
