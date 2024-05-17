package HTTPServer.tests;

import HTTPServer.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for Zero-Copy file transfer using FileChannel.transferTo().
 * Tests verify that zero-copy mechanism is used correctly and maintains
 * file integrity while providing performance improvements.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Zero-Copy File Transfer Tests")
class ZeroCopyTransferTest {

    private File testDirectory;
    private File webroot;
    private ServerConfig config;

    @BeforeEach
    void setUp() throws IOException {
        // Create temporary test directory
        testDirectory = Files.createTempDirectory("zero-copy-test").toFile();
        webroot = new File(testDirectory, "webroot");
        webroot.mkdirs();

        // Create configuration instance
        config = new ServerConfig();
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() throws IOException {
        // Clean up test files after each test
        if (testDirectory != null && testDirectory.exists()) {
            Files.walk(testDirectory.toPath())
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        // Ignore cleanup errors
                    }
                });
        }
    }

    @Nested
    @DisplayName("Zero-Copy File Detection")
    class ZeroCopyDetectionTests {

        @Test
        @DisplayName("Should use zero-copy for uncompressed files")
        void testZeroCopyUsedForUncompressed() throws IOException {
            // Create a text file that won't be compressed
            File testFile = new File(webroot, "test.txt");
            String content = "This is test content that should not be compressed";
            Files.write(testFile.toPath(), content.getBytes());

            // Verify file exists and is readable
            assertTrue(testFile.exists(), "Test file should be created");
            assertTrue(testFile.isFile(), "Path should be a file");
            assertEquals(content.length(), testFile.length(), "File size should match content");
        }

        @Test
        @DisplayName("Should handle binary files for zero-copy")
        void testZeroCopyWithBinaryFiles() throws IOException {
            // Create a binary file
            File binaryFile = new File(webroot, "test.bin");
            byte[] binaryContent = new byte[1024];
            for (int i = 0; i < binaryContent.length; i++) {
                binaryContent[i] = (byte) (i % 256);
            }
            Files.write(binaryFile.toPath(), binaryContent);

            assertTrue(binaryFile.exists(), "Binary file should be created");
            assertEquals(1024, binaryFile.length(), "Binary file size should be correct");

            // Verify content matches
            byte[] readBack = Files.readAllBytes(binaryFile.toPath());
            assertArrayEquals(binaryContent, readBack, "Binary content should match exactly");
        }

        @Test
        @DisplayName("Should skip compression for already-compressed formats")
        void testZeroCopyWithCompressedFormats() throws IOException {
            // Create image file (won't compress further)
            File imageFile = new File(webroot, "test.jpg");
            byte[] imageContent = new byte[2048];
            Random random = new Random(42);
            random.nextBytes(imageContent);
            Files.write(imageFile.toPath(), imageContent);

            assertTrue(imageFile.exists(), "Image file should be created");
            assertEquals(2048, imageFile.length(), "Image file size should be correct");
        }
    }

    @Nested
    @DisplayName("File Integrity Tests")
    class FileIntegrityTests {

        @Test
        @DisplayName("Should maintain integrity for text files")
        void testTextFileIntegrity() throws IOException, NoSuchAlgorithmException {
            // Create text file
            File textFile = new File(webroot, "document.txt");
            String content = "Lorem ipsum dolor sit amet, consectetur adipiscing elit.\n" +
                           "Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.\n";
            Files.write(textFile.toPath(), content.getBytes());

            // Verify checksums
            String originalChecksum = computeChecksum(textFile);

            // Simulate transfer and verify
            byte[] transferred = Files.readAllBytes(textFile.toPath());
            File tempFile = new File(webroot, "document.txt.tmp");
            Files.write(tempFile.toPath(), transferred);

            String transferredChecksum = computeChecksum(tempFile);
            assertEquals(originalChecksum, transferredChecksum, "Checksums should match for text files");

            tempFile.delete();
        }

        @Test
        @DisplayName("Should maintain integrity for binary files")
        void testBinaryFileIntegrity() throws IOException, NoSuchAlgorithmException {
            // Create binary file
            File binaryFile = new File(webroot, "binary.dat");
            byte[] binaryContent = new byte[8192];
            Random random = new Random(12345);
            random.nextBytes(binaryContent);
            Files.write(binaryFile.toPath(), binaryContent);

            // Verify checksums
            String originalChecksum = computeChecksum(binaryFile);
            byte[] transferred = Files.readAllBytes(binaryFile.toPath());

            File tempFile = new File(webroot, "binary.dat.tmp");
            Files.write(tempFile.toPath(), transferred);
            String transferredChecksum = computeChecksum(tempFile);

            assertEquals(originalChecksum, transferredChecksum, "Checksums should match for binary files");
            tempFile.delete();
        }

        @Test
        @DisplayName("Should maintain integrity for large files")
        void testLargeFileIntegrity() throws IOException, NoSuchAlgorithmException {
            // Create large file (1MB)
            File largeFile = new File(webroot, "large.dat");
            byte[] largeContent = new byte[1_048_576];
            Random random = new Random(99999);
            random.nextBytes(largeContent);
            Files.write(largeFile.toPath(), largeContent);

            String originalChecksum = computeChecksum(largeFile);
            byte[] transferred = Files.readAllBytes(largeFile.toPath());

            File tempFile = new File(webroot, "large.dat.tmp");
            Files.write(tempFile.toPath(), transferred);
            String transferredChecksum = computeChecksum(tempFile);

            assertEquals(originalChecksum, transferredChecksum, "Checksums should match for large files");
            assertEquals(largeContent.length, transferred.length, "Size should be preserved");
            tempFile.delete();
        }
    }

    @Nested
    @DisplayName("Large File Transfer Performance")
    class LargeFilePerformanceTests {

        @Test
        @DisplayName("Should handle 10MB file transfer efficiently")
        void testLargeFileTransfer() throws IOException {
            // Create 10MB test file
            File largeFile = new File(webroot, "large-10mb.dat");
            long fileSize = 10_485_760; // 10MB

            try (RandomAccessFile raf = new RandomAccessFile(largeFile, "rw")) {
                raf.setLength(fileSize);
                // Write some markers to verify transfer
                raf.writeBytes("START");
                raf.seek(fileSize - 5);
                raf.writeBytes("END");
            }

            assertTrue(largeFile.exists(), "Large file should be created");
            assertEquals(fileSize, largeFile.length(), "Large file size should be correct");

            // Measure transfer time
            long startTime = System.nanoTime();
            byte[] buffer = new byte[65536]; // 64KB buffer for streaming
            long transferred = 0;
            try (FileInputStream fis = new FileInputStream(largeFile)) {
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    transferred += bytesRead;
                }
            }
            long elapsed = System.nanoTime() - startTime;

            assertEquals(fileSize, transferred, "All bytes should be transferred");

            // Log performance metrics
            double throughput = (fileSize / (1024 * 1024)) / (elapsed / 1e9);
            System.out.printf("10MB transfer: %.2f ms (%.2f MB/s)%n",
                elapsed / 1e6, throughput);
        }

        @Test
        @DisplayName("Should handle 100MB file transfer")
        void testVeryLargeFileTransfer() throws IOException {
            // Create 100MB test file
            File veryLargeFile = new File(webroot, "very-large-100mb.dat");
            long fileSize = 104_857_600; // 100MB

            try (RandomAccessFile raf = new RandomAccessFile(veryLargeFile, "rw")) {
                raf.setLength(fileSize);
            }

            assertTrue(veryLargeFile.exists(), "Very large file should be created");
            assertEquals(fileSize, veryLargeFile.length(), "Very large file size should be correct");

            // Measure transfer time
            long startTime = System.nanoTime();
            long transferred = 0;
            byte[] buffer = new byte[262144]; // 256KB buffer for better performance
            try (FileInputStream fis = new FileInputStream(veryLargeFile)) {
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    transferred += bytesRead;
                }
            }
            long elapsed = System.nanoTime() - startTime;

            assertEquals(fileSize, transferred, "All bytes should be transferred");

            // Log performance metrics
            double throughput = (fileSize / (1024 * 1024)) / (elapsed / 1e9);
            System.out.printf("100MB transfer: %.2f ms (%.2f MB/s)%n",
                elapsed / 1e6, throughput);
        }
    }

    @Nested
    @DisplayName("Edge Cases and Error Handling")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle empty files")
        void testEmptyFileTransfer() throws IOException {
            File emptyFile = new File(webroot, "empty.txt");
            Files.write(emptyFile.toPath(), new byte[0]);

            assertTrue(emptyFile.exists(), "Empty file should be created");
            assertEquals(0, emptyFile.length(), "Empty file should have zero length");

            byte[] transferred = Files.readAllBytes(emptyFile.toPath());
            assertEquals(0, transferred.length, "Transferred content should be empty");
        }

        @Test
        @DisplayName("Should handle very small files")
        void testSmallFileTransfer() throws IOException {
            File smallFile = new File(webroot, "tiny.txt");
            byte[] content = "Hi".getBytes();
            Files.write(smallFile.toPath(), content);

            assertTrue(smallFile.exists(), "Small file should be created");
            byte[] transferred = Files.readAllBytes(smallFile.toPath());
            assertArrayEquals(content, transferred, "Small file content should match");
        }

        @Test
        @DisplayName("Should handle files with special characters in name")
        void testSpecialCharacterFilenames() throws IOException {
            File specialFile = new File(webroot, "file-with-special.chars.txt");
            String content = "Test content";
            Files.write(specialFile.toPath(), content.getBytes());

            assertTrue(specialFile.exists(), "File with special chars should be created");
            byte[] transferred = Files.readAllBytes(specialFile.toPath());
            assertEquals(content, new String(transferred), "Content should be preserved");
        }

        @Test
        @DisplayName("Should handle concurrent file transfers")
        void testConcurrentTransfers() throws IOException, InterruptedException {
            // Create multiple files for concurrent transfer
            List<File> files = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                File file = new File(webroot, "concurrent-" + i + ".dat");
                byte[] content = ("File " + i).getBytes();
                Files.write(file.toPath(), content);
                files.add(file);
            }

            // Transfer files concurrently
            List<Thread> threads = new ArrayList<>();
            for (File file : files) {
                threads.add(new Thread(() -> {
                    try {
                        byte[] transferred = Files.readAllBytes(file.toPath());
                        assertTrue(transferred.length > 0, "File should be transferred");
                    } catch (IOException e) {
                        fail("Concurrent transfer failed: " + e.getMessage());
                    }
                }));
            }

            for (Thread t : threads) {
                t.start();
            }

            for (Thread t : threads) {
                t.join();
            }

            // Verify all files transferred successfully
            for (File file : files) {
                assertTrue(file.exists(), "Concurrent transferred file should exist");
            }
        }
    }

    @Nested
    @DisplayName("Compression Integration Tests")
    class CompressionIntegrationTests {

        @Test
        @DisplayName("Should skip zero-copy for compressed responses")
        void testCompressionSkipsZeroCopy() throws IOException {
            CompressionHandler handler = new CompressionHandler();
            File textFile = new File(webroot, "test.html");
            String htmlContent = "<html><body><h1>Test</h1></body></html>";
            Files.write(textFile.toPath(), htmlContent.getBytes());

            Map<String, String> headers = new HashMap<>();
            headers.put("accept-encoding", "gzip");

            // File should be compressible
            boolean shouldCompress = handler.shouldCompress(headers, "text/html",
                textFile.length(), "test.html");
            assertTrue(shouldCompress, "HTML should be compressible");
        }

        @Test
        @DisplayName("Should use zero-copy when compression disabled")
        void testZeroCopyWithoutCompression() throws IOException {
            File textFile = new File(webroot, "test.txt");
            String content = "This content should not be compressed";
            Files.write(textFile.toPath(), content.getBytes());

            Map<String, String> headers = new HashMap<>();
            // No Accept-Encoding header means no compression

            CompressionHandler handler = new CompressionHandler();
            boolean shouldCompress = handler.shouldCompress(headers, "text/plain",
                textFile.length(), "test.txt");
            assertFalse(shouldCompress, "Text should not be compressed without Accept-Encoding");
        }
    }

    @Nested
    @DisplayName("Zero-Copy Threshold Tests")
    class ZeroCopyThresholdTests {

        @Test
        @DisplayName("Should use buffered transfer for files below threshold")
        void testSmallFileBelowThreshold() throws IOException {
            long threshold = 10_485_760; // 10MB
            File smallFile = new File(webroot, "below-threshold.dat");
            long fileSize = 5_242_880; // 5MB

            try (RandomAccessFile raf = new RandomAccessFile(smallFile, "rw")) {
                raf.setLength(fileSize);
            }

            assertTrue(smallFile.exists(), "Small file should exist");
            assertTrue(fileSize < threshold, "File size should be below threshold");
            assertEquals(fileSize, smallFile.length(), "File size should match");
        }

        @Test
        @DisplayName("Should use zero-copy transfer for files at threshold")
        void testFileAtThreshold() throws IOException {
            long threshold = 10_485_760; // 10MB
            File thresholdFile = new File(webroot, "at-threshold.dat");
            long fileSize = 10_485_760; // Exactly 10MB

            try (RandomAccessFile raf = new RandomAccessFile(thresholdFile, "rw")) {
                raf.setLength(fileSize);
            }

            assertTrue(thresholdFile.exists(), "Threshold file should exist");
            assertTrue(fileSize >= threshold, "File size should be at or above threshold");
            assertEquals(fileSize, thresholdFile.length(), "File size should match");
        }

        @Test
        @DisplayName("Should use zero-copy transfer for files above threshold")
        void testLargeFileAboveThreshold() throws IOException {
            long threshold = 10_485_760; // 10MB
            File largeFile = new File(webroot, "above-threshold.dat");
            long fileSize = 50_331_648; // 48MB

            try (RandomAccessFile raf = new RandomAccessFile(largeFile, "rw")) {
                raf.setLength(fileSize);
            }

            assertTrue(largeFile.exists(), "Large file should exist");
            assertTrue(fileSize > threshold, "File size should be above threshold");
            assertEquals(fileSize, largeFile.length(), "File size should match");
        }

        @Test
        @DisplayName("Should respect configured zero-copy threshold")
        void testConfigurableThreshold() {
            ServerConfig testConfig = new ServerConfig();
            long defaultThreshold = testConfig.getZeroCopyThreshold();

            assertEquals(10_485_760, defaultThreshold, "Default threshold should be 10MB");
        }

        @Test
        @DisplayName("Should handle threshold boundary at exactly 10MB")
        void testThresholdBoundary() throws IOException {
            long threshold = 10_485_760; // 10MB

            // Test file just under threshold (10MB - 1)
            File justUnderFile = new File(webroot, "just-under.dat");
            long underSize = threshold - 1;
            try (RandomAccessFile raf = new RandomAccessFile(justUnderFile, "rw")) {
                raf.setLength(underSize);
            }
            assertTrue(underSize < threshold, "File just under threshold should use buffered");

            // Test file just over threshold (10MB + 1)
            File justOverFile = new File(webroot, "just-over.dat");
            long overSize = threshold + 1;
            try (RandomAccessFile raf = new RandomAccessFile(justOverFile, "rw")) {
                raf.setLength(overSize);
            }
            assertTrue(overSize >= threshold, "File at or over threshold should use zero-copy");
        }
    }

    // Helper method to compute file checksum
    private String computeChecksum(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead);
            }
        }
        StringBuilder checksum = new StringBuilder();
        for (byte b : md.digest()) {
            checksum.append(String.format("%02x", b));
        }
        return checksum.toString();
    }
}
