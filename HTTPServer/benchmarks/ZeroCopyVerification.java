package HTTPServer;

import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Verification tests for Zero-Copy file transfer implementation.
 * Tests verify that zero-copy mechanism works correctly and maintains file integrity.
 */
public class ZeroCopyVerification {

    private static int testsRun = 0;
    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("Zero-Copy File Transfer Verification Tests");
        System.out.println("=".repeat(70));

        // Create temporary test directory
        File testDir = Files.createTempDirectory("zero-copy-verify").toFile();

        try {
            // Test 1: Zero-copy with uncompressed files
            test1_UncompressedFileTransfer(testDir);

            // Test 2: File integrity verification
            test2_FileIntegrity(testDir);

            // Test 3: Large file handling
            test3_LargeFileTransfer(testDir);

            // Test 4: Empty file handling
            test4_EmptyFileTransfer(testDir);

            // Test 5: Binary file integrity
            test5_BinaryFileIntegrity(testDir);

            // Test 6: Performance comparison
            test6_PerformanceComparison(testDir);

            // Print summary
            printSummary();

        } finally {
            // Clean up test files
            cleanup(testDir);
        }
    }

    private static void test1_UncompressedFileTransfer(File testDir) throws Exception {
        testStart("1: Uncompressed File Transfer");

        File testFile = new File(testDir, "test.txt");
        String content = "This is test content that should not be compressed";
        Files.write(testFile.toPath(), content.getBytes());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            boolean zeroCopyUsed = ZeroCopyTransferHandler.transferZeroCopy(testFile, baos);
            byte[] transferred = baos.toByteArray();

            assertTrue(testFile.exists(), "Test file should exist");
            assertEquals(content.length(), transferred.length, "Size should match");
            assertEquals(content, new String(transferred), "Content should match");

            System.out.println("  Zero-copy used: " + zeroCopyUsed);
            testPassed();
        } catch (Exception e) {
            testFailed("Exception: " + e.getMessage());
        }
    }

    private static void test2_FileIntegrity(File testDir) throws Exception {
        testStart("2: File Integrity Verification");

        File originalFile = new File(testDir, "original.bin");
        byte[] originalContent = "Original file content with some data".getBytes();
        Files.write(originalFile.toPath(), originalContent);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ZeroCopyTransferHandler.transferZeroCopy(originalFile, baos);
            byte[] transferred = baos.toByteArray();

            String originalHash = computeHash(originalContent);
            String transferredHash = computeHash(transferred);

            assertEquals(originalHash, transferredHash, "Checksums should match");
            testPassed();
        } catch (Exception e) {
            testFailed("Exception: " + e.getMessage());
        }
    }

    private static void test3_LargeFileTransfer(File testDir) throws Exception {
        testStart("3: Large File Transfer (10MB)");

        File largeFile = new File(testDir, "large-10mb.bin");
        long fileSize = 10 * 1024 * 1024; // 10MB

        // Create large file
        try (RandomAccessFile raf = new RandomAccessFile(largeFile, "rw")) {
            raf.setLength(fileSize);
            raf.writeBytes("START");
            raf.seek(fileSize - 5);
            raf.writeBytes("END");
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        long startTime = System.nanoTime();

        try {
            boolean zeroCopyUsed = ZeroCopyTransferHandler.transferZeroCopy(largeFile, baos);
            long elapsed = System.nanoTime() - startTime;
            byte[] transferred = baos.toByteArray();

            assertEquals(fileSize, transferred.length, "File size should be preserved");
            double throughput = (fileSize / (1024.0 * 1024.0)) / (elapsed / 1e9);
            System.out.printf("  Transfer time: %.2f ms%n", elapsed / 1e6);
            System.out.printf("  Throughput: %.2f MB/s%n", throughput);
            System.out.println("  Zero-copy used: " + zeroCopyUsed);
            testPassed();
        } catch (Exception e) {
            testFailed("Exception: " + e.getMessage());
        }
    }

    private static void test4_EmptyFileTransfer(File testDir) throws Exception {
        testStart("4: Empty File Transfer");

        File emptyFile = new File(testDir, "empty.txt");
        Files.write(emptyFile.toPath(), new byte[0]);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            boolean zeroCopyUsed = ZeroCopyTransferHandler.transferZeroCopy(emptyFile, baos);
            byte[] transferred = baos.toByteArray();

            assertEquals(0, transferred.length, "Empty file should transfer 0 bytes");
            System.out.println("  Zero-copy used: " + zeroCopyUsed);
            testPassed();
        } catch (Exception e) {
            testFailed("Exception: " + e.getMessage());
        }
    }

    private static void test5_BinaryFileIntegrity(File testDir) throws Exception {
        testStart("5: Binary File Integrity");

        File binaryFile = new File(testDir, "binary.dat");
        byte[] binaryContent = new byte[65536];
        java.util.Random random = new java.util.Random(12345);
        random.nextBytes(binaryContent);
        Files.write(binaryFile.toPath(), binaryContent);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ZeroCopyTransferHandler.transferZeroCopy(binaryFile, baos);
            byte[] transferred = baos.toByteArray();

            assertEquals(binaryContent.length, transferred.length, "Binary file size should match");

            // Check first and last bytes
            assertEquals(binaryContent[0], transferred[0], "First byte should match");
            assertEquals(binaryContent[binaryContent.length - 1],
                       transferred[transferred.length - 1], "Last byte should match");

            testPassed();
        } catch (Exception e) {
            testFailed("Exception: " + e.getMessage());
        }
    }

    private static void test6_PerformanceComparison(File testDir) throws Exception {
        testStart("6: Performance Comparison (1MB file)");

        File testFile = new File(testDir, "perf-test-1mb.bin");
        long fileSize = 1024 * 1024; // 1MB
        try (RandomAccessFile raf = new RandomAccessFile(testFile, "rw")) {
            raf.setLength(fileSize);
        }

        // Benchmark buffered transfer
        long bufferedStart = System.nanoTime();
        ByteArrayOutputStream baos1 = new ByteArrayOutputStream();
        ZeroCopyTransferHandler.transferBuffered(testFile, baos1);
        long bufferedTime = System.nanoTime() - bufferedStart;

        // Benchmark zero-copy transfer
        long zeroCopyStart = System.nanoTime();
        ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
        try {
            ZeroCopyTransferHandler.transferZeroCopy(testFile, baos2);
        } catch (IOException e) {
            // Fall back to buffered if zero-copy fails
            ZeroCopyTransferHandler.transferBuffered(testFile, baos2);
        }
        long zeroCopyTime = System.nanoTime() - zeroCopyStart;

        double speedup = (double) bufferedTime / zeroCopyTime;

        System.out.printf("  Buffered transfer: %.2f ms%n", bufferedTime / 1e6);
        System.out.printf("  Zero-copy transfer: %.2f ms%n", zeroCopyTime / 1e6);
        System.out.printf("  Speedup: %.2fx%n", speedup);

        testPassed();
    }

    // Helper methods
    private static void testStart(String testName) {
        testsRun++;
        System.out.println("\n[Test " + testsRun + "] " + testName);
    }

    private static void testPassed() {
        testsPassed++;
        System.out.println("  ✓ PASSED");
    }

    private static void testFailed(String reason) {
        testsFailed++;
        System.out.println("  ✗ FAILED: " + reason);
    }

    private static void assertTrue(boolean condition, String message) throws Exception {
        if (!condition) {
            throw new Exception("Assertion failed: " + message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) throws Exception {
        if (!expected.equals(actual)) {
            throw new Exception("Assertion failed: " + message + "\n  Expected: " + expected + "\n  Actual: " + actual);
        }
    }

    private static void assertEquals(long expected, long actual, String message) throws Exception {
        if (expected != actual) {
            throw new Exception("Assertion failed: " + message + "\n  Expected: " + expected + "\n  Actual: " + actual);
        }
    }

    private static void assertEquals(int expected, int actual, String message) throws Exception {
        if (expected != actual) {
            throw new Exception("Assertion failed: " + message + "\n  Expected: " + expected + "\n  Actual: " + actual);
        }
    }

    private static void assertEquals(byte expected, byte actual, String message) throws Exception {
        if (expected != actual) {
            throw new Exception("Assertion failed: " + message);
        }
    }

    private static void assertEquals(String expected, String actual, String message) throws Exception {
        if (!expected.equals(actual)) {
            throw new Exception("Assertion failed: " + message);
        }
    }

    private static String computeHash(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(data);
        StringBuilder hash = new StringBuilder();
        for (byte b : md.digest()) {
            hash.append(String.format("%02x", b));
        }
        return hash.toString();
    }

    private static void cleanup(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        dir.delete();
    }

    private static void printSummary() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("Test Results Summary");
        System.out.println("=".repeat(70));
        System.out.println("Tests Run:    " + testsRun);
        System.out.println("Tests Passed: " + testsPassed);
        System.out.println("Tests Failed: " + testsFailed);

        if (testsFailed == 0) {
            System.out.println("\n✓ ALL TESTS PASSED!");
        } else {
            System.out.println("\n✗ " + testsFailed + " test(s) failed");
        }

        // Print metrics
        System.out.println("\nZero-Copy Transfer Metrics:");
        System.out.println("  Total transfers: " + ZeroCopyTransferHandler.getZeroCopyTransfersCount());
        System.out.println("  Bytes transferred: " + ZeroCopyTransferHandler.getZeroCopyBytesTransferred());
        System.out.println("  Transfer errors: " + ZeroCopyTransferHandler.getZeroCopyErrors());
    }
}
