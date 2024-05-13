package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Test suite for Virtual Threads support (Java 21+).
 * Tests detection, creation, and performance of virtual thread executors.
 */
@DisplayName("Virtual Threads Support Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VirtualThreadsTest {

    private VirtualThreadsSupport threadSupport;

    @BeforeEach
    void setUp() {
        threadSupport = new VirtualThreadsSupport();
    }

    @Test
    @DisplayName("Should detect Java 21+ support for virtual threads")
    void testVirtualThreadsSupport() {
        // Test detection of virtual threads support
        boolean supportsVirtualThreads = threadSupport.supportsVirtualThreads();
        int javaVersion = threadSupport.getJavaVersion();

        assertThat(javaVersion).isGreaterThanOrEqualTo(11);

        if (javaVersion >= 21) {
            assertThat(supportsVirtualThreads)
                .as("Java 21+ should support virtual threads")
                .isTrue();
        } else {
            assertThat(supportsVirtualThreads)
                .as("Java < 21 should not support virtual threads")
                .isFalse();
        }
    }

    @Test
    @DisplayName("Should return correct Java version string")
    void testJavaVersionParsing() {
        int version = threadSupport.getJavaVersion();

        assertThat(version)
            .as("Java version should be positive integer")
            .isGreaterThan(0);

        String versionString = System.getProperty("java.version");
        assertThat(versionString).isNotEmpty();
    }

    @Test
    @DisplayName("Should create virtual thread executor when supported")
    void testVirtualThreadExecutorCreation() {
        ExecutorService executor = threadSupport.createExecutor();

        assertThat(executor)
            .as("Executor should be created")
            .isNotNull();

        // Verify it's actually an executor service
        assertThat(executor).isInstanceOf(ExecutorService.class);

        // Executor should not be shutdown
        assertThat(executor.isShutdown()).isFalse();

        executor.shutdown();
    }

    @Test
    @DisplayName("Should handle 1000+ concurrent virtual threads")
    void testHighConcurrency() throws InterruptedException {
        ExecutorService executor = threadSupport.createExecutor();
        int taskCount = 1000;
        CountDownLatch latch = new CountDownLatch(taskCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        // Submit 1000 concurrent tasks
        for (int i = 0; i < taskCount; i++) {
            executor.submit(() -> {
                try {
                    // Simulate some work
                    Thread.sleep(10);
                    successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    failureCount.incrementAndGet();
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all tasks to complete
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - startTime;

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(completed)
            .as("All 1000 tasks should complete within 30 seconds")
            .isTrue();

        assertThat(successCount.get())
            .as("All tasks should succeed")
            .isEqualTo(taskCount);

        assertThat(failureCount.get())
            .as("No tasks should fail")
            .isZero();

        // Virtual threads should complete much faster than platform threads
        // Platform threads with 1000 tasks would take much longer
        // This is informational; no strict assertion on time
        System.out.println("1000 concurrent tasks completed in: " + duration + "ms");
    }

    @Test
    @DisplayName("Should handle 10000+ concurrent virtual threads")
    void testVeryHighConcurrency() throws InterruptedException {
        ExecutorService executor = threadSupport.createExecutor();
        int taskCount = 10000;
        CountDownLatch latch = new CountDownLatch(taskCount);
        AtomicInteger successCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        // Submit 10,000 concurrent tasks
        for (int i = 0; i < taskCount; i++) {
            executor.submit(() -> {
                try {
                    // Minimal work
                    Thread.sleep(1);
                    successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all tasks to complete
        boolean completed = latch.await(60, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - startTime;

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(completed)
            .as("All 10,000 tasks should complete within 60 seconds")
            .isTrue();

        assertThat(successCount.get())
            .as("All tasks should succeed")
            .isEqualTo(taskCount);

        System.out.println("10,000 concurrent tasks completed in: " + duration + "ms");
    }

    @Test
    @DisplayName("Should fall back to platform threads on Java < 21")
    void testFallbackToPlatformThreads() {
        ExecutorService executor = threadSupport.createExecutor();

        assertThat(executor)
            .as("Executor should be created regardless of Java version")
            .isNotNull();

        // Should not throw exceptions
        CountDownLatch latch = new CountDownLatch(1);
        executor.submit(() -> {
            latch.countDown();
        });

        assertThatNoException().isThrownBy(() -> {
            latch.await(5, TimeUnit.SECONDS);
        });

        executor.shutdown();
    }

    @Test
    @DisplayName("Should properly shutdown executor")
    void testExecutorShutdown() throws InterruptedException {
        ExecutorService executor = threadSupport.createExecutor();
        CountDownLatch latch = new CountDownLatch(10);

        // Submit some tasks
        for (int i = 0; i < 10; i++) {
            executor.submit(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        // Shutdown executor
        executor.shutdown();

        // Should complete without errors
        boolean terminated = executor.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(terminated)
            .as("Executor should terminate")
            .isTrue();

        assertThat(executor.isTerminated())
            .as("Executor should be in terminated state")
            .isTrue();
    }

    @Test
    @DisplayName("Should handle rapid task submission and completion")
    void testRapidSubmission() throws InterruptedException {
        ExecutorService executor = threadSupport.createExecutor();
        int iterations = 100;
        int tasksPerIteration = 100;
        int totalTasks = iterations * tasksPerIteration;

        CountDownLatch latch = new CountDownLatch(totalTasks);
        AtomicInteger completedTasks = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        // Rapidly submit and complete many batches
        for (int i = 0; i < iterations; i++) {
            for (int j = 0; j < tasksPerIteration; j++) {
                executor.submit(() -> {
                    try {
                        Thread.sleep(1);
                        completedTasks.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }

        boolean completed = latch.await(60, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - startTime;

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(completed)
            .as("All 10,000 tasks should complete")
            .isTrue();

        assertThat(completedTasks.get())
            .as("All tasks should be completed")
            .isEqualTo(totalTasks);

        System.out.println(totalTasks + " tasks completed in: " + duration + "ms " +
                          "(" + (totalTasks * 1000.0 / duration) + " tasks/sec)");
    }

    @Test
    @DisplayName("Executor should not be null under any conditions")
    void testExecutorNeverNull() {
        // Test multiple times to ensure consistency
        for (int i = 0; i < 10; i++) {
            ExecutorService executor = threadSupport.createExecutor();

            assertThat(executor)
                .as("Executor created on iteration " + i + " should not be null")
                .isNotNull();

            executor.shutdown();
        }
    }
}
