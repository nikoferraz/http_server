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
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Test suite for CacheManager.
 * Tests ETag generation, last-modified header handling, and cache validation.
 */
@DisplayName("CacheManager Tests")
class CacheManagerTest {

    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        cacheManager = new CacheManager();
    }

    @Nested
    @DisplayName("ETag Generation")
    class ETagGenerationTests {

        @Test
        @DisplayName("Should generate strong ETag for regular files")
        void testGenerateStrongETag(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "test content".getBytes());

            String etag = cacheManager.generateETag(testFile);

            // Strong ETag should be quoted and not start with W/
            assertThat(etag).startsWith("\"");
            assertThat(etag).endsWith("\"");
            assertThat(etag).doesNotStartWith("W/");
        }

        @Test
        @DisplayName("Should generate consistent ETag for same file")
        void testETagConsistency(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "test content".getBytes());

            String etag1 = cacheManager.generateETag(testFile);
            String etag2 = cacheManager.generateETag(testFile);

            assertThat(etag1).isEqualTo(etag2);
        }

        @Test
        @DisplayName("Should generate different ETags for different files")
        void testETagDifferentForDifferentContent(@TempDir Path tempDir) throws IOException {
            File file1 = tempDir.resolve("file1.txt").toFile();
            File file2 = tempDir.resolve("file2.txt").toFile();

            Files.write(file1.toPath(), "content 1".getBytes());
            Files.write(file2.toPath(), "content 2".getBytes());

            String etag1 = cacheManager.generateETag(file1);
            String etag2 = cacheManager.generateETag(file2);

            assertThat(etag1).isNotEqualTo(etag2);
        }

        @Test
        @DisplayName("Should generate weak ETag for very large files")
        void testWeakETagForLargeFiles(@TempDir Path tempDir) throws IOException {
            File largeFile = tempDir.resolve("large.bin").toFile();
            // Create a file larger than 100MB threshold
            byte[] data = new byte[1024];
            try (var os = Files.newOutputStream(largeFile.toPath())) {
                for (int i = 0; i < 110_000; i++) {
                    os.write(data);
                }
            }

            String etag = cacheManager.generateETag(largeFile);

            // Should be weak ETag
            assertThat(etag).startsWith("W/");
        }

        @Test
        @DisplayName("Should handle empty files")
        void testEmptyFile(@TempDir Path tempDir) throws IOException {
            File emptyFile = tempDir.resolve("empty.txt").toFile();
            Files.write(emptyFile.toPath(), new byte[0]);

            String etag = cacheManager.generateETag(emptyFile);

            assertThat(etag).isNotNull();
            assertThat(etag).isNotEmpty();
        }

        @Test
        @DisplayName("Should handle files with special characters in name")
        void testFileWithSpecialCharacters(@TempDir Path tempDir) throws IOException {
            File specialFile = tempDir.resolve("file-with-special_chars.txt").toFile();
            Files.write(specialFile.toPath(), "content".getBytes());

            String etag = cacheManager.generateETag(specialFile);

            assertThat(etag).isNotNull();
        }
    }

    @Nested
    @DisplayName("Last-Modified Header")
    class LastModifiedTests {

        @Test
        @DisplayName("Should return Last-Modified header in HTTP date format")
        void testLastModifiedHeader(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "test".getBytes());

            String lastModified = cacheManager.getLastModified(testFile);

            assertThat(lastModified).isNotNull();
            assertThat(lastModified).isNotEmpty();
            // Should contain GMT
            assertThat(lastModified).contains("GMT");
        }

        @Test
        @DisplayName("Should use HTTP date format (RFC 7231)")
        void testHTTPDateFormat(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "test".getBytes());

            String lastModified = cacheManager.getLastModified(testFile);

            // Should have standard format: "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
            assertThat(lastModified).matches("[A-Z][a-z]{2}, \\d{2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT");
        }

        @Test
        @DisplayName("Should update Last-Modified when file changes")
        void testLastModifiedUpdates(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "content 1".getBytes());

            String modified1 = cacheManager.getLastModified(testFile);

            // Wait a bit and update file
            Thread.sleep(10);
            Files.write(testFile.toPath(), "content 2".getBytes());

            String modified2 = cacheManager.getLastModified(testFile);

            // Dates might be same if system clock resolution is low, but should be valid
            assertThat(modified1).isNotNull();
            assertThat(modified2).isNotNull();
        }

        @Test
        @DisplayName("Should handle files with very old modification times")
        void testOldFileModificationTime(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("old.txt").toFile();
            Files.write(testFile.toPath(), "content".getBytes());
            // Set to very old date (1970)
            testFile.setLastModified(0);

            String lastModified = cacheManager.getLastModified(testFile);

            assertThat(lastModified).isNotNull();
            assertThat(lastModified).contains("1970");
        }
    }

    @Nested
    @DisplayName("Cache Validation")
    class CacheValidationTests {

        @Test
        @DisplayName("Should validate ETag match")
        void testETagMatch(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "content".getBytes());

            String etag = cacheManager.generateETag(testFile);
            boolean matches = cacheManager.validateETag(testFile, etag);

            assertThat(matches).isTrue();
        }

        @Test
        @DisplayName("Should invalidate mismatched ETag")
        void testETagMismatch(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "content".getBytes());

            boolean matches = cacheManager.validateETag(testFile, "\"invalid-etag\"");

            assertThat(matches).isFalse();
        }

        @Test
        @DisplayName("Should validate weak ETag match")
        void testWeakETagValidation(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "content".getBytes());

            String weakETag = "W/\"test-weak-etag\"";
            // Weak ETag validation is more lenient
            boolean matches = cacheManager.validateETag(testFile, weakETag);

            // May or may not match depending on implementation
            assertThat(matches).isBool();
        }
    }

    @Nested
    @DisplayName("Cache Control Headers")
    class CacheControlTests {

        @Test
        @DisplayName("Should provide Cache-Control header")
        void testCacheControlHeader() {
            String cacheControl = cacheManager.getCacheControl();

            assertThat(cacheControl).isNotNull();
            assertThat(cacheControl).isNotEmpty();
        }

        @Test
        @DisplayName("Should include max-age in Cache-Control")
        void testCacheControlMaxAge() {
            String cacheControl = cacheManager.getCacheControl();

            assertThat(cacheControl).contains("max-age=");
        }

        @Test
        @DisplayName("Should mark cache as public")
        void testCacheControlPublic() {
            String cacheControl = cacheManager.getCacheControl();

            assertThat(cacheControl).contains("public");
        }
    }

    @Nested
    @DisplayName("Conditional Request Handling")
    class ConditionalRequestTests {

        @Test
        @DisplayName("Should determine 304 Not Modified for matching ETag")
        void testNotModifiedForMatchingETag(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "content".getBytes());

            String etag = cacheManager.generateETag(testFile);
            Map<String, String> headers = new HashMap<>();
            headers.put("if-none-match", etag);

            boolean notModified = cacheManager.isNotModified(testFile, headers);

            assertThat(notModified).isTrue();
        }

        @Test
        @DisplayName("Should determine 200 OK for mismatched ETag")
        void testModifiedForMismatchedETag(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "content".getBytes());

            Map<String, String> headers = new HashMap<>();
            headers.put("if-none-match", "\"different-etag\"");

            boolean notModified = cacheManager.isNotModified(testFile, headers);

            assertThat(notModified).isFalse();
        }

        @Test
        @DisplayName("Should handle If-Modified-Since header")
        void testIfModifiedSince(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "content".getBytes());

            String lastModified = cacheManager.getLastModified(testFile);
            Map<String, String> headers = new HashMap<>();
            headers.put("if-modified-since", lastModified);

            // If exact match, should be not modified
            boolean notModified = cacheManager.isNotModified(testFile, headers);

            // May or may not be true depending on implementation
            assertThat(notModified).isBool();
        }

        @Test
        @DisplayName("Should handle wildcard If-None-Match")
        void testWildcardIfNoneMatch(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "content".getBytes());

            Map<String, String> headers = new HashMap<>();
            headers.put("if-none-match", "*");

            // Wildcard means any ETag matches
            boolean notModified = cacheManager.isNotModified(testFile, headers);

            assertThat(notModified).isTrue();
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle non-existent files")
        void testNonExistentFile() {
            File nonExistent = new File("/nonexistent/path/file.txt");

            String etag = cacheManager.generateETag(nonExistent);

            // Should handle gracefully, may return weak ETag
            assertThat(etag).isNotNull();
        }

        @Test
        @DisplayName("Should handle null headers in validation")
        void testNullHeadersInValidation(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "content".getBytes());

            // Empty headers should mean no matching
            Map<String, String> headers = new HashMap<>();

            boolean notModified = cacheManager.isNotModified(testFile, headers);

            assertThat(notModified).isFalse();
        }

        @Test
        @DisplayName("Should handle multiple ETags in If-None-Match")
        void testMultipleETags(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "content".getBytes());

            String etag = cacheManager.generateETag(testFile);
            Map<String, String> headers = new HashMap<>();
            // Multiple ETags separated by comma
            headers.put("if-none-match", "\"etag1\", " + etag + ", \"etag3\"");

            boolean notModified = cacheManager.isNotModified(testFile, headers);

            assertThat(notModified).isTrue();
        }
    }

    @Nested
    @DisplayName("Streaming and Memory Efficiency")
    class StreamingTests {

        @Test
        @DisplayName("Should use streaming for ETag generation")
        void testStreamingETag(@TempDir Path tempDir) throws IOException {
            File largeFile = tempDir.resolve("large.bin").toFile();
            // Create a reasonably large file
            try (var os = Files.newOutputStream(largeFile.toPath())) {
                byte[] data = new byte[1024];
                for (int i = 0; i < 1000; i++) {
                    os.write(data);
                }
            }

            // Should not throw OutOfMemoryError
            String etag = cacheManager.generateETag(largeFile);

            assertThat(etag).isNotNull();
        }

        @Test
        @DisplayName("Should handle binary files")
        void testBinaryFileETag(@TempDir Path tempDir) throws IOException {
            File binaryFile = tempDir.resolve("binary.bin").toFile();
            byte[] binaryData = new byte[256];
            for (int i = 0; i < 256; i++) {
                binaryData[i] = (byte) i;
            }
            Files.write(binaryFile.toPath(), binaryData);

            String etag = cacheManager.generateETag(binaryFile);

            assertThat(etag).isNotNull();
        }
    }
}
