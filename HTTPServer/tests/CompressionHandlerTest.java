package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.*;

/**
 * Test suite for CompressionHandler.
 * Tests Gzip compression decisions, compression ratio, and valid gzip output.
 */
@DisplayName("CompressionHandler Tests")
class CompressionHandlerTest {

    private CompressionHandler compressionHandler;

    @BeforeEach
    void setUp() {
        compressionHandler = new CompressionHandler();
    }

    @Nested
    @DisplayName("Compression Decision")
    class CompressionDecisionTests {

        @Test
        @DisplayName("Should compress HTML when Accept-Encoding includes gzip")
        void testCompressHtmlWithGzip() {
            Map<String, String> headers = new HashMap<>();
            headers.put("accept-encoding", "gzip, deflate");

            boolean shouldCompress = compressionHandler.shouldCompress(
                headers, "text/html", 1000, "index.html"
            );

            assertThat(shouldCompress).isTrue();
        }

        @Test
        @DisplayName("Should not compress when Accept-Encoding excludes gzip")
        void testNoCompressionWithoutGzip() {
            Map<String, String> headers = new HashMap<>();
            headers.put("accept-encoding", "deflate");

            boolean shouldCompress = compressionHandler.shouldCompress(
                headers, "text/html", 1000, "index.html"
            );

            assertThat(shouldCompress).isFalse();
        }

        @Test
        @DisplayName("Should not compress small files")
        void testNoCompressionForSmallFiles() {
            Map<String, String> headers = new HashMap<>();
            headers.put("accept-encoding", "gzip");

            // Only 100 bytes, below MIN_COMPRESS_SIZE (256)
            boolean shouldCompress = compressionHandler.shouldCompress(
                headers, "text/plain", 100, "small.txt"
            );

            assertThat(shouldCompress).isFalse();
        }

        @Test
        @DisplayName("Should compress files at or above minimum compression size")
        void testCompressionAboveMinimumSize() {
            Map<String, String> headers = new HashMap<>();
            headers.put("accept-encoding", "gzip");

            boolean shouldCompress = compressionHandler.shouldCompress(
                headers, "text/plain", 256, "file.txt"
            );

            assertThat(shouldCompress).isTrue();
        }

        @Test
        @DisplayName("Should not compress already compressed files")
        void testNoCompressionForPrecompressedFiles() {
            Map<String, String> headers = new HashMap<>();
            headers.put("accept-encoding", "gzip");

            // Test various pre-compressed extensions
            String[] precompressed = {".jpg", ".png", ".gif", ".zip", ".gz", ".mp4"};

            for (String ext : precompressed) {
                boolean shouldCompress = compressionHandler.shouldCompress(
                    headers, "image/jpeg", 5000, "file" + ext
                );
                assertThat(shouldCompress)
                    .as("Should not compress " + ext)
                    .isFalse();
            }
        }

        @Test
        @DisplayName("Should not compress non-compressible MIME types")
        void testNoCompressionForBinaryTypes() {
            Map<String, String> headers = new HashMap<>();
            headers.put("accept-encoding", "gzip");

            // Test binary types that shouldn't be compressed
            String[] binaryTypes = {"image/jpeg", "image/png", "image/gif", "video/mp4", "audio/mp3"};

            for (String mimeType : binaryTypes) {
                boolean shouldCompress = compressionHandler.shouldCompress(
                    headers, mimeType, 5000, "file.bin"
                );
                assertThat(shouldCompress)
                    .as("Should not compress " + mimeType)
                    .isFalse();
            }
        }

        @Test
        @DisplayName("Should compress text-based MIME types")
        void testCompressionForTextTypes() {
            Map<String, String> headers = new HashMap<>();
            headers.put("accept-encoding", "gzip");

            String[] textTypes = {
                "text/html",
                "text/plain",
                "text/css",
                "text/javascript",
                "application/json",
                "application/javascript",
                "application/xml"
            };

            for (String mimeType : textTypes) {
                boolean shouldCompress = compressionHandler.shouldCompress(
                    headers, mimeType, 5000, "file.txt"
                );
                assertThat(shouldCompress)
                    .as("Should compress " + mimeType)
                    .isTrue();
            }
        }

        @Test
        @DisplayName("Should handle case-insensitive Accept-Encoding")
        void testCaseInsensitiveAcceptEncoding() {
            Map<String, String> headers = new HashMap<>();
            headers.put("accept-encoding", "GZIP, deflate");

            boolean shouldCompress = compressionHandler.shouldCompress(
                headers, "text/html", 1000, "index.html"
            );

            assertThat(shouldCompress).isTrue();
        }

        @Test
        @DisplayName("Should handle missing Accept-Encoding header")
        void testMissingAcceptEncoding() {
            Map<String, String> headers = new HashMap<>();

            boolean shouldCompress = compressionHandler.shouldCompress(
                headers, "text/html", 1000, "index.html"
            );

            assertThat(shouldCompress).isFalse();
        }
    }

    @Nested
    @DisplayName("Compression Execution")
    class CompressionExecutionTests {

        @Test
        @DisplayName("Should compress text data")
        void testCompressTextData() throws IOException {
            String originalText = "Hello, World! ".repeat(100);
            byte[] originalBytes = originalText.getBytes();

            byte[] compressedBytes = compressionHandler.compress(originalBytes);

            assertThat(compressedBytes).isNotNull();
            assertThat(compressedBytes.length).isLessThan(originalBytes.length);
        }

        @Test
        @DisplayName("Should produce valid gzip output")
        void testValidGzipOutput() throws IOException {
            String originalText = "Test content for compression";
            byte[] originalBytes = originalText.getBytes();

            byte[] compressedBytes = compressionHandler.compress(originalBytes);

            // Should start with gzip magic number (1f 8b)
            assertThat(compressedBytes).isNotEmpty();
            assertThat(compressedBytes[0]).isEqualTo((byte) 0x1f);
            assertThat(compressedBytes[1]).isEqualTo((byte) 0x8b);
        }

        @Test
        @DisplayName("Should decompress to original content")
        void testRoundTripCompression() throws IOException {
            String originalText = "This is test content for round-trip compression testing";
            byte[] originalBytes = originalText.getBytes();

            byte[] compressedBytes = compressionHandler.compress(originalBytes);

            // Decompress and verify
            ByteArrayInputStream bais = new ByteArrayInputStream(compressedBytes);
            GZIPInputStream gzis = new GZIPInputStream(bais);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = gzis.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }

            byte[] decompressedBytes = baos.toByteArray();
            String decompressedText = new String(decompressedBytes);

            assertThat(decompressedText).isEqualTo(originalText);
        }

        @Test
        @DisplayName("Should achieve reasonable compression ratio for text")
        void testCompressionRatio() throws IOException {
            // Highly compressible text (repetitive)
            String repetitiveText = "a".repeat(10000);
            byte[] originalBytes = repetitiveText.getBytes();

            byte[] compressedBytes = compressionHandler.compress(originalBytes);

            double ratio = (double) compressedBytes.length / originalBytes.length;
            // Should achieve at least 50% compression for highly repetitive text
            assertThat(ratio).isLessThan(0.5);
        }

        @Test
        @DisplayName("Should handle empty data")
        void testCompressEmptyData() throws IOException {
            byte[] emptyData = new byte[0];

            byte[] compressed = compressionHandler.compress(emptyData);

            // Should produce valid gzip even for empty data
            assertThat(compressed).isNotEmpty();
        }

        @Test
        @DisplayName("Should handle large data")
        void testCompressLargeData() throws IOException {
            // Create 1MB of text
            String largeText = "x".repeat(1024 * 1024);
            byte[] originalBytes = largeText.getBytes();

            byte[] compressedBytes = compressionHandler.compress(originalBytes);

            assertThat(compressedBytes).isNotNull();
            assertThat(compressedBytes.length).isLessThan(originalBytes.length);
        }
    }

    @Nested
    @DisplayName("MIME Type Detection")
    class MimeTypeTests {

        @Test
        @DisplayName("Should handle MIME types with charset")
        void testMimeTypeWithCharset() {
            Map<String, String> headers = new HashMap<>();
            headers.put("accept-encoding", "gzip");

            boolean shouldCompress = compressionHandler.shouldCompress(
                headers, "text/html; charset=utf-8", 1000, "index.html"
            );

            assertThat(shouldCompress).isTrue();
        }

        @Test
        @DisplayName("Should handle MIME types with parameters")
        void testMimeTypeWithParameters() {
            Map<String, String> headers = new HashMap<>();
            headers.put("accept-encoding", "gzip");

            boolean shouldCompress = compressionHandler.shouldCompress(
                headers, "application/json; charset=utf-8", 1000, "data.json"
            );

            assertThat(shouldCompress).isTrue();
        }

        @Test
        @DisplayName("Should handle null MIME type")
        void testNullMimeType() {
            Map<String, String> headers = new HashMap<>();
            headers.put("accept-encoding", "gzip");

            boolean shouldCompress = compressionHandler.shouldCompress(
                headers, null, 1000, "file"
            );

            assertThat(shouldCompress).isFalse();
        }

        @Test
        @DisplayName("Should handle empty MIME type")
        void testEmptyMimeType() {
            Map<String, String> headers = new HashMap<>();
            headers.put("accept-encoding", "gzip");

            boolean shouldCompress = compressionHandler.shouldCompress(
                headers, "", 1000, "file"
            );

            assertThat(shouldCompress).isFalse();
        }
    }

    @Nested
    @DisplayName("File Extension Handling")
    class FileExtensionTests {

        @Test
        @DisplayName("Should recognize common pre-compressed extensions")
        void testCommonPrecompressedExtensions() {
            Map<String, String> headers = new HashMap<>();
            headers.put("accept-encoding", "gzip");

            String[] extensions = {".jpg", ".jpeg", ".png", ".gif", ".zip", ".gz", ".7z", ".pdf"};

            for (String ext : extensions) {
                boolean shouldCompress = compressionHandler.shouldCompress(
                    headers, "application/octet-stream", 5000, "file" + ext
                );
                assertThat(shouldCompress)
                    .as("Should not compress " + ext)
                    .isFalse();
            }
        }

        @Test
        @DisplayName("Should handle files without extensions")
        void testFileWithoutExtension() {
            Map<String, String> headers = new HashMap<>();
            headers.put("accept-encoding", "gzip");

            boolean shouldCompress = compressionHandler.shouldCompress(
                headers, "text/plain", 1000, "README"
            );

            assertThat(shouldCompress).isTrue();
        }

        @Test
        @DisplayName("Should handle uppercase extensions")
        void testUppercaseExtensions() {
            Map<String, String> headers = new HashMap<>();
            headers.put("accept-encoding", "gzip");

            boolean shouldCompress = compressionHandler.shouldCompress(
                headers, "image/jpeg", 5000, "IMAGE.JPG"
            );

            // Should still recognize as pre-compressed
            assertThat(shouldCompress).isFalse();
        }

        @Test
        @DisplayName("Should handle double extensions")
        void testDoubleExtensions() {
            Map<String, String> headers = new HashMap<>();
            headers.put("accept-encoding", "gzip");

            // .tar.gz should not be compressed
            boolean shouldCompress = compressionHandler.shouldCompress(
                headers, "application/gzip", 5000, "archive.tar.gz"
            );

            assertThat(shouldCompress).isFalse();
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle boundary case file sizes")
        void testBoundarySizeFiles() {
            Map<String, String> headers = new HashMap<>();
            headers.put("accept-encoding", "gzip");

            // Exactly at threshold (256 bytes)
            boolean shouldCompress256 = compressionHandler.shouldCompress(
                headers, "text/plain", 256, "file.txt"
            );
            assertThat(shouldCompress256).isTrue();

            // Just below threshold (255 bytes)
            boolean shouldCompress255 = compressionHandler.shouldCompress(
                headers, "text/plain", 255, "file.txt"
            );
            assertThat(shouldCompress255).isFalse();
        }

        @Test
        @DisplayName("Should handle very large files")
        void testVeryLargeFiles() {
            Map<String, String> headers = new HashMap<>();
            headers.put("accept-encoding", "gzip");

            boolean shouldCompress = compressionHandler.shouldCompress(
                headers, "text/html", 1024 * 1024 * 100, "large.html"
            );

            assertThat(shouldCompress).isTrue();
        }

        @Test
        @DisplayName("Should handle Accept-Encoding with quality values")
        void testAcceptEncodingWithQuality() {
            Map<String, String> headers = new HashMap<>();
            headers.put("accept-encoding", "gzip;q=0.8, deflate;q=0.5");

            boolean shouldCompress = compressionHandler.shouldCompress(
                headers, "text/html", 1000, "index.html"
            );

            // Should still recognize gzip
            assertThat(shouldCompress).isTrue();
        }

        @Test
        @DisplayName("Should handle whitespace in Accept-Encoding")
        void testAcceptEncodingWhitespace() {
            Map<String, String> headers = new HashMap<>();
            headers.put("accept-encoding", "  gzip  ,  deflate  ");

            boolean shouldCompress = compressionHandler.shouldCompress(
                headers, "text/html", 1000, "index.html"
            );

            // Should handle whitespace gracefully
            assertThat(shouldCompress).isTrue();
        }
    }
}
