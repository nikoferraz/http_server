package HTTPServer;

import java.nio.ByteBuffer;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.List;

/**
 * Performance profiler for Buffer Pool demonstrating GC overhead reduction.
 *
 * This profiler simulates the behavior of an HTTP server handling multiple
 * requests with 8KB buffer allocations. It compares the GC impact of:
 * 1. Traditional approach: allocate new byte[] for each request
 * 2. Buffer pool approach: reuse buffers from a pool
 */
public class BufferPoolProfiler {

    private static final int BUFFER_SIZE = 8192;
    private static final int NUM_REQUESTS = 10000;
    private static final int WARMUP_REQUESTS = 1000;

    public static void main(String[] args) {
        System.out.println("=== Buffer Pool GC Profiling ===\n");
        System.out.println("Configuration:");
        System.out.println("  Buffer size: " + BUFFER_SIZE + " bytes");
        System.out.println("  Simulated requests: " + NUM_REQUESTS);
        System.out.println("  Warmup requests: " + WARMUP_REQUESTS);
        System.out.println();

        // Test traditional approach (without pooling)
        System.out.println("--- Traditional Approach (No Pooling) ---");
        GCMetrics traditionalMetrics = profileTraditionalApproach();

        System.out.println();

        // Test buffer pool approach
        System.out.println("--- Buffer Pool Approach ---");
        GCMetrics poolMetrics = profileBufferPoolApproach();

        System.out.println();

        // Compare results
        System.out.println("=== Comparison ===");
        printComparison(traditionalMetrics, poolMetrics);
    }

    private static GCMetrics profileTraditionalApproach() {
        // Warmup phase
        System.out.println("Warmup phase...");
        for (int i = 0; i < WARMUP_REQUESTS; i++) {
            byte[] buffer = new byte[BUFFER_SIZE];
            simulateBufferUsage(buffer);
        }
        System.gc();

        // Measurement phase
        System.out.println("Measuring traditional approach...");
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        long gcCountBefore = getGCCollectionCount();
        long gcTimeBefore = getGCCollectionTime();
        MemoryUsage heapBefore = memoryBean.getHeapMemoryUsage();

        long startTime = System.nanoTime();
        for (int i = 0; i < NUM_REQUESTS; i++) {
            byte[] buffer = new byte[BUFFER_SIZE];
            simulateBufferUsage(buffer);
        }
        long duration = System.nanoTime() - startTime;

        System.gc();
        MemoryUsage heapAfter = memoryBean.getHeapMemoryUsage();
        long gcCountAfter = getGCCollectionCount();
        long gcTimeAfter = getGCCollectionTime();

        GCMetrics metrics = new GCMetrics();
        metrics.durationNs = duration;
        metrics.allocatedMB = (heapAfter.getUsed() - heapBefore.getUsed()) / (1024.0 * 1024.0);
        metrics.gcCollections = gcCountAfter - gcCountBefore;
        metrics.gcTimeMs = gcTimeAfter - gcTimeBefore;
        metrics.throughputPerSec = (NUM_REQUESTS * 1_000_000_000.0) / duration;

        System.out.println(String.format("Duration: %.3f seconds", duration / 1_000_000_000.0));
        System.out.println(String.format("GC collections: %d", metrics.gcCollections));
        System.out.println(String.format("GC time: %d ms", metrics.gcTimeMs));
        System.out.println(String.format("Throughput: %.0f requests/sec", metrics.throughputPerSec));
        System.out.println(String.format("Memory allocated: %.2f MB", metrics.allocatedMB));

        return metrics;
    }

    private static GCMetrics profileBufferPoolApproach() {
        BufferPool pool = new BufferPool(BUFFER_SIZE, 1000);

        // Warmup phase
        System.out.println("Warmup phase...");
        for (int i = 0; i < WARMUP_REQUESTS; i++) {
            ByteBuffer buffer = pool.acquire();
            simulateBufferUsageDirectBuffer(buffer);
            pool.release(buffer);
        }
        System.gc();

        // Measurement phase
        System.out.println("Measuring buffer pool approach...");
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        long gcCountBefore = getGCCollectionCount();
        long gcTimeBefore = getGCCollectionTime();
        MemoryUsage heapBefore = memoryBean.getHeapMemoryUsage();

        long startTime = System.nanoTime();
        for (int i = 0; i < NUM_REQUESTS; i++) {
            ByteBuffer buffer = pool.acquire();
            simulateBufferUsageDirectBuffer(buffer);
            pool.release(buffer);
        }
        long duration = System.nanoTime() - startTime;

        System.gc();
        MemoryUsage heapAfter = memoryBean.getHeapMemoryUsage();
        long gcCountAfter = getGCCollectionCount();
        long gcTimeAfter = getGCCollectionTime();

        GCMetrics metrics = new GCMetrics();
        metrics.durationNs = duration;
        metrics.allocatedMB = (heapAfter.getUsed() - heapBefore.getUsed()) / (1024.0 * 1024.0);
        metrics.gcCollections = gcCountAfter - gcCountBefore;
        metrics.gcTimeMs = gcTimeAfter - gcTimeBefore;
        metrics.throughputPerSec = (NUM_REQUESTS * 1_000_000_000.0) / duration;
        metrics.poolSize = pool.size();
        metrics.poolAllocated = pool.getAllocatedCount();

        System.out.println(String.format("Duration: %.3f seconds", duration / 1_000_000_000.0));
        System.out.println(String.format("GC collections: %d", metrics.gcCollections));
        System.out.println(String.format("GC time: %d ms", metrics.gcTimeMs));
        System.out.println(String.format("Throughput: %.0f requests/sec", metrics.throughputPerSec));
        System.out.println(String.format("Memory allocated: %.2f MB", metrics.allocatedMB));
        System.out.println(String.format("Pool status: %d buffers pooled, %d allocated total",
                                        metrics.poolSize, metrics.poolAllocated));

        return metrics;
    }

    private static void simulateBufferUsage(byte[] buffer) {
        // Simulate reading from buffer
        for (int i = 0; i < Math.min(buffer.length, 100); i++) {
            buffer[i] = (byte) (i % 256);
        }
    }

    private static void simulateBufferUsageDirectBuffer(ByteBuffer buffer) {
        // Simulate reading from direct buffer
        buffer.clear();
        for (int i = 0; i < Math.min(buffer.capacity(), 100); i++) {
            buffer.put((byte) (i % 256));
        }
        buffer.clear();
    }

    private static long getGCCollectionCount() {
        long count = 0;
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += gcBean.getCollectionCount();
        }
        return count;
    }

    private static long getGCCollectionTime() {
        long time = 0;
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            time += gcBean.getCollectionTime();
        }
        return time;
    }

    private static void printComparison(GCMetrics traditional, GCMetrics pool) {
        double throughputImprovement = ((pool.throughputPerSec - traditional.throughputPerSec) / traditional.throughputPerSec) * 100;
        double gcReduction = ((traditional.gcTimeMs - pool.gcTimeMs) / (double) traditional.gcTimeMs) * 100;
        double gcCollectionReduction = ((traditional.gcCollections - pool.gcCollections) / (double) traditional.gcCollections) * 100;

        System.out.println("Throughput improvement: " + String.format("%.2f%%", throughputImprovement));
        System.out.println("GC time reduction: " + String.format("%.2f%%", gcReduction));
        System.out.println("GC collections reduction: " + String.format("%.2f%%", gcCollectionReduction));

        if (throughputImprovement > 0) {
            System.out.println("\nResult: Buffer pool improves performance!");
        } else {
            System.out.println("\nResult: Buffer pool adds minimal overhead");
        }

        System.out.println("\nSummary:");
        System.out.println(String.format("  Traditional: %d GC events, %.0f requests/sec",
                traditional.gcCollections, traditional.throughputPerSec));
        System.out.println(String.format("  Pool approach: %d GC events, %.0f requests/sec",
                pool.gcCollections, pool.throughputPerSec));
        System.out.println(String.format("  Pool size: %d (max 1000)", pool.poolSize));
    }

    static class GCMetrics {
        long durationNs;
        double allocatedMB;
        long gcCollections;
        long gcTimeMs;
        double throughputPerSec;
        int poolSize;
        int poolAllocated;
    }
}
