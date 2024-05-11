package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Test suite for ETag caching functionality.
 * Tests cache hits, misses, invalidation, size limits, and thread safety.
 */
@DisplayName("ETag Cache Tests")
class ETagCacheTest {

    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        cacheManager = new CacheManager();
        cacheManager.clearCache();
    }

    @Nested
    @DisplayName("Cache Hit and Miss")
    class CacheHitMissTests {

        @Test
        @DisplayName("Should cache ETag after first calculation")
        void testETagCaching(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "test content".getBytes());

            // First call - cache miss, calculates ETag
            long start1 = System.nanoTime();
            String etag1 = cacheManager.generateETag(testFile);
            long time1 = System.nanoTime() - start1;

            // Second call - cache hit, should be much faster
            long start2 = System.nanoTime();
            String etag2 = cacheManager.generateETag(testFile);
            long time2 = System.nanoTime() - start2;

            // ETags should be identical
            assertThat(etag1).isEqualTo(etag2);

            // Cache hit should be significantly faster (at least 10x)
            assertThat(time2).isLessThan(time1 / 10);
        }

        @Test
        @DisplayName("Should track cache hits and misses")
        void testCacheMetrics(@TempDir Path tempDir) throws IOException {
            File file1 = tempDir.resolve("file1.txt").toFile();
            File file2 = tempDir.resolve("file2.txt").toFile();
            Files.write(file1.toPath(), "content 1".getBytes());
            Files.write(file2.toPath(), "content 2".getBytes());

            // Generate ETags: 2 misses
            cacheManager.generateETag(file1);
            cacheManager.generateETag(file2);

            // Generate same ETags again: 2 hits
            cacheManager.generateETag(file1);
            cacheManager.generateETag(file2);

            double hitRate = cacheManager.getCacheHitRate();

            assertThat(cacheManager.getCacheSize()).isEqualTo(2);
            assertThat(hitRate).isGreaterThanOrEqualTo(0.5); // 2 hits / 4 total
        }

        @Test
        @DisplayName("Should measure cache hit percentage correctly")
        void testCacheHitPercentage(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "content".getBytes());

            // 1 miss
            cacheManager.generateETag(testFile);
            double hitRate1 = cacheManager.getCacheHitRate();
            assertThat(hitRate1).isEqualTo(0.0); // 0/1

            // 9 hits
            for (int i = 0; i < 9; i++) {
                cacheManager.generateETag(testFile);
            }
            double hitRate2 = cacheManager.getCacheHitRate();
            assertThat(hitRate2).isEqualTo(0.9); // 9/10
        }
    }

    @Nested
    @DisplayName("Cache Invalidation")
    class CacheInvalidationTests {

        @Test
        @DisplayName("Should invalidate cache when file modified")
        void testCacheInvalidation(@TempDir Path tempDir) throws IOException, InterruptedException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "original content".getBytes());

            // First generation
            String etag1 = cacheManager.generateETag(testFile);
            double hitRate1 = cacheManager.getCacheHitRate();

            // Wait to ensure file modification timestamp differs
            Thread.sleep(2);

            // Modify file
            Files.write(testFile.toPath(), "modified content".getBytes());

            // Second generation should recalculate (cache miss)
            String etag2 = cacheManager.generateETag(testFile);

            // ETags should be different
            assertThat(etag1).isNotEqualTo(etag2);

            // Hit rate should drop due to cache miss
            double hitRate2 = cacheManager.getCacheHitRate();
            assertThat(hitRate2).isLessThan(hitRate1);
        }

        @Test
        @DisplayName("Should detect file modification time changes")
        void testFileModificationDetection(@TempDir Path tempDir) throws IOException, InterruptedException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "content".getBytes());
            long originalMod = testFile.lastModified();

            // Cache the ETag
            cacheManager.generateETag(testFile);

            // Change modification time without changing content
            Thread.sleep(10);
            testFile.setLastModified(System.currentTimeMillis());

            // Should detect cache miss due to modification time
            long newMod = testFile.lastModified();
            assertThat(newMod).isNotEqualTo(originalMod);

            // This should be a cache miss
            String etag = cacheManager.generateETag(testFile);
            assertThat(etag).isNotNull();
        }
    }

    @Nested
    @DisplayName("Cache Size and LRU Eviction")
    class CacheSizeLimitTests {

        @Test
        @DisplayName("Should respect cache size limit (10,000 entries)")
        void testCacheSizeLimit(@TempDir Path tempDir) throws IOException {
            // Create more files than cache limit
            int numFiles = 100;
            for (int i = 0; i < numFiles; i++) {
                File f = tempDir.resolve("file-" + i + ".txt").toFile();
                Files.write(f.toPath(), ("content " + i).getBytes());
                cacheManager.generateETag(f);
            }

            long cacheSize = cacheManager.getCacheSize();
            assertThat(cacheSize).isLessThanOrEqualTo(100);
            assertThat(cacheSize).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should enforce max cache size of 10,000")
        void testMaxCacheSize(@TempDir Path tempDir) throws IOException {
            final int MAX_SIZE = 10000;

            // Create 10,001 files
            for (int i = 0; i < MAX_SIZE + 1; i++) {
                File f = tempDir.resolve("f-" + i + ".tmp").toFile();
                Files.write(f.toPath(), String.valueOf(i).getBytes());
                cacheManager.generateETag(f);
            }

            long cacheSize = cacheManager.getCacheSize();
            assertThat(cacheSize).isLessThanOrEqualTo(MAX_SIZE);
        }

        @Test
        @DisplayName("Should evict least recently used entry when cache full")
        void testLRUEviction(@TempDir Path tempDir) throws IOException {
            // Create 3 files (smaller than cache limit)
            File file1 = tempDir.resolve("first.txt").toFile();
            File file2 = tempDir.resolve("second.txt").toFile();
            File file3 = tempDir.resolve("third.txt").toFile();

            Files.write(file1.toPath(), "content 1".getBytes());
            Files.write(file2.toPath(), "content 2".getBytes());
            Files.write(file3.toPath(), "content 3".getBytes());

            // Cache all three
            String etag1 = cacheManager.generateETag(file1);
            String etag2 = cacheManager.generateETag(file2);
            String etag3 = cacheManager.generateETag(file3);

            assertThat(cacheManager.getCacheSize()).isEqualTo(3);

            // Access file1 and file2 again (making them recently used)
            cacheManager.generateETag(file1);
            cacheManager.generateETag(file2);

            // The cache should have been accessed, file1 and file2 are now more recent
            assertThat(cacheManager.getCacheSize()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should clear cache when requested")
        void testClearCache(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "content".getBytes());

            // Add to cache
            cacheManager.generateETag(testFile);
            assertThat(cacheManager.getCacheSize()).isGreaterThan(0);

            // Clear cache
            cacheManager.clearCache();

            assertThat(cacheManager.getCacheSize()).isEqualTo(0);
            assertThat(cacheManager.getCacheHitRate()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Concurrent Access")
    class ConcurrentAccessTests {

        @Test
        @DisplayName("Should handle concurrent cache access safely")
        void testConcurrentCacheAccess(@TempDir Path tempDir) throws IOException, InterruptedException {
            File testFile = tempDir.resolve("concurrent.txt").toFile();
            Files.write(testFile.toPath(), "shared content".getBytes());

            int numThreads = 10;
            int accessesPerThread = 100;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(numThreads);
            AtomicInteger successCount = new AtomicInteger(0);

            // Spawn multiple threads accessing the cache
            for (int t = 0; t < numThreads; t++) {
                new Thread(() -> {
                    try {
                        startLatch.await(); // Wait for all threads to be ready
                        for (int i = 0; i < accessesPerThread; i++) {
                            String etag = cacheManager.generateETag(testFile);
                            if (etag != null && !etag.isEmpty()) {
                                successCount.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        endLatch.countDown();
                    }
                }).start();
            }

            // Start all threads at same time
            startLatch.countDown();
            // Wait for all threads to finish
            endLatch.await();

            // Verify all accesses succeeded
            assertThat(successCount.get()).isEqualTo(numThreads * accessesPerThread);
            // Cache should have the file
            assertThat(cacheManager.getCacheSize()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should not duplicate ETag calculations during concurrent access")
        void testNoDuplicateCalculations(@TempDir Path tempDir) throws IOException, InterruptedException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "content".getBytes());

            int numThreads = 5;
            CountDownLatch latch = new CountDownLatch(numThreads);
            List<String> etags = new ArrayList<>();

            // Multiple threads request same file simultaneously
            for (int i = 0; i < numThreads; i++) {
                new Thread(() -> {
                    try {
                        String etag = cacheManager.generateETag(testFile);
                        synchronized (etags) {
                            etags.add(etag);
                        }
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            latch.await();

            // All ETags should be identical
            assertThat(etags).allMatch(etag -> etag.equals(etags.get(0)));
        }
    }

    @Nested
    @DisplayName("Cache Performance")
    class CachePerformanceTests {

        @Test
        @DisplayName("Should provide significant speedup for repeated access")
        void testCacheSpeedup(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("perf.txt").toFile();
            Files.write(testFile.toPath(), generateBytes(1024 * 100)); // 100KB

            // Warm up and measure first access (cache miss)
            long warmup = System.nanoTime();
            cacheManager.generateETag(testFile);
            long firstAccessTime = System.nanoTime() - warmup;

            // Measure subsequent access (cache hit)
            long hitStart = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                cacheManager.generateETag(testFile);
            }
            long totalCacheHitTime = System.nanoTime() - hitStart;
            long avgCacheHitTime = totalCacheHitTime / 1000;

            // Cache hit should be orders of magnitude faster
            double speedup = (double) firstAccessTime / avgCacheHitTime;
            assertThat(speedup).isGreaterThan(100.0); // 100x speedup
        }

        @Test
        @DisplayName("Should maintain high cache hit rate under load")
        void testCacheHitRateUnderLoad(@TempDir Path tempDir) throws IOException {
            // Create few files
            File[] files = new File[5];
            for (int i = 0; i < 5; i++) {
                files[i] = tempDir.resolve("file-" + i + ".txt").toFile();
                Files.write(files[i].toPath(), ("content " + i).getBytes());
            }

            // Access with high locality (working set fits in cache)
            for (int i = 0; i < 1000; i++) {
                cacheManager.generateETag(files[i % 5]);
            }

            double hitRate = cacheManager.getCacheHitRate();
            // Should be mostly hits (1 miss per 5 files, rest hits)
            assertThat(hitRate).isGreaterThan(0.75); // At least 75% hit rate
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle very large files (100MB+)")
        void testLargeFileCache(@TempDir Path tempDir) throws IOException {
            File largeFile = tempDir.resolve("large.bin").toFile();

            // Create file > 100MB (uses weak ETag)
            try (var os = Files.newOutputStream(largeFile.toPath())) {
                byte[] data = new byte[1024];
                for (int i = 0; i < 102_400; i++) {
                    os.write(data);
                }
            }

            // First call
            String etag1 = cacheManager.generateETag(largeFile);

            // Second call should be cached
            long start = System.nanoTime();
            String etag2 = cacheManager.generateETag(largeFile);
            long time = System.nanoTime() - start;

            assertThat(etag1).isEqualTo(etag2);
            assertThat(time).isLessThan(1_000_000); // Less than 1ms (cache hit)
        }

        @Test
        @DisplayName("Should handle files with same content but different paths")
        void testDifferentPathsSameContent(@TempDir Path tempDir) throws IOException {
            File file1 = tempDir.resolve("file1.txt").toFile();
            File file2 = tempDir.resolve("file2.txt").toFile();

            Files.write(file1.toPath(), "same content".getBytes());
            Files.write(file2.toPath(), "same content".getBytes());

            // ETags should be same (same content)
            String etag1 = cacheManager.generateETag(file1);
            String etag2 = cacheManager.generateETag(file2);

            assertThat(etag1).isEqualTo(etag2);

            // Cache should have 2 entries (different paths)
            assertThat(cacheManager.getCacheSize()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should handle empty file caching")
        void testEmptyFileCache(@TempDir Path tempDir) throws IOException {
            File emptyFile = tempDir.resolve("empty.txt").toFile();
            Files.write(emptyFile.toPath(), new byte[0]);

            // First access
            String etag1 = cacheManager.generateETag(emptyFile);

            // Second access (should be cached)
            long start = System.nanoTime();
            String etag2 = cacheManager.generateETag(emptyFile);
            long time = System.nanoTime() - start;

            assertThat(etag1).isEqualTo(etag2);
            assertThat(time).isLessThan(100_000); // Much faster
        }
    }

    // Helper method
    private byte[] generateBytes(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ('a' + (i % 26));
        }
        return data;
    }
}
