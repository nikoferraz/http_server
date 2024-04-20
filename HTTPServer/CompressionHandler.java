package HTTPServer;

import java.io.*;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Handles response compression using Gzip.
 * Compresses text-based content types and skips binary/pre-compressed files.
 */
public class CompressionHandler {

    private static final Logger logger = Logger.getLogger(CompressionHandler.class.getName());

    // Minimum size in bytes to compress (no benefit for very small files)
    private static final int MIN_COMPRESS_SIZE = 256;

    // Content types that should be compressed
    private static final String[] COMPRESSIBLE_TYPES = {
        "text/html",
        "text/css",
        "text/javascript",
        "text/plain",
        "text/xml",
        "application/json",
        "application/javascript",
        "application/xml",
        "application/xhtml+xml",
        "application/rss+xml",
        "application/atom+xml"
    };

    // File extensions that are already compressed
    private static final String[] PRECOMPRESSED_EXTENSIONS = {
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".ico",
        ".mp4", ".webm", ".avi", ".mov", ".flv",
        ".mp3", ".wav", ".ogg", ".flac",
        ".zip", ".gz", ".bz2", ".7z", ".rar", ".tar",
        ".pdf", ".woff", ".woff2"
    };

    /**
     * Checks if compression should be applied based on client capabilities and content type.
     */
    public boolean shouldCompress(Map<String, String> headers, String mimeType, long contentLength, String fileName) {
        // Check if compression is supported by client
        String acceptEncoding = headers.get("accept-encoding");
        if (acceptEncoding == null || !acceptEncoding.toLowerCase().contains("gzip")) {
            return false;
        }

        // Don't compress small files
        if (contentLength < MIN_COMPRESS_SIZE) {
            return false;
        }

        // Don't compress already compressed files
        if (isPrecompressed(fileName)) {
            return false;
        }

        // Check if content type is compressible
        return isCompressibleType(mimeType);
    }

    /**
     * Checks if the content type is compressible.
     */
    private boolean isCompressibleType(String mimeType) {
        if (mimeType == null) {
            return false;
        }

        String normalizedType = mimeType.toLowerCase();
        for (String compressibleType : COMPRESSIBLE_TYPES) {
            if (normalizedType.startsWith(compressibleType)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if the file is already compressed based on extension.
     */
    private boolean isPrecompressed(String fileName) {
        if (fileName == null) {
            return false;
        }

        String lowerFileName = fileName.toLowerCase();
        for (String ext : PRECOMPRESSED_EXTENSIONS) {
            if (lowerFileName.endsWith(ext)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Compresses file content using Gzip.
     * @return byte array of compressed content, or null if compression fails
     */
    public byte[] compressFile(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzos = new GZIPOutputStream(baos)) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                gzos.write(buffer, 0, bytesRead);
            }

            gzos.finish();
            return baos.toByteArray();

        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to compress file: " + file.getName(), e);
            return null;
        }
    }

    /**
     * Compresses a byte array using Gzip.
     * @return compressed byte array, or null if compression fails
     */
    public byte[] compress(byte[] data) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzos = new GZIPOutputStream(baos)) {

            gzos.write(data);
            gzos.finish();
            return baos.toByteArray();

        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to compress data", e);
            return null;
        }
    }

    /**
     * Creates a GZIPOutputStream wrapper for streaming compression.
     * Caller is responsible for closing the stream.
     */
    public GZIPOutputStream createCompressedStream(OutputStream outputStream) throws IOException {
        return new GZIPOutputStream(outputStream);
    }
}
