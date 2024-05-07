package HTTPServer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Benchmark comparing virtual threads vs platform threads performance.
 *
 * Run with: java -cp target/classes:HTTPServer HTTPServer.VirtualThreadsBenchmark
 */
public class VirtualThreadsBenchmark {

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(70));
        System.out.println("Virtual Threads vs Platform Threads Benchmark");
        System.out.println("=".repeat(70));

        VirtualThreadsSupport vtSupport = new VirtualThreadsSupport();
        int javaVersion = vtSupport.getJavaVersion();
        boolean supportsVT = vtSupport.supportsVirtualThreads();

        System.out.println("\nJava Version: " + javaVersion);
        System.out.println("Virtual Threads Support: " + (supportsVT ? "YES" : "NO"));
        System.out.println();

        // Test 1: Throughput test with 1000 tasks
        System.out.println("Test 1: Throughput - 1000 short-lived tasks");
        System.out.println("-".repeat(70));
        benchmarkThroughput(1000, 1);

        // Test 2: Concurrency test with 10000 tasks
        System.out.println("\nTest 2: Concurrency - 10,000 concurrent tasks");
        System.out.println("-".repeat(70));
        benchmarkThroughput(10000, 1);

        // Test 3: Long-running tasks
        System.out.println("\nTest 3: Long-running - 1000 tasks (10ms each)");
        System.out.println("-".repeat(70));
        benchmarkThroughput(1000, 10);

        // Test 4: Very high concurrency
        if (supportsVT) {
            System.out.println("\nTest 4: Ultra High Concurrency - 50,000 virtual threads");
            System.out.println("-".repeat(70));
            benchmarkThroughput(50000, 1);
        }

        System.out.println("\n" + "=".repeat(70));
        System.out.println("Benchmark Complete");
        System.out.println("=".repeat(70));
    }

    private static void benchmarkThroughput(int taskCount, int taskDurationMs) throws Exception {
        VirtualThreadsSupport vtSupport = new VirtualThreadsSupport();
        ExecutorService executor = vtSupport.createExecutor();

        CountDownLatch latch = new CountDownLatch(taskCount);
        AtomicInteger completedCount = new AtomicInteger(0);

        long startTime = System.nanoTime();

        // Submit all tasks
        for (int i = 0; i < taskCount; i++) {
            executor.submit(() -> {
                try {
                    if (taskDurationMs > 0) {
                        Thread.sleep(taskDurationMs);
                    }
                    completedCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for completion
        boolean completed = latch.await(2, TimeUnit.MINUTES);
        long endTime = System.nanoTime();
        long durationNs = endTime - startTime;
        long durationMs = TimeUnit.NANOSECONDS.toMillis(durationNs);

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Calculate metrics
        double throughput = (taskCount * 1000.0) / durationMs;
        double timePerTask = durationMs / (double) taskCount;

        System.out.printf("Tasks: %,d%n", taskCount);
        System.out.printf("Total Duration: %,d ms%n", durationMs);
        System.out.printf("Throughput: %.0f tasks/sec%n", throughput);
        System.out.printf("Time per Task: %.3f ms%n", timePerTask);
        System.out.printf("Completed: %,d/%,d%n", completedCount.get(), taskCount);

        if (!completed) {
            System.out.println("WARNING: Not all tasks completed within timeout!");
        }
    }
}
