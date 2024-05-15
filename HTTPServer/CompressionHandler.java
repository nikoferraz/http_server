package HTTPServer;

import java.io.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Handles response compression using Gzip with intelligent caching.
 * Compresses text-based content types and caches compressed content for repeated accesses.
 * Cache respects file modification times and enforces size/entry limits.
 */
public class CompressionHandler {

    private static final Logger logger = Logger.getLogger(CompressionHandler.class.getName());

    // Minimum size in bytes to compress (no benefit for very small files)
    private static final int MIN_COMPRESS_SIZE = 256;

    // Compression cache limits
    private static final int MAX_CACHE_SIZE = 1000;
    private static final long MAX_CACHEABLE_SIZE = 1_048_576; // 1MB

    // Metrics for cache performance
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);

    // Cache for compressed content
    private final ConcurrentHashMap<String, CachedCompression> compressionCache = new ConcurrentHashMap<>();

    /**
     * Internal class to store cached compression results.
     */
    private static class CachedCompression {
        final byte[] compressedData;
        final long lastModified;
        final long cacheTime;
        final int originalSize;
        final int compressedSize;

        CachedCompression(byte[] data, long lastModified, int originalSize) {
            this.compressedData = data;
            this.lastModified = lastModified;
            this.cacheTime = System.nanoTime();
            this.originalSize = originalSize;
            this.compressedSize = data.length;
        }

        /**
         * Checks if cached entry is still valid based on file modification time.
         */
        boolean isValid(File file) {
            return file.lastModified() == lastModified;
        }

        /**
         * Returns compression ratio (compressed / original).
         */
        double getCompressionRatio() {
            return (double) compressedSize / originalSize;
        }
    }

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
     * Compresses file content using Gzip with caching support.
     * Returns cached result for repeated accesses to the same file.
     * Cache is invalidated when file is modified.
     * Files larger than 1MB are compressed but not cached.
     *
     * @return byte array of compressed content, or null if compression fails
     */
    public byte[] compressFile(File file) {
        if (file == null) {
            return null;
        }

        // Don't cache very large files (over 1MB)
        if (file.length() > MAX_CACHEABLE_SIZE) {
            cacheMisses.incrementAndGet();
            return compressFileUncached(file);
        }

        String cacheKey = file.getAbsolutePath();
        CachedCompression cached = compressionCache.get(cacheKey);

        // Return cached result if valid
        if (cached != null && cached.isValid(file)) {
            cacheHits.incrementAndGet();
            return cached.compressedData;
        }

        // Cache miss - compress file
        cacheMisses.incrementAndGet();
        byte[] compressed = compressFileUncached(file);

        if (compressed != null) {
            // Add to cache
            compressionCache.put(cacheKey, new CachedCompression(compressed, file.lastModified(), (int) file.length()));

            // Evict oldest entry if cache is full
            if (compressionCache.size() > MAX_CACHE_SIZE) {
                evictLRU();
            }
        }

        return compressed;
    }

    /**
     * Compresses file without caching.
     * Used internally and for files that shouldn't be cached.
     */
    private byte[] compressFileUncached(File file) {
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
     * Evicts the least recently used entry from the cache.
     */
    private void evictLRU() {
        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;

        // Find entry with oldest cache time
        for (Map.Entry<String, CachedCompression> entry : compressionCache.entrySet()) {
            if (entry.getValue().cacheTime < oldestTime) {
                oldestTime = entry.getValue().cacheTime;
                oldestKey = entry.getKey();
            }
        }

        if (oldestKey != null) {
            compressionCache.remove(oldestKey);
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

    // ============= Cache Management Methods =============

    /**
     * Returns the number of cache hits (successful cache lookups).
     */
    public long getCacheHits() {
        return cacheHits.get();
    }

    /**
     * Returns the number of cache misses (compression performed).
     */
    public long getCacheMisses() {
        return cacheMisses.get();
    }

    /**
     * Returns the current cache hit rate (hits / total requests).
     * Returns 0.0 if no requests have been made.
     */
    public double getCacheHitRate() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }

    /**
     * Returns the number of entries currently in the cache.
     */
    public long getCacheSize() {
        return compressionCache.size();
    }

    /**
     * Clears all cached compression results and resets metrics.
     * Useful for testing and cache invalidation.
     */
    public void clearCache() {
        compressionCache.clear();
        cacheHits.set(0);
        cacheMisses.set(0);
    }

    /**
     * Records cache metrics to the MetricsCollector for Prometheus export.
     */
    public void recordMetrics(MetricsCollector metrics) {
        metrics.recordCacheMetrics("compression",
            cacheHits.get(),
            cacheMisses.get(),
            compressionCache.size());
    }
}
