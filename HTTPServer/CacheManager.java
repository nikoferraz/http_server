package HTTPServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Manages HTTP caching with ETag and Last-Modified support.
 * Implements conditional requests (If-None-Match, If-Modified-Since).
 */
public class CacheManager {

    private static final Logger logger = Logger.getLogger(CacheManager.class.getName());

    // RFC 7231 HTTP date format
    private static final DateTimeFormatter HTTP_DATE_FORMAT =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'").withZone(ZoneId.of("GMT"));

    // Default cache control for static assets (1 hour)
    private static final String DEFAULT_CACHE_CONTROL = "public, max-age=3600, must-revalidate";

    /**
     * Generates a strong ETag based on file content hash (MD5) using streaming to avoid loading entire file into memory.
     * For very large files, uses weak ETag based on size + modification time.
     */
    public String generateETag(File file) {
        try {
            // For files larger than 100MB, use weak ETag to avoid hash computation overhead
            if (file.length() > 104_857_600) {
                return generateWeakETag(file);
            }

            // Generate strong ETag using MD5 hash with streaming
            MessageDigest md = MessageDigest.getInstance("MD5");

            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    md.update(buffer, 0, bytesRead);
                }
            }

            byte[] hash = md.digest();

            StringBuilder sb = new StringBuilder("\"");
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            sb.append("\"");

            return sb.toString();

        } catch (IOException | NoSuchAlgorithmException e) {
            logger.log(Level.WARNING, "Failed to generate strong ETag, falling back to weak ETag", e);
            return generateWeakETag(file);
        }
    }

    /**
     * Generates a weak ETag based on file size and modification time.
     */
    private String generateWeakETag(File file) {
        long size = file.length();
        long lastModified = file.lastModified();
        return String.format("W/\"%x-%x\"", size, lastModified);
    }

    /**
     * Gets the Last-Modified date of the file in HTTP date format.
     */
    public String getLastModified(File file) {
        try {
            FileTime fileTime = Files.getLastModifiedTime(file.toPath());
            ZonedDateTime zdt = ZonedDateTime.ofInstant(fileTime.toInstant(), ZoneId.of("GMT"));
            return HTTP_DATE_FORMAT.format(zdt);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to get last modified time", e);
            return null;
        }
    }

    /**
     * Checks if the resource has been modified based on If-None-Match (ETag) header.
     * @return true if resource was modified (should send full response), false if not modified (send 304)
     */
    public boolean isModifiedByETag(String ifNoneMatch, String currentETag) {
        if (ifNoneMatch == null || currentETag == null) {
            return true;
        }

        // Handle multiple ETags in If-None-Match
        String[] clientETags = ifNoneMatch.split(",");
        for (String clientETag : clientETags) {
            clientETag = clientETag.trim();
            if (clientETag.equals("*") || clientETag.equals(currentETag)) {
                return false; // Not modified
            }

            // Handle weak ETag comparison
            if (currentETag.startsWith("W/") && clientETag.startsWith("W/")) {
                if (clientETag.equals(currentETag)) {
                    return false;
                }
            }
        }

        return true; // Modified
    }

    /**
     * Checks if the resource has been modified based on If-Modified-Since header.
     * @return true if resource was modified (should send full response), false if not modified (send 304)
     */
    public boolean isModifiedByDate(String ifModifiedSince, File file) {
        if (ifModifiedSince == null) {
            return true;
        }

        try {
            ZonedDateTime clientDate = ZonedDateTime.parse(ifModifiedSince, HTTP_DATE_FORMAT);
            FileTime fileTime = Files.getLastModifiedTime(file.toPath());
            ZonedDateTime fileDate = ZonedDateTime.ofInstant(fileTime.toInstant(), ZoneId.of("GMT"));

            // Truncate to seconds for comparison (HTTP dates don't have milliseconds)
            fileDate = fileDate.withNano(0);
            clientDate = clientDate.withNano(0);

            // If file date is after client date, it's been modified
            return fileDate.isAfter(clientDate);

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to parse If-Modified-Since header: " + ifModifiedSince, e);
            return true; // Assume modified on error
        }
    }

    /**
     * Determines if the request is a conditional request (has If-None-Match or If-Modified-Since).
     */
    public boolean isConditionalRequest(Map<String, String> headers) {
        return headers.containsKey("if-none-match") || headers.containsKey("if-modified-since");
    }

    /**
     * Checks if the resource should be served (true) or 304 returned (false).
     */
    public boolean shouldServeResource(Map<String, String> headers, File file, String etag) {
        String ifNoneMatch = headers.get("if-none-match");
        String ifModifiedSince = headers.get("if-modified-since");

        // If If-None-Match is present, use ETag validation (takes precedence)
        if (ifNoneMatch != null) {
            return isModifiedByETag(ifNoneMatch, etag);
        }

        // Fall back to If-Modified-Since validation
        if (ifModifiedSince != null) {
            return isModifiedByDate(ifModifiedSince, file);
        }

        // Not a conditional request, serve the resource
        return true;
    }

    /**
     * Gets the Cache-Control header value for the file.
     */
    public String getCacheControl(String fileName) {
        // For now, use default cache control
        // Future: could customize based on file type or path patterns
        return DEFAULT_CACHE_CONTROL;
    }

    /**
     * Formats the current time as an HTTP date string.
     */
    public String getHttpDate() {
        return HTTP_DATE_FORMAT.format(ZonedDateTime.now(ZoneId.of("GMT")));
    }
}
