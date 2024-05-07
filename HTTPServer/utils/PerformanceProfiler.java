package HTTPServer;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Standalone performance profiler for HTTP Server
 */
public class PerformanceProfiler {
    
    private static final int TEST_PORT = 19999;
    private static final String LOCALHOST = "127.0.0.1";
    
    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(80));
        System.out.println("HTTP SERVER PERFORMANCE BASELINE REPORT");
        System.out.println("=".repeat(80));
        System.out.println("Date: " + new Date());
        System.out.println("Java Version: " + System.getProperty("java.version"));
        System.out.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        System.out.println("Processors: " + Runtime.getRuntime().availableProcessors());
        System.out.println();
        
        // Create temp directory for tests
        Path tempDir = Files.createTempDirectory("perftest");
        File webroot = tempDir.toFile();
        
        // Create test files
        createTestFiles(tempDir);
        
        // Start server
        ServerConfig config = new ServerConfig();
        Servlet server = new Servlet(webroot, TEST_PORT, 10, config);
        server.start();
        Thread.sleep(2000); // Wait for startup
        
        try {
            // Run tests
            testResourceUsage();
            testThroughput();
            testLatency();
            testComponentPerformance(tempDir);
            testConcurrentLoad();
            
        } finally {
            server.stopServer();
            server.join(5000);
            deleteDirectory(tempDir.toFile());
        }
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("PROFILING COMPLETE");
        System.out.println("=".repeat(80));
    }
    
    private static void createTestFiles(Path dir) throws IOException {
        // Small file (10KB)
        Files.write(dir.resolve("small.html"), generateHTML(10 * 1024).getBytes());
        
        // Medium file (100KB)
        Files.write(dir.resolve("medium.html"), generateHTML(100 * 1024).getBytes());
        
        // Large file (1MB)
        Files.write(dir.resolve("large.html"), generateHTML(1024 * 1024).getBytes());
        
        // JSON file
        Files.write(dir.resolve("data.json"), "{\"test\": \"data\"}".getBytes());
    }
    
    private static String generateHTML(int size) {
        StringBuilder sb = new StringBuilder("<html><body>");
        int remaining = size - sb.length() - 14; // 14 for closing tags
        for (int i = 0; i < remaining / 10; i++) {
            sb.append("testdata" + (i % 10));
        }
        sb.append("</body></html>");
        return sb.toString();
    }
    
    private static void testResourceUsage() {
        System.out.println("\n## RESOURCE USAGE");
        System.out.println("-".repeat(80));
        
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();
        
        System.out.printf("Total Memory: %.2f MB\n", totalMemory / 1024.0 / 1024.0);
        System.out.printf("Used Memory: %.2f MB\n", usedMemory / 1024.0 / 1024.0);
        System.out.printf("Free Memory: %.2f MB\n", freeMemory / 1024.0 / 1024.0);
        System.out.printf("Max Memory: %.2f MB\n", maxMemory / 1024.0 / 1024.0);
    }
    
    private static void testThroughput() throws Exception {
        System.out.println("\n## THROUGHPUT TESTS");
        System.out.println("-".repeat(80));
        
        // Warm-up
        for (int i = 0; i < 100; i++) {
            sendRequest("GET /small.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        }
        
        // Single request throughput
        int numRequests = 1000;
        long start = System.nanoTime();
        for (int i = 0; i < numRequests; i++) {
            sendRequest("GET /small.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        }
        long duration = System.nanoTime() - start;
        double throughput = numRequests / (duration / 1_000_000_000.0);
        
        System.out.printf("Sequential Requests: %.2f req/sec (%d requests in %.2f seconds)\n", 
            throughput, numRequests, duration / 1_000_000_000.0);
    }
    
    private static void testLatency() throws Exception {
        System.out.println("\n## LATENCY TESTS");
        System.out.println("-".repeat(80));
        
        List<Long> latencies = new ArrayList<>();
        int numRequests = 100;
        
        for (int i = 0; i < numRequests; i++) {
            long start = System.nanoTime();
            sendRequest("GET /small.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            long latency = System.nanoTime() - start;
            latencies.add(latency);
        }
        
        Collections.sort(latencies);
        
        long p50 = latencies.get((int)(numRequests * 0.50));
        long p95 = latencies.get((int)(numRequests * 0.95));
        long p99 = latencies.get((int)(numRequests * 0.99));
        long avg = latencies.stream().mapToLong(Long::longValue).sum() / numRequests;
        
        System.out.printf("Average: %.2f ms\n", avg / 1_000_000.0);
        System.out.printf("P50: %.2f ms\n", p50 / 1_000_000.0);
        System.out.printf("P95: %.2f ms\n", p95 / 1_000_000.0);
        System.out.printf("P99: %.2f ms\n", p99 / 1_000_000.0);
    }
    
    private static void testComponentPerformance(Path tempDir) throws Exception {
        System.out.println("\n## COMPONENT PERFORMANCE");
        System.out.println("-".repeat(80));
        
        // RateLimiter
        testRateLimiter();
        
        // CacheManager
        testCacheManager(tempDir);
        
        // CompressionHandler
        testCompressionHandler();
        
        // MetricsCollector
        testMetricsCollector();
    }
    
    private static void testRateLimiter() {
        System.out.println("\n### RateLimiter");
        RateLimiter limiter = new RateLimiter(10000, 60); // 10k req/min
        
        long start = System.nanoTime();
        int numTests = 100000;
        for (int i = 0; i < numTests; i++) {
            limiter.tryAcquire("test-ip-" + (i % 1000));
        }
        long duration = System.nanoTime() - start;
        double throughput = numTests / (duration / 1_000_000_000.0);
        
        System.out.printf("  Throughput: %.0f checks/sec\n", throughput);
        System.out.printf("  Average time per check: %.4f µs\n", (duration / numTests) / 1000.0);
    }
    
    private static void testCacheManager(Path tempDir) throws Exception {
        System.out.println("\n### CacheManager");
        CacheManager cache = new CacheManager();
        
        File smallFile = tempDir.resolve("small.html").toFile();
        File mediumFile = tempDir.resolve("medium.html").toFile();
        File largeFile = tempDir.resolve("large.html").toFile();
        
        // Measure ETag calculation time
        long start = System.nanoTime();
        String etag = cache.generateETag(smallFile);
        long smallTime = System.nanoTime() - start;
        
        start = System.nanoTime();
        etag = cache.generateETag(mediumFile);
        long mediumTime = System.nanoTime() - start;
        
        start = System.nanoTime();
        etag = cache.generateETag(largeFile);
        long largeTime = System.nanoTime() - start;
        
        System.out.printf("  Small file ETag (10KB): %.2f ms\n", smallTime / 1_000_000.0);
        System.out.printf("  Medium file ETag (100KB): %.2f ms\n", mediumTime / 1_000_000.0);
        System.out.printf("  Large file ETag (1MB): %.2f ms\n", largeTime / 1_000_000.0);
    }
    
    private static void testCompressionHandler() {
        System.out.println("\n### CompressionHandler");
        CompressionHandler compressor = new CompressionHandler();
        
        String html = generateHTML(10 * 1024);
        String json = "{\"data\": [" + "1,".repeat(1000) + "1]}";
        
        long start = System.nanoTime();
        byte[] compressedHtml = compressor.compress(html.getBytes());
        long htmlTime = System.nanoTime() - start;
        
        start = System.nanoTime();
        byte[] compressedJson = compressor.compress(json.getBytes());
        long jsonTime = System.nanoTime() - start;
        
        double htmlRatio = 100.0 * compressedHtml.length / html.getBytes().length;
        double jsonRatio = 100.0 * compressedJson.length / json.getBytes().length;
        
        System.out.printf("  HTML compression: %.1f%% ratio, %.2f ms\n", htmlRatio, htmlTime / 1_000_000.0);
        System.out.printf("  JSON compression: %.1f%% ratio, %.2f ms\n", jsonRatio, jsonTime / 1_000_000.0);
    }
    
    private static void testMetricsCollector() {
        System.out.println("\n### MetricsCollector");
        MetricsCollector metrics = new MetricsCollector();
        
        long start = System.nanoTime();
        int numRecords = 10000;
        for (int i = 0; i < numRecords; i++) {
            metrics.recordRequest("/test", "GET", 200, 1000 + i, 1024);
        }
        long duration = System.nanoTime() - start;
        double throughput = numRecords / (duration / 1_000_000_000.0);
        
        System.out.printf("  Recording rate: %.0f metrics/sec\n", throughput);
        System.out.printf("  Average time per record: %.4f µs\n", (duration / numRecords) / 1000.0);
        
        // Memory footprint estimate
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        Thread.yield();
        long memBefore = runtime.totalMemory() - runtime.freeMemory();
        
        MetricsCollector bigMetrics = new MetricsCollector();
        for (int i = 0; i < 10000; i++) {
            bigMetrics.recordRequest("/test" + i, "GET", 200, i, i * 100);
        }
        
        System.gc();
        Thread.yield();
        long memAfter = runtime.totalMemory() - runtime.freeMemory();
        long memGrowth = memAfter - memBefore;
        
        System.out.printf("  Memory for 10k metrics: %.2f KB\n", memGrowth / 1024.0);
    }
    
    private static void testConcurrentLoad() throws Exception {
        System.out.println("\n## CONCURRENT LOAD TESTS");
        System.out.println("-".repeat(80));
        
        int[] threadCounts = {1, 5, 10, 20, 50};
        
        for (int numThreads : threadCounts) {
            testConcurrentLoadWithThreads(numThreads);
        }
    }
    
    private static void testConcurrentLoadWithThreads(int numThreads) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        int requestsPerThread = 100;
        int totalRequests = numThreads * requestsPerThread;
        
        CountDownLatch latch = new CountDownLatch(totalRequests);
        long start = System.nanoTime();
        
        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    sendRequest("GET /small.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(60, TimeUnit.SECONDS);
        long duration = System.nanoTime() - start;
        
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        
        double throughput = totalRequests / (duration / 1_000_000_000.0);
        
        System.out.printf("\n%d threads × %d requests:\n", numThreads, requestsPerThread);
        System.out.printf("  Throughput: %.2f req/sec\n", throughput);
        System.out.printf("  Success: %d, Failures: %d\n", successCount.get(), failureCount.get());
        System.out.printf("  Duration: %.2f seconds\n", duration / 1_000_000_000.0);
    }
    
    private static String sendRequest(String request) throws IOException {
        Socket socket = new Socket(LOCALHOST, TEST_PORT);
        try {
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes());
            out.flush();
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append("\n");
                if (line.isEmpty()) break; // End of headers
            }
            return response.toString();
        } finally {
            socket.close();
        }
    }
    
    private static void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }
}
