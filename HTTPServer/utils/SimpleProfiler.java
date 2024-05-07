package HTTPServer;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Simple performance profiler - tests component performance
 */
public class SimpleProfiler {
    
    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(80));
        System.out.println("HTTP SERVER COMPONENT PERFORMANCE BASELINE");
        System.out.println("=".repeat(80));
        System.out.println("Date: " + new Date());
        System.out.println("Java Version: " + System.getProperty("java.version"));
        System.out.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        System.out.println("Processors: " + Runtime.getRuntime().availableProcessors());
        
        testResourceUsage();
        testRateLimiter();
        testCacheManager();
        testCompressionHandler();
        testMetricsCollector();
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("PROFILING COMPLETE");
        System.out.println("=".repeat(80));
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
    
    private static void testRateLimiter() {
        System.out.println("\n## RATE LIMITER PERFORMANCE");
        System.out.println("-".repeat(80));
        
        RateLimiter limiter = new RateLimiter(10000, 60);
        
        // Test throughput
        long start = System.nanoTime();
        int numTests = 100000;
        int allowed = 0;
        for (int i = 0; i < numTests; i++) {
            if (limiter.tryAcquire("test-ip-" + (i % 1000)).isAllowed()) {
                allowed++;
            }
        }
        long duration = System.nanoTime() - start;
        double throughput = numTests / (duration / 1_000_000_000.0);
        
        System.out.printf("Throughput: %.0f checks/sec\n", throughput);
        System.out.printf("Average time per check: %.4f µs\n", (duration / numTests) / 1000.0);
        System.out.printf("Allowed: %d/%d (%.1f%%)\n", allowed, numTests, 100.0 * allowed / numTests);
        
        // Test memory footprint
        System.gc();
        Thread.yield();
        Runtime runtime = Runtime.getRuntime();
        long memBefore = runtime.totalMemory() - runtime.freeMemory();
        
        RateLimiter bigLimiter = new RateLimiter(1000, 60);
        for (int i = 0; i < 10000; i++) {
            bigLimiter.tryAcquire("ip-" + i);
        }
        
        System.gc();
        Thread.yield();
        long memAfter = runtime.totalMemory() - runtime.freeMemory();
        System.out.printf("Memory for 10k buckets: ~%.2f KB\n", (memAfter - memBefore) / 1024.0);
    }
    
    private static void testCacheManager() throws Exception {
        System.out.println("\n## CACHE MANAGER PERFORMANCE");
        System.out.println("-".repeat(80));

        CacheManager cache = new CacheManager();
        cache.clearCache();
        Path tempDir = Files.createTempDirectory("cachetest");

        try {
            // Create test files
            File smallFile = tempDir.resolve("small.html").toFile();
            File mediumFile = tempDir.resolve("medium.html").toFile();
            File largeFile = tempDir.resolve("large.html").toFile();

            Files.write(smallFile.toPath(), generateBytes(10 * 1024));
            Files.write(mediumFile.toPath(), generateBytes(100 * 1024));
            Files.write(largeFile.toPath(), generateBytes(1024 * 1024));

            // Measure UNCACHED ETag calculation time
            long start = System.nanoTime();
            String etag = cache.generateETag(smallFile);
            long smallTime = System.nanoTime() - start;

            start = System.nanoTime();
            String etag2 = cache.generateETag(mediumFile);
            long mediumTime = System.nanoTime() - start;

            start = System.nanoTime();
            String etag3 = cache.generateETag(largeFile);
            long largeTime = System.nanoTime() - start;

            System.out.printf("Small file ETag (10KB) - UNCACHED: %.2f ms\n", smallTime / 1_000_000.0);
            System.out.printf("Medium file ETag (100KB) - UNCACHED: %.2f ms\n", mediumTime / 1_000_000.0);
            System.out.printf("Large file ETag (1MB) - UNCACHED: %.2f ms\n", largeTime / 1_000_000.0);

            // Measure CACHED ETag performance
            start = System.nanoTime();
            for (int i = 0; i < 10000; i++) {
                cache.generateETag(smallFile); // Cache hit
            }
            long smallCachedTime = System.nanoTime() - start;

            start = System.nanoTime();
            for (int i = 0; i < 10000; i++) {
                cache.generateETag(mediumFile); // Cache hit
            }
            long mediumCachedTime = System.nanoTime() - start;

            start = System.nanoTime();
            for (int i = 0; i < 10000; i++) {
                cache.generateETag(largeFile); // Cache hit
            }
            long largeCachedTime = System.nanoTime() - start;

            double smallSpeedup = (double) smallTime / (smallCachedTime / 10000);
            double mediumSpeedup = (double) mediumTime / (mediumCachedTime / 10000);
            double largeSpeedup = (double) largeTime / (largeCachedTime / 10000);

            System.out.printf("Small file ETag (10KB) - CACHED avg: %.4f µs (%.0fx faster)\n",
                (smallCachedTime / 10000.0) / 1000.0, smallSpeedup);
            System.out.printf("Medium file ETag (100KB) - CACHED avg: %.4f µs (%.0fx faster)\n",
                (mediumCachedTime / 10000.0) / 1000.0, mediumSpeedup);
            System.out.printf("Large file ETag (1MB) - CACHED avg: %.4f µs (%.0fx faster)\n",
                (largeCachedTime / 10000.0) / 1000.0, largeSpeedup);

            // Test cache statistics
            System.out.printf("Cache hit rate: %.2f%% (%d hits, %d misses)\n",
                cache.getCacheHitRate() * 100, cache.getCacheHits(), cache.getCacheMisses());
            System.out.printf("Cache size: %d entries\n", cache.getCacheSize());

        } finally {
            deleteDirectory(tempDir.toFile());
        }
    }
    
    private static void testCompressionHandler() {
        System.out.println("\n## COMPRESSION HANDLER PERFORMANCE");
        System.out.println("-".repeat(80));
        
        CompressionHandler compressor = new CompressionHandler();
        
        String html = generateHTML(10 * 1024);
        String json = generateJSON(10 * 1024);
        String text = "test ".repeat(2048);
        
        // HTML compression
        long start = System.nanoTime();
        byte[] compressedHtml = compressor.compress(html.getBytes());
        long htmlTime = System.nanoTime() - start;
        
        // JSON compression
        start = System.nanoTime();
        byte[] compressedJson = compressor.compress(json.getBytes());
        long jsonTime = System.nanoTime() - start;
        
        // Text compression
        start = System.nanoTime();
        byte[] compressedText = compressor.compress(text.getBytes());
        long textTime = System.nanoTime() - start;
        
        double htmlRatio = 100.0 * compressedHtml.length / html.getBytes().length;
        double jsonRatio = 100.0 * compressedJson.length / json.getBytes().length;
        double textRatio = 100.0 * compressedText.length / text.getBytes().length;
        
        System.out.printf("HTML (10KB): %.1f%% compressed, %.2f ms\n", htmlRatio, htmlTime / 1_000_000.0);
        System.out.printf("JSON (10KB): %.1f%% compressed, %.2f ms\n", jsonRatio, jsonTime / 1_000_000.0);
        System.out.printf("Text (10KB): %.1f%% compressed, %.2f ms\n", textRatio, textTime / 1_000_000.0);
        
        // Throughput test
        start = System.nanoTime();
        int numCompress = 1000;
        for (int i = 0; i < numCompress; i++) {
            compressor.compress(html.getBytes());
        }
        long throughputTime = System.nanoTime() - start;
        double throughput = numCompress / (throughputTime / 1_000_000_000.0);
        
        System.out.printf("Compression throughput: %.2f ops/sec\n", throughput);
        System.out.printf("Average compression time: %.2f ms\n", (throughputTime / numCompress) / 1_000_000.0);
    }
    
    private static void testMetricsCollector() {
        System.out.println("\n## METRICS COLLECTOR PERFORMANCE");
        System.out.println("-".repeat(80));
        
        MetricsCollector metrics = MetricsCollector.getInstance();
        
        // Test recording performance
        long start = System.nanoTime();
        int numRecords = 10000;
        for (int i = 0; i < numRecords; i++) {
            metrics.recordRequest("GET", 200, 10 + (i % 100), 1024 + (i % 10000));
        }
        long duration = System.nanoTime() - start;
        double throughput = numRecords / (duration / 1_000_000_000.0);
        
        System.out.printf("Recording rate: %.0f metrics/sec\n", throughput);
        System.out.printf("Average time per record: %.4f µs\n", (duration / numRecords) / 1000.0);
        
        // Test export performance
        start = System.nanoTime();
        String export = metrics.exportPrometheusMetrics();
        long exportTime = System.nanoTime() - start;
        
        System.out.printf("Export time: %.2f ms\n", exportTime / 1_000_000.0);
        System.out.printf("Export size: %d bytes\n", export.length());
        
        // Memory estimate
        System.gc();
        Thread.yield();
        Runtime runtime = Runtime.getRuntime();
        long memBefore = runtime.totalMemory() - runtime.freeMemory();
        
        MetricsCollector testMetrics = MetricsCollector.getInstance();
        for (int i = 0; i < 10000; i++) {
            testMetrics.recordRequest("POST", 200, i % 100, i * 100);
        }
        
        System.gc();
        Thread.yield();
        long memAfter = runtime.totalMemory() - runtime.freeMemory();
        System.out.printf("Memory growth (10k more metrics): ~%.2f KB\n", (memAfter - memBefore) / 1024.0);
    }
    
    private static String generateHTML(int size) {
        StringBuilder sb = new StringBuilder("<html><body>");
        int remaining = size - sb.length() - 14;
        for (int i = 0; i < remaining / 10; i++) {
            sb.append("testdata" + (i % 10));
        }
        sb.append("</body></html>");
        return sb.toString();
    }
    
    private static String generateJSON(int size) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        int count = (size - 20) / 4;
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            sb.append(i % 100);
        }
        sb.append("]}");
        return sb.toString();
    }
    
    private static byte[] generateBytes(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte)('a' + (i % 26));
        }
        return data;
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
