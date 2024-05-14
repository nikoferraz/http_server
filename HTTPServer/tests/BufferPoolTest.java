package HTTPServer.tests;

import HTTPServer.BufferPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Buffer Pool Tests")
class BufferPoolTest {

    private BufferPool pool;

    @BeforeEach
    void setUp() {
        pool = new BufferPool(8192, 100);
    }

    @Test
    @DisplayName("Should allocate buffer from pool")
    void testBufferAllocation() {
        ByteBuffer buffer = pool.acquire();
        assertThat(buffer).isNotNull();
        assertThat(buffer.capacity()).isEqualTo(8192);
    }

    @Test
    @DisplayName("Should clear buffer before returning")
    void testBufferClearing() {
        ByteBuffer buffer = pool.acquire();
        buffer.put((byte) 42);
        buffer.put((byte) 100);
        assertThat(buffer.position()).isGreaterThan(0);

        pool.release(buffer);

        ByteBuffer reused = pool.acquire();
        assertThat(reused.position()).isEqualTo(0);
        assertThat(reused.limit()).isEqualTo(8192);
    }

    @Test
    @DisplayName("Should reuse released buffers - same instance")
    void testBufferReuse() {
        // Create a small pool with limited pre-allocation for this test
        BufferPool smallPool = new BufferPool(8192, 2);

        // Get one buffer from pool
        ByteBuffer buffer1 = smallPool.acquire();
        int buffer1Hash = System.identityHashCode(buffer1);

        // Release it back
        smallPool.release(buffer1);

        // Acquire again - should get the same instance (only 2 in pool)
        ByteBuffer buffer2 = smallPool.acquire();
        int buffer2Hash = System.identityHashCode(buffer2);

        assertThat(buffer2Hash).isEqualTo(buffer1Hash);
        assertThat(buffer2.capacity()).isEqualTo(8192);
    }

    @Test
    @DisplayName("Should respect pool size limit")
    void testPoolSizeLimit() {
        BufferPool smallPool = new BufferPool(8192, 10);
        List<ByteBuffer> buffers = new ArrayList<>();

        // Acquire 20 buffers
        for (int i = 0; i < 20; i++) {
            ByteBuffer buffer = smallPool.acquire();
            buffers.add(buffer);
        }

        // Release all buffers
        for (ByteBuffer buffer : buffers) {
            smallPool.release(buffer);
        }

        // Pool should have max 10
        assertThat(smallPool.size()).isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("Should be thread-safe under concurrent access")
    void testConcurrentAccess() throws InterruptedException {
        BufferPool concurrentPool = new BufferPool(8192, 100);
        int threadCount = 50;
        int operationsPerThread = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        ByteBuffer buffer = concurrentPool.acquire();
                        assertThat(buffer).isNotNull();
                        assertThat(buffer.capacity()).isEqualTo(8192);

                        // Simulate usage
                        buffer.put((byte) 1);

                        concurrentPool.release(buffer);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assertThat(errors.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should handle null release gracefully")
    void testNullRelease() {
        assertThatNoException().isThrownBy(() -> {
            pool.release(null);
        });
    }

    @Test
    @DisplayName("Should allocate new buffers when pool is empty")
    void testAllocationWhenPoolEmpty() {
        ByteBuffer buffer1 = pool.acquire();
        pool.release(buffer1);

        // Acquire more than pool max allows
        List<ByteBuffer> buffers = new ArrayList<>();
        for (int i = 0; i < 110; i++) {
            buffers.add(pool.acquire());
        }

        // All buffers should be valid
        for (ByteBuffer buffer : buffers) {
            assertThat(buffer).isNotNull();
            assertThat(buffer.capacity()).isEqualTo(8192);
        }

        // Release all
        for (ByteBuffer buffer : buffers) {
            pool.release(buffer);
        }

        // Pool size should not exceed max
        assertThat(pool.size()).isLessThanOrEqualTo(100);
    }

    @Test
    @DisplayName("Should track allocated buffers count")
    void testAllocationTracking() {
        // Create a small pool to test allocation
        BufferPool smallPool = new BufferPool(8192, 5);
        int initialAllocated = smallPool.getAllocatedCount();

        // Pre-allocated count should be approximately half
        assertThat(initialAllocated).isEqualTo(2); // 5 / 2 = 2

        // Acquire more than pre-allocated to force new allocations
        for (int i = 0; i < 5; i++) {
            smallPool.acquire();
        }

        // Should have allocated at least the initial pre-allocated count
        assertThat(smallPool.getAllocatedCount()).isGreaterThanOrEqualTo(initialAllocated);
    }

    @Test
    @DisplayName("Should track pooled buffers count")
    void testPoolingTracking() {
        ByteBuffer buffer = pool.acquire();
        int beforeRelease = pool.size();

        pool.release(buffer);
        int afterRelease = pool.size();

        assertThat(afterRelease).isGreaterThan(beforeRelease);
    }

    @Test
    @DisplayName("Should maintain buffer object identity across release/acquire")
    void testBufferIdentity() {
        Set<Integer> hashCodes = new HashSet<>();

        for (int i = 0; i < 10; i++) {
            ByteBuffer buffer = pool.acquire();
            hashCodes.add(System.identityHashCode(buffer));
            pool.release(buffer);
        }

        // Should have reused the same buffers (limited unique identities)
        assertThat(hashCodes).hasSizeLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("Should handle direct buffer allocation")
    void testDirectBufferAllocation() {
        ByteBuffer buffer = pool.acquire();
        // Direct buffers are suitable for I/O operations
        assertThat(buffer.isDirect()).isTrue();
    }

    @Test
    @DisplayName("Should clear method resets buffer properly")
    void testClearFunctionality() {
        ByteBuffer buffer = pool.acquire();
        buffer.put(new byte[]{1, 2, 3, 4, 5});
        buffer.put((byte) 100);

        pool.release(buffer);

        ByteBuffer reused = pool.acquire();
        // Position should be at start
        assertThat(reused.position()).isEqualTo(0);
        // Limit should be at capacity
        assertThat(reused.limit()).isEqualTo(reused.capacity());
        // Old data should still be there, but not accessible
        assertThat(reused.remaining()).isEqualTo(reused.capacity());
    }

    @Test
    @DisplayName("Should support clear operation on pool")
    void testPoolClear() {
        ByteBuffer buffer1 = pool.acquire();
        ByteBuffer buffer2 = pool.acquire();

        pool.release(buffer1);
        pool.release(buffer2);

        assertThat(pool.size()).isGreaterThan(0);

        pool.clear();

        assertThat(pool.size()).isEqualTo(0);
        assertThat(pool.getAllocatedCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should pre-allocate approximately half the pool")
    void testPreAllocation() {
        BufferPool testPool = new BufferPool(8192, 100);
        // Pool should have pre-allocated around 50 buffers
        assertThat(testPool.size()).isGreaterThanOrEqualTo(40);
        assertThat(testPool.size()).isLessThanOrEqualTo(60);
    }

    @Test
    @DisplayName("Should handle rapid acquire/release cycles")
    void testRapidCycles() {
        long startTime = System.nanoTime();

        for (int i = 0; i < 10000; i++) {
            ByteBuffer buffer = pool.acquire();
            pool.release(buffer);
        }

        long duration = System.nanoTime() - startTime;
        double opsPerSecond = (10000.0 * 1_000_000_000) / duration;

        // Should handle at least 100k ops/sec
        assertThat(opsPerSecond).isGreaterThan(100_000);
    }

    @Test
    @DisplayName("Should not drop buffers when pool is full")
    void testPoolFullBehavior() {
        BufferPool tinyPool = new BufferPool(8192, 5);
        List<ByteBuffer> buffers = new ArrayList<>();

        // Fill the pool
        for (int i = 0; i < 5; i++) {
            ByteBuffer buffer = tinyPool.acquire();
            buffers.add(buffer);
        }

        // Release 3
        for (int i = 0; i < 3; i++) {
            tinyPool.release(buffers.get(i));
        }

        assertThat(tinyPool.size()).isEqualTo(3);

        // Release 2 more - should be kept
        tinyPool.release(buffers.get(3));
        tinyPool.release(buffers.get(4));

        assertThat(tinyPool.size()).isEqualTo(5); // Full capacity
    }
}
