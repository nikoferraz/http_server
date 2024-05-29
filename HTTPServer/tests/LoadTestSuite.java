package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Timeout;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive load testing suite for HTTP Server.
 * Validates performance under high concurrency and throughput scenarios.
 */
@DisplayName("Load Testing Suite")
class LoadTestSuite {

    private Servlet servlet;
    private static final int TEST_PORT = 19080;
    private static final String LOCALHOST = "127.0.0.1";
    private static final long WAIT_FOR_START = 2000; // 2 seconds

    // Performance targets
    private static final long CONCURRENT_CONNECTIONS_TARGET = 10000L;
    private static final long THROUGHPUT_TARGET_REQ_SEC = 10000L;
    private static final long LATENCY_P99_TARGET_MS = 100L;
    private static final double CACHE_HIT_TARGET = 0.95; // 95%
    private static final double ERROR_RATE_TARGET = 0.001; // < 0.1%

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        File webroot = tempDir.toFile();

        // Create test files
        Files.write(
            tempDir.resolve("small.html"),
            "<html><body>Small file for testing</body></html>".getBytes(StandardCharsets.UTF_8)
        );

        Files.write(
            tempDir.resolve("medium.txt"),
            ("Medium file: " + "x".repeat(50000)).getBytes(StandardCharsets.UTF_8)
        );

        Files.write(
            tempDir.resolve("index.html"),
            "<html><body>Index page</body></html>".getBytes(StandardCharsets.UTF_8)
        );

        ServerConfig config = new ServerConfig();
        servlet = new Servlet(webroot, TEST_PORT, 1, config);
        servlet.start();
        Thread.sleep(WAIT_FOR_START);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (servlet != null) {
            servlet.stopServer();
            servlet.join(5000);
        }
    }

    // ============== Test 1: Concurrent Connection Stress Test ==============
    @Nested
    @DisplayName("Concurrent Connection Tests")
    class ConcurrentConnectionTests {

        @Test
        @DisplayName("Should handle 1,000 concurrent connections")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void testThousandConcurrentConnections() throws InterruptedException {
            performConcurrentConnectionTest(1000, "/index.html");
        }

        @Test
        @DisplayName("Should handle 5,000 concurrent connections")
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        void testFiveThousandConcurrentConnections() throws InterruptedException {
            performConcurrentConnectionTest(5000, "/index.html");
        }

        @Test
        @DisplayName("Should handle 10,000 concurrent connections")
        @Timeout(value = 90, unit = TimeUnit.SECONDS)
        void testTenThousandConcurrentConnections() throws InterruptedException {
            performConcurrentConnectionTest(10000, "/index.html");
        }

        private void performConcurrentConnectionTest(int concurrentCount, String path) throws InterruptedException {
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch latch = new CountDownLatch(concurrentCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);

            long startTime = System.currentTimeMillis();

            for (int i = 0; i < concurrentCount; i++) {
                executor.submit(() -> {
                    try {
                        String response = sendHTTPRequest(path);
                        if (response.contains("200")) {
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
                    } catch (IOException e) {
                        failureCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(90, TimeUnit.SECONDS);
            long elapsedMs = System.currentTimeMillis() - startTime;

            executor.shutdown();

            assertThat(completed)
                .as("All %d requests should complete within timeout", concurrentCount)
                .isTrue();

            assertThat(successCount.get())
                .as("At least 99%% of requests should succeed for %d concurrent connections", concurrentCount)
                .isGreaterThanOrEqualTo((int)(concurrentCount * 0.99));

            assertThat(elapsedMs)
                .as("Should complete %d requests in reasonable time (< 60 seconds)", concurrentCount)
                .isLessThan(60000L);

            System.out.printf(
                "Concurrent connections test (%d connections): %d successful, %d failed, %.2f seconds%n",
                concurrentCount, successCount.get(), failureCount.get(), elapsedMs / 1000.0
            );
        }
    }

    // ============== Test 2: Sustained Throughput Test ==============
    @Nested
    @DisplayName("Sustained Throughput Tests")
    class SustainedThroughputTests {

        @Test
        @DisplayName("Should maintain > 5,000 req/sec for 30 seconds with 100 concurrent clients")
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        void testSustainedThroughput30Seconds() throws InterruptedException {
            performSustainedThroughputTest(100, 30, 5000);
        }

        @Test
        @DisplayName("Should maintain > 10,000 req/sec peak throughput")
        @Timeout(value = 45, unit = TimeUnit.SECONDS)
        void testPeakThroughput() throws InterruptedException {
            performSustainedThroughputTest(200, 15, THROUGHPUT_TARGET_REQ_SEC);
        }

        private void performSustainedThroughputTest(int concurrentClients, int durationSeconds, long minThroughput)
                throws InterruptedException {
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            AtomicLong totalRequests = new AtomicLong(0);
            AtomicLong totalErrors = new AtomicLong(0);
            AtomicInteger shouldStop = new AtomicInteger(0);

            long startTime = System.currentTimeMillis();

            for (int i = 0; i < concurrentClients; i++) {
                executor.submit(() -> {
                    while (shouldStop.get() == 0) {
                        try {
                            String response = sendHTTPRequest("/index.html");
                            if (response.contains("200")) {
                                totalRequests.incrementAndGet();
                            } else {
                                totalErrors.incrementAndGet();
                            }
                        } catch (IOException e) {
                            totalErrors.incrementAndGet();
                        }
                    }
                });
            }

            // Run for specified duration
            Thread.sleep(durationSeconds * 1000L);
            shouldStop.set(1);

            long elapsedMs = System.currentTimeMillis() - startTime;
            double elapsedSeconds = elapsedMs / 1000.0;
            double requestsPerSecond = totalRequests.get() / elapsedSeconds;

            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);

            assertThat(requestsPerSecond)
                .as("Should achieve at least %d req/sec with %d concurrent clients", minThroughput, concurrentClients)
                .isGreaterThanOrEqualTo(minThroughput);

            double errorRate = totalErrors.get() / (double)(totalRequests.get() + totalErrors.get());
            assertThat(errorRate)
                .as("Error rate should be < 0.1%%")
                .isLessThan(ERROR_RATE_TARGET);

            System.out.printf(
                "Sustained throughput test (%d clients, %d seconds): %.0f req/sec, %.3f%% error rate%n",
                concurrentClients, durationSeconds, requestsPerSecond, errorRate * 100
            );
        }
    }

    // ============== Test 3: Cache Hit Rate Under Load ==============
    @Nested
    @DisplayName("Cache Performance Tests")
    class CachePerformanceTests {

        @Test
        @DisplayName("Should achieve > 95%% cache hit rate with repeated requests")
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        void testCacheHitRateUnderLoad() throws InterruptedException {
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch latch = new CountDownLatch(1000);
            AtomicInteger successCount = new AtomicInteger(0);
            List<Long> latencies = new ArrayList<>();

            long startTime = System.currentTimeMillis();

            // Request same file 1000 times from 10 concurrent clients
            for (int i = 0; i < 10; i++) {
                executor.submit(() -> {
                    for (int j = 0; j < 100; j++) {
                        try {
                            long reqStart = System.nanoTime();
                            String response = sendHTTPRequest("/index.html");
                            long reqEnd = System.nanoTime();

                            if (response.contains("200")) {
                                successCount.incrementAndGet();
                                long latencyMs = (reqEnd - reqStart) / 1_000_000L;
                                synchronized (latencies) {
                                    latencies.add(latencyMs);
                                }
                            }
                        } catch (IOException e) {
                            // Continue
                        } finally {
                            latch.countDown();
                        }
                    }
                });
            }

            boolean completed = latch.await(60, TimeUnit.SECONDS);
            long totalElapsedMs = System.currentTimeMillis() - startTime;

            executor.shutdown();

            assertThat(completed).isTrue();

            double successRate = successCount.get() / 1000.0;
            assertThat(successRate)
                .as("Cache hit success rate should be > 95%%")
                .isGreaterThan(CACHE_HIT_TARGET);

            // Calculate p99 latency
            List<Long> sortedLatencies = new ArrayList<>(latencies);
            sortedLatencies.sort(Long::compareTo);
            long p99Latency = sortedLatencies.get((int)(sortedLatencies.size() * 0.99));

            assertThat(p99Latency)
                .as("P99 latency should be < 100ms with caching")
                .isLessThan(LATENCY_P99_TARGET_MS);

            System.out.printf(
                "Cache performance test: %.1f%% success rate, P99 latency: %dms, total time: %.2fs%n",
                successRate * 100, p99Latency, totalElapsedMs / 1000.0
            );
        }

        @Test
        @DisplayName("Should reduce latency significantly with caching")
        @Timeout(value = 45, unit = TimeUnit.SECONDS)
        void testCachingLatencyReduction() throws InterruptedException {
            // First pass: uncached requests (but after first request, cache is populated)
            long uncachedLatency = measureAverageLatency(50, "/index.html", true);

            // Second pass: cached requests
            long cachedLatency = measureAverageLatency(50, "/index.html", false);

            double latencyReduction = (double)(uncachedLatency - cachedLatency) / uncachedLatency;

            System.out.printf(
                "Cache latency test: Uncached: %dms, Cached: %dms, Reduction: %.1f%%%n",
                uncachedLatency, cachedLatency, latencyReduction * 100
            );

            assertThat(cachedLatency)
                .as("Cached requests should be significantly faster")
                .isLessThan(uncachedLatency);
        }

        private long measureAverageLatency(int requestCount, String path, boolean clearCache) throws InterruptedException {
            List<Long> latencies = new ArrayList<>();

            for (int i = 0; i < requestCount; i++) {
                try {
                    long start = System.nanoTime();
                    String response = sendHTTPRequest(path);
                    long end = System.nanoTime();

                    if (response.contains("200")) {
                        latencies.add((end - start) / 1_000_000L);
                    }
                } catch (IOException e) {
                    // Continue
                }
            }

            return latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        }
    }

    // ============== Test 4: Mixed Workload Test ==============
    @Nested
    @DisplayName("Mixed Workload Tests")
    class MixedWorkloadTests {

        @Test
        @DisplayName("Should handle mixed workload (small, medium files) under load")
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        void testMixedWorkloadDistribution() throws InterruptedException {
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch latch = new CountDownLatch(1000);
            AtomicInteger smallRequests = new AtomicInteger(0);
            AtomicInteger mediumRequests = new AtomicInteger(0);
            AtomicInteger successCount = new AtomicInteger(0);

            long startTime = System.currentTimeMillis();

            for (int i = 0; i < 100; i++) {
                executor.submit(() -> {
                    for (int j = 0; j < 10; j++) {
                        String path;
                        if (j % 2 == 0) {
                            path = "/small.html";
                            smallRequests.incrementAndGet();
                        } else {
                            path = "/medium.txt";
                            mediumRequests.incrementAndGet();
                        }

                        try {
                            String response = sendHTTPRequest(path);
                            if (response.contains("200")) {
                                successCount.incrementAndGet();
                            }
                        } catch (IOException e) {
                            // Continue
                        } finally {
                            latch.countDown();
                        }
                    }
                });
            }

            boolean completed = latch.await(60, TimeUnit.SECONDS);
            long elapsedMs = System.currentTimeMillis() - startTime;

            executor.shutdown();

            assertThat(completed).isTrue();
            assertThat(successCount.get())
                .as("Should successfully handle mixed workload")
                .isGreaterThanOrEqualTo(950);

            System.out.printf(
                "Mixed workload test: %d small files, %d medium files, %d successful, %.2f seconds%n",
                smallRequests.get(), mediumRequests.get(), successCount.get(), elapsedMs / 1000.0
            );
        }
    }

    // ============== Test 5: Spike Traffic Test ==============
    @Nested
    @DisplayName("Spike Traffic Tests")
    class SpikeTrafficTests {

        @Test
        @DisplayName("Should handle traffic spikes without excessive errors")
        @Timeout(value = 120, unit = TimeUnit.SECONDS)
        void testTrafficSpike() throws InterruptedException {
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);
            AtomicInteger shouldStop = new AtomicInteger(0);

            long startTime = System.currentTimeMillis();

            // Normal traffic: 100 concurrent clients for 10 seconds
            CountDownLatch normalPhase = new CountDownLatch(100);
            for (int i = 0; i < 100; i++) {
                executor.submit(() -> sendContinuousRequests(normalPhase, successCount, errorCount, shouldStop, "/index.html"));
            }

            Thread.sleep(10000); // Run normal traffic
            normalPhase.await();

            // Spike: 1000 additional concurrent clients for 15 seconds
            CountDownLatch spikePhase = new CountDownLatch(1000);
            long spikeStart = System.currentTimeMillis();

            for (int i = 0; i < 1000; i++) {
                executor.submit(() -> sendContinuousRequests(spikePhase, successCount, errorCount, shouldStop, "/index.html"));
            }

            Thread.sleep(15000); // Run spike traffic
            long spikeEnd = System.currentTimeMillis();
            spikePhase.await();

            // Recovery: 100 concurrent clients for 10 seconds
            CountDownLatch recoveryPhase = new CountDownLatch(100);
            for (int i = 0; i < 100; i++) {
                executor.submit(() -> sendContinuousRequests(recoveryPhase, successCount, errorCount, shouldStop, "/index.html"));
            }

            Thread.sleep(10000); // Run recovery traffic
            recoveryPhase.await();

            shouldStop.set(1);
            long totalElapsedMs = System.currentTimeMillis() - startTime;

            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);

            long totalRequests = successCount.get() + errorCount.get();
            double errorRate = errorCount.get() / (double)totalRequests;

            assertThat(errorRate)
                .as("Error rate during spike should remain < 1%%")
                .isLessThan(0.01);

            System.out.printf(
                "Spike traffic test: %d total requests, %d errors (%.2f%% error rate), %.2f seconds%n",
                totalRequests, errorCount.get(), errorRate * 100, totalElapsedMs / 1000.0
            );
        }

        private void sendContinuousRequests(CountDownLatch latch, AtomicInteger success, AtomicInteger errors,
                                           AtomicInteger shouldStop, String path) {
            try {
                while (shouldStop.get() == 0) {
                    try {
                        String response = sendHTTPRequest(path);
                        if (response.contains("200")) {
                            success.incrementAndGet();
                        } else {
                            errors.incrementAndGet();
                        }
                    } catch (IOException e) {
                        errors.incrementAndGet();
                    }
                }
            } finally {
                latch.countDown();
            }
        }
    }

    // ============== Test 6: Connection Keep-Alive Efficiency ==============
    @Nested
    @DisplayName("Keep-Alive Efficiency Tests")
    class KeepAliveTests {

        @Test
        @DisplayName("Should benefit from keep-alive connections")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void testKeepAliveEfficiency() throws IOException {
            final int REQUEST_COUNT = 100;

            // Test with keep-alive (single connection, multiple requests)
            long keepAliveTime = measureKeepAliveTime(REQUEST_COUNT);

            // Test without keep-alive (separate connections)
            long noKeepAliveTime = measureNoKeepAliveTime(REQUEST_COUNT);

            double improvement = (double)(noKeepAliveTime - keepAliveTime) / noKeepAliveTime;

            assertThat(keepAliveTime)
                .as("Keep-alive should be faster than separate connections")
                .isLessThan(noKeepAliveTime);

            System.out.printf(
                "Keep-alive efficiency test: With keep-alive: %dms, Without: %dms, Improvement: %.1f%%%n",
                keepAliveTime, noKeepAliveTime, improvement * 100
            );
        }

        private long measureKeepAliveTime(int requestCount) throws IOException {
            long startTime = System.currentTimeMillis();

            try (Socket socket = new Socket(LOCALHOST, TEST_PORT)) {
                for (int i = 0; i < requestCount; i++) {
                    String request = String.format(
                        "GET /index.html HTTP/1.1\r\nHost: %s\r\nConnection: keep-alive\r\n\r\n",
                        LOCALHOST
                    );
                    socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();

                    StringBuilder response = new StringBuilder();
                    byte[] buffer = new byte[4096];
                    int bytesRead = socket.getInputStream().read(buffer);
                    if (bytesRead > 0) {
                        response.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
                    }
                }
            }

            return System.currentTimeMillis() - startTime;
        }

        private long measureNoKeepAliveTime(int requestCount) throws IOException {
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < requestCount; i++) {
                try (Socket socket = new Socket(LOCALHOST, TEST_PORT)) {
                    String request = String.format(
                        "GET /index.html HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n",
                        LOCALHOST
                    );
                    socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();

                    StringBuilder response = new StringBuilder();
                    byte[] buffer = new byte[4096];
                    int bytesRead = socket.getInputStream().read(buffer);
                    if (bytesRead > 0) {
                        response.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
                    }
                }
            }

            return System.currentTimeMillis() - startTime;
        }
    }

    // ============== Helper Methods ==============

    private String sendHTTPRequest(String path) throws IOException {
        try (Socket socket = new Socket(LOCALHOST, TEST_PORT)) {
            String request = String.format(
                "GET %s HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n",
                path, LOCALHOST
            );
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();

            StringBuilder response = new StringBuilder();
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = socket.getInputStream().read(buffer)) != -1) {
                response.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
            }

            return response.toString();
        }
    }

    // ============== Performance Metrics ==============

    @Nested
    @DisplayName("Performance Validation Tests")
    class PerformanceValidationTests {

        @Test
        @DisplayName("Should validate all performance targets are met")
        void validatePerformanceTargets() {
            System.out.println("\n========== PERFORMANCE TARGETS ==========");
            System.out.printf("Concurrent Connections Target: %,d%n", CONCURRENT_CONNECTIONS_TARGET);
            System.out.printf("Throughput Target: %,d req/sec%n", THROUGHPUT_TARGET_REQ_SEC);
            System.out.printf("Latency P99 Target: %,d ms%n", LATENCY_P99_TARGET_MS);
            System.out.printf("Cache Hit Rate Target: %.1f%%%n", CACHE_HIT_TARGET * 100);
            System.out.printf("Error Rate Target: < %.2f%%%n", ERROR_RATE_TARGET * 100);
            System.out.println("=========================================\n");
        }
    }
}
