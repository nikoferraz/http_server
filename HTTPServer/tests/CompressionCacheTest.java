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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Test suite for Compression Caching.
 * Tests cache hit/miss, invalidation, size limits, and performance.
 */
@DisplayName("CompressionCache Tests")
class CompressionCacheTest {

    private CompressionHandler compressionHandler;

    @BeforeEach
    void setUp() {
        compressionHandler = new CompressionHandler();
        compressionHandler.clearCache();
    }

    @Nested
    @DisplayName("Cache Behavior")
    class CacheBehaviorTests {

        @Test
        @DisplayName("Should cache compressed content after first compression")
        void testCompressionCaching(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("test.txt").toFile();
            String content = "Hello, World! ".repeat(1000);
            Files.write(testFile.toPath(), content.getBytes());

            // First compression - cache miss
            byte[] compressed1 = compressionHandler.compressFile(testFile);
            long hits1 = compressionHandler.getCacheHits();
            long misses1 = compressionHandler.getCacheMisses();

            assertThat(compressed1).isNotNull();
            assertThat(hits1).isEqualTo(0);
            assertThat(misses1).isEqualTo(1);

            // Second compression - cache hit
            byte[] compressed2 = compressionHandler.compressFile(testFile);
            long hits2 = compressionHandler.getCacheHits();
            long misses2 = compressionHandler.getCacheMisses();

            assertThat(compressed2).isNotNull();
            assertThat(compressed1).isEqualTo(compressed2);
            assertThat(hits2).isEqualTo(1);
            assertThat(misses2).isEqualTo(1);
        }

        @Test
        @DisplayName("Should invalidate cache when file modified")
        void testCacheInvalidation(@TempDir Path tempDir) throws IOException, InterruptedException {
            File testFile = tempDir.resolve("test.txt").toFile();
            String content = "Hello, World! ".repeat(1000);
            Files.write(testFile.toPath(), content.getBytes());

            // First compression
            byte[] compressed1 = compressionHandler.compressFile(testFile);
            long misses1 = compressionHandler.getCacheMisses();
            assertThat(misses1).isEqualTo(1);

            // Modify file (change last modified time)
            Thread.sleep(10);
            Files.write(testFile.toPath(), "Modified content".repeat(500).getBytes());

            // Second compression - should be cache miss due to modification
            byte[] compressed2 = compressionHandler.compressFile(testFile);
            long misses2 = compressionHandler.getCacheMisses();

            assertThat(compressed2).isNotNull();
            assertThat(misses2).isEqualTo(2); // New cache miss
        }

        @Test
        @DisplayName("Should track cache metrics accurately")
        void testCacheMetrics(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "test content".repeat(500).getBytes());

            // Clear cache and metrics
            compressionHandler.clearCache();
            assertThat(compressionHandler.getCacheHits()).isEqualTo(0);
            assertThat(compressionHandler.getCacheMisses()).isEqualTo(0);

            // Perform multiple compressions
            compressionHandler.compressFile(testFile); // miss
            compressionHandler.compressFile(testFile); // hit
            compressionHandler.compressFile(testFile); // hit

            assertThat(compressionHandler.getCacheHits()).isEqualTo(2);
            assertThat(compressionHandler.getCacheMisses()).isEqualTo(1);
            assertThat(compressionHandler.getCacheHitRate()).isEqualTo(2.0 / 3.0);
        }

        @Test
        @DisplayName("Should return zero hit rate when no requests")
        void testCacheHitRateEmpty() {
            compressionHandler.clearCache();
            assertThat(compressionHandler.getCacheHitRate()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("File Size Limits")
    class FileSizeLimitTests {

        @Test
        @DisplayName("Should not cache files larger than 1MB")
        void testMaxFileSizeLimit(@TempDir Path tempDir) throws IOException {
            // Create a 2MB file
            File largeFile = tempDir.resolve("large.txt").toFile();
            String largContent = "x".repeat(1024 * 1024 * 2);
            Files.write(largeFile.toPath(), largContent.getBytes());

            compressionHandler.compressFile(largeFile);
            assertThat(compressionHandler.getCacheMisses()).isEqualTo(1);
            assertThat(compressionHandler.getCacheSize()).isEqualTo(0); // Not cached
        }

        @Test
        @DisplayName("Should cache files at or below 1MB")
        void testFileBelowMaxSize(@TempDir Path tempDir) throws IOException {
            // Create a 500KB file
            File smallFile = tempDir.resolve("small.txt").toFile();
            String content = "x".repeat(1024 * 500);
            Files.write(smallFile.toPath(), content.getBytes());

            compressionHandler.compressFile(smallFile);
            assertThat(compressionHandler.getCacheSize()).isEqualTo(1); // Cached

            compressionHandler.compressFile(smallFile);
            assertThat(compressionHandler.getCacheHits()).isEqualTo(1); // Cache hit
        }

        @Test
        @DisplayName("Should cache file exactly at 1MB boundary")
        void testFileBoundary1MB(@TempDir Path tempDir) throws IOException {
            // Create exactly 1MB file
            File boundaryFile = tempDir.resolve("boundary.txt").toFile();
            String content = "x".repeat(1024 * 1024);
            Files.write(boundaryFile.toPath(), content.getBytes());

            compressionHandler.compressFile(boundaryFile);
            assertThat(compressionHandler.getCacheSize()).isEqualTo(1); // Cached
        }

        @Test
        @DisplayName("Should not cache file just over 1MB")
        void testFileJustOver1MB(@TempDir Path tempDir) throws IOException {
            // Create 1MB + 1 byte file
            File overFile = tempDir.resolve("over.txt").toFile();
            String content = "x".repeat(1024 * 1024 + 1);
            Files.write(overFile.toPath(), content.getBytes());

            compressionHandler.compressFile(overFile);
            assertThat(compressionHandler.getCacheSize()).isEqualTo(0); // Not cached
        }
    }

    @Nested
    @DisplayName("Cache Size Limits")
    class CacheSizeLimitTests {

        @Test
        @DisplayName("Should respect cache size limit of 1000 entries")
        void testCacheSizeLimit(@TempDir Path tempDir) throws IOException {
            // Create and compress 1100 files
            for (int i = 0; i < 1100; i++) {
                File file = tempDir.resolve("file_" + i + ".txt").toFile();
                String content = "Content " + i + " ".repeat(100);
                Files.write(file.toPath(), content.getBytes());
                compressionHandler.compressFile(file);
            }

            // Cache should be limited to 1000
            assertThat(compressionHandler.getCacheSize())
                .isLessThanOrEqualTo(1000)
                .isEqualTo(1000);
        }

        @Test
        @DisplayName("Should use LRU eviction when cache is full")
        void testLRUEviction(@TempDir Path tempDir) throws IOException {
            // Create and cache 1001 files
            File[] files = new File[1001];
            for (int i = 0; i < 1001; i++) {
                files[i] = tempDir.resolve("file_" + i + ".txt").toFile();
                String content = "Content " + i + " ".repeat(100);
                Files.write(files[i].toPath(), content.getBytes());
                compressionHandler.compressFile(files[i]);
            }

            // First file should have been evicted (LRU)
            long hitsBeforeRecompression = compressionHandler.getCacheHits();

            // Try to recompress first file - should be a cache miss
            compressionHandler.compressFile(files[0]);
            long hitsAfterRecompression = compressionHandler.getCacheHits();

            // Should be the same (no cache hit for first file)
            assertThat(hitsAfterRecompression).isEqualTo(hitsBeforeRecompression);
        }

        @Test
        @DisplayName("Should maintain cache integrity after evictions")
        void testCacheIntegrityAfterEviction(@TempDir Path tempDir) throws IOException {
            // Create and cache 1100 files
            File lastFile = null;
            for (int i = 0; i < 1100; i++) {
                File file = tempDir.resolve("file_" + i + ".txt").toFile();
                String content = "Content " + i + " ".repeat(100);
                Files.write(file.toPath(), content.getBytes());
                compressionHandler.compressFile(file);
                if (i == 1099) {
                    lastFile = file;
                }
            }

            // Cache should have 1000 entries
            assertThat(compressionHandler.getCacheSize()).isEqualTo(1000);

            // Recompress last file - should be cache hit
            long hitsBeforeLast = compressionHandler.getCacheHits();
            compressionHandler.compressFile(lastFile);
            long hitsAfterLast = compressionHandler.getCacheHits();

            assertThat(hitsAfterLast).isGreaterThan(hitsBeforeLast);
        }
    }

    @Nested
    @DisplayName("Performance")
    class PerformanceTests {

        @Test
        @DisplayName("Should be significantly faster for cache hits")
        void testCacheHitPerformance(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("test.txt").toFile();
            String content = "x".repeat(10000);
            Files.write(testFile.toPath(), content.getBytes());

            // Warm up cache
            compressionHandler.compressFile(testFile);

            // Measure cache hit performance
            long startTime = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                compressionHandler.compressFile(testFile);
            }
            long hitDuration = System.nanoTime() - startTime;
            double avgHitTimeMs = hitDuration / 100.0 / 1_000_000;

            // Clear cache and measure cache miss (actual compression) performance
            compressionHandler.clearCache();

            long startTime2 = System.nanoTime();
            for (int i = 0; i < 10; i++) {
                compressionHandler.compressFile(testFile);
            }
            long missDuration = System.nanoTime() - startTime2;
            double avgMissTimeMs = missDuration / 10.0 / 1_000_000;

            // Cache hit should be much faster
            assertThat(avgHitTimeMs).isLessThan(avgMissTimeMs);
            assertThat(avgMissTimeMs / avgHitTimeMs).isGreaterThan(5.0); // At least 5x faster
        }

        @Test
        @DisplayName("Should handle high concurrency with thread-safe cache")
        void testConcurrentCompressions(@TempDir Path tempDir) throws IOException, InterruptedException {
            File testFile = tempDir.resolve("test.txt").toFile();
            String content = "Concurrent test content ".repeat(500);
            Files.write(testFile.toPath(), content.getBytes());

            ExecutorService executor = Executors.newFixedThreadPool(10);
            CountDownLatch latch = new CountDownLatch(100);

            // Submit 100 concurrent compression tasks
            for (int i = 0; i < 100; i++) {
                executor.submit(() -> {
                    try {
                        compressionHandler.compressFile(testFile);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Wait for all tasks to complete
            boolean completed = latch.await(10, TimeUnit.SECONDS);
            assertThat(completed).isTrue();

            executor.shutdown();

            // Should have significant cache hits due to concurrent requests
            long totalRequests = compressionHandler.getCacheHits() + compressionHandler.getCacheMisses();
            assertThat(totalRequests).isEqualTo(100);
            assertThat(compressionHandler.getCacheHits()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should maintain consistency under concurrent modifications")
        void testConcurrentFileModifications(@TempDir Path tempDir) throws IOException, InterruptedException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "Initial content".repeat(500).getBytes());

            // Initial compression
            compressionHandler.compressFile(testFile);
            long initialMisses = compressionHandler.getCacheMisses();

            ExecutorService executor = Executors.newFixedThreadPool(5);
            CountDownLatch latch = new CountDownLatch(50);

            // 25 threads compressing cached file
            for (int i = 0; i < 25; i++) {
                executor.submit(() -> {
                    try {
                        compressionHandler.compressFile(testFile);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // 25 threads modifying and recompressing
            for (int i = 0; i < 25; i++) {
                executor.submit(() -> {
                    try {
                        Files.write(testFile.toPath(), ("Modified ".repeat(500) + System.nanoTime()).getBytes());
                        compressionHandler.compressFile(testFile);
                    } catch (IOException e) {
                        // Expected race condition - ignore
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(10, TimeUnit.SECONDS);
            assertThat(completed).isTrue();

            executor.shutdown();

            // Should handle concurrent access without errors
            long totalOps = compressionHandler.getCacheHits() + compressionHandler.getCacheMisses();
            assertThat(totalOps).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("Cache Management")
    class CacheManagementTests {

        @Test
        @DisplayName("Should clear cache and reset metrics")
        void testClearCache(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "test content".repeat(500).getBytes());

            // Populate cache
            compressionHandler.compressFile(testFile);
            compressionHandler.compressFile(testFile);

            assertThat(compressionHandler.getCacheSize()).isGreaterThan(0);
            assertThat(compressionHandler.getCacheHits()).isGreaterThan(0);

            // Clear cache
            compressionHandler.clearCache();

            assertThat(compressionHandler.getCacheSize()).isEqualTo(0);
            assertThat(compressionHandler.getCacheHits()).isEqualTo(0);
            assertThat(compressionHandler.getCacheMisses()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should report accurate cache size")
        void testCacheSize(@TempDir Path tempDir) throws IOException {
            assertThat(compressionHandler.getCacheSize()).isEqualTo(0);

            // Add files to cache
            for (int i = 0; i < 5; i++) {
                File file = tempDir.resolve("file_" + i + ".txt").toFile();
                Files.write(file.toPath(), ("Content " + i).repeat(500).getBytes());
                compressionHandler.compressFile(file);
            }

            assertThat(compressionHandler.getCacheSize()).isEqualTo(5);
        }

        @Test
        @DisplayName("Should calculate hit rate correctly")
        void testHitRateCalculation(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "test".repeat(500).getBytes());

            compressionHandler.clearCache();

            // 1 miss, 2 hits = 2/3 = 0.667
            compressionHandler.compressFile(testFile);
            compressionHandler.compressFile(testFile);
            compressionHandler.compressFile(testFile);

            double hitRate = compressionHandler.getCacheHitRate();
            assertThat(hitRate).isCloseTo(2.0 / 3.0, within(0.001));
        }

        @Test
        @DisplayName("Should handle compression of multiple different files")
        void testMultipleFileCaching(@TempDir Path tempDir) throws IOException {
            File file1 = tempDir.resolve("file1.txt").toFile();
            File file2 = tempDir.resolve("file2.txt").toFile();
            File file3 = tempDir.resolve("file3.txt").toFile();

            Files.write(file1.toPath(), "File 1 content".repeat(500).getBytes());
            Files.write(file2.toPath(), "File 2 content".repeat(500).getBytes());
            Files.write(file3.toPath(), "File 3 content".repeat(500).getBytes());

            // Compress each file twice
            compressionHandler.compressFile(file1);
            compressionHandler.compressFile(file2);
            compressionHandler.compressFile(file3);

            assertThat(compressionHandler.getCacheSize()).isEqualTo(3);
            assertThat(compressionHandler.getCacheMisses()).isEqualTo(3);
            assertThat(compressionHandler.getCacheHits()).isEqualTo(0);

            // Now hit them again
            compressionHandler.compressFile(file1);
            compressionHandler.compressFile(file2);
            compressionHandler.compressFile(file3);

            assertThat(compressionHandler.getCacheHits()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle files with same content but different paths")
        void testSameContentDifferentPaths(@TempDir Path tempDir) throws IOException {
            File file1 = tempDir.resolve("file1.txt").toFile();
            File file2 = tempDir.resolve("file2.txt").toFile();

            String sameContent = "Same content".repeat(500);
            Files.write(file1.toPath(), sameContent.getBytes());
            Files.write(file2.toPath(), sameContent.getBytes());

            compressionHandler.compressFile(file1);
            compressionHandler.compressFile(file2);

            // Should be 2 cache entries (different paths = different cache keys)
            assertThat(compressionHandler.getCacheSize()).isEqualTo(2);
            assertThat(compressionHandler.getCacheMisses()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should handle empty files (not cached due to MIN_COMPRESS_SIZE)")
        void testEmptyFileHandling(@TempDir Path tempDir) throws IOException {
            File emptyFile = tempDir.resolve("empty.txt").toFile();
            Files.write(emptyFile.toPath(), new byte[0]);

            byte[] result = compressionHandler.compressFile(emptyFile);

            // Empty files are still compressed, but may not be cached (depends on implementation)
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Should handle files with special characters in name")
        void testSpecialCharacterFilenames(@TempDir Path tempDir) throws IOException {
            File specialFile = tempDir.resolve("file-with_special.chars@2024.txt").toFile();
            Files.write(specialFile.toPath(), "content".repeat(500).getBytes());

            compressionHandler.compressFile(specialFile);
            compressionHandler.compressFile(specialFile);

            assertThat(compressionHandler.getCacheSize()).isEqualTo(1);
            assertThat(compressionHandler.getCacheHits()).isEqualTo(1);
        }
    }
}
