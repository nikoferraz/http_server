package HTTPServer;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class HealthCheckHandler {

    private static final long MIN_DISK_SPACE_MB = 100;
    private final File webroot;
    private final CacheManager cacheManager;
    private final CompressionHandler compressionHandler;
    private final MetricsCollector metrics;

    public HealthCheckHandler(File webroot) {
        this.webroot = webroot;
        this.cacheManager = null;
        this.compressionHandler = null;
        this.metrics = null;
    }

    public HealthCheckHandler(File webroot, CacheManager cacheManager,
                            CompressionHandler compressionHandler, MetricsCollector metrics) {
        this.webroot = webroot;
        this.cacheManager = cacheManager;
        this.compressionHandler = compressionHandler;
        this.metrics = metrics;
    }

    public void handleHealthCheck(Writer writer, String path, String version) throws IOException {
        switch (path) {
            case "/health/live":
                handleLivenessProbe(writer, version);
                break;
            case "/health/ready":
                handleReadinessProbe(writer, version);
                break;
            case "/health/startup":
                handleStartupProbe(writer, version);
                break;
            case "/health/metrics":
                handleMetrics(writer, version);
                break;
            default:
                sendNotFound(writer, version);
                break;
        }
    }

    private void handleLivenessProbe(Writer writer, String version) throws IOException {
        // Liveness probe - always returns UP if server is running
        String timestamp = getCurrentTimestamp();
        String response = String.format("{\"status\":\"UP\",\"timestamp\":\"%s\"}", timestamp);

        String httpVersion = version.startsWith("HTTP/1.1") ? "HTTP/1.1" : "HTTP/1.0";
        writer.write(httpVersion + " 200 OK\r\n");
        writer.write("Content-Type: application/json\r\n");
        writer.write("Content-Length: " + response.length() + "\r\n");
        writer.write("Cache-Control: no-cache\r\n");
        writer.write("Connection: close\r\n");
        writer.write("\r\n");
        writer.write(response);
        writer.flush();
    }

    private void handleReadinessProbe(Writer writer, String version) throws IOException {
        // Readiness probe - checks if server is ready to serve traffic
        String timestamp = getCurrentTimestamp();
        boolean isReady = true;
        StringBuilder checks = new StringBuilder();

        // Check thread pool availability
        boolean threadPoolOk = checkThreadPool();
        checks.append("\"threadPool\":\"").append(threadPoolOk ? "UP" : "DOWN").append("\"");

        if (!threadPoolOk) {
            isReady = false;
        }

        // Check disk space
        boolean diskSpaceOk = checkDiskSpace();
        checks.append(",\"diskSpace\":\"").append(diskSpaceOk ? "UP" : "DOWN").append("\"");

        if (!diskSpaceOk) {
            isReady = false;
        }

        String status = isReady ? "UP" : "DOWN";
        int statusCode = isReady ? 200 : 503;
        String statusText = isReady ? "OK" : "Service Unavailable";

        String response = String.format(
            "{\"status\":\"%s\",\"checks\":{%s},\"timestamp\":\"%s\"}",
            status, checks.toString(), timestamp
        );

        String httpVersion = version.startsWith("HTTP/1.1") ? "HTTP/1.1" : "HTTP/1.0";
        writer.write(httpVersion + " " + statusCode + " " + statusText + "\r\n");
        writer.write("Content-Type: application/json\r\n");
        writer.write("Content-Length: " + response.length() + "\r\n");
        writer.write("Cache-Control: no-cache\r\n");
        writer.write("Connection: close\r\n");
        writer.write("\r\n");
        writer.write(response);
        writer.flush();
    }

    private void handleStartupProbe(Writer writer, String version) throws IOException {
        // Startup probe - returns UP when fully initialized
        // For now, if server is accepting connections, it's started
        String timestamp = getCurrentTimestamp();
        String response = String.format("{\"status\":\"UP\",\"timestamp\":\"%s\"}", timestamp);

        String httpVersion = version.startsWith("HTTP/1.1") ? "HTTP/1.1" : "HTTP/1.0";
        writer.write(httpVersion + " 200 OK\r\n");
        writer.write("Content-Type: application/json\r\n");
        writer.write("Content-Length: " + response.length() + "\r\n");
        writer.write("Cache-Control: no-cache\r\n");
        writer.write("Connection: close\r\n");
        writer.write("\r\n");
        writer.write(response);
        writer.flush();
    }

    private boolean checkThreadPool() {
        // Simple check - if we're processing this request, thread pool has capacity
        // In a real implementation, would check ExecutorService metrics
        return true;
    }

    private boolean checkDiskSpace() {
        if (webroot == null || !webroot.exists()) {
            return false;
        }

        long freeSpaceBytes = webroot.getUsableSpace();
        long freeSpaceMB = freeSpaceBytes / (1024 * 1024);

        return freeSpaceMB >= MIN_DISK_SPACE_MB;
    }

    private String getCurrentTimestamp() {
        return ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT);
    }

    private void handleMetrics(Writer writer, String version) throws IOException {
        // Record cache metrics before exporting
        if (cacheManager != null && metrics != null) {
            cacheManager.recordMetrics(metrics);
        }
        if (compressionHandler != null && metrics != null) {
            compressionHandler.recordMetrics(metrics);
        }

        // Export Prometheus metrics
        String metricsOutput = metrics != null ? metrics.exportPrometheusMetrics() : "";

        String httpVersion = version.startsWith("HTTP/1.1") ? "HTTP/1.1" : "HTTP/1.0";
        writer.write(httpVersion + " 200 OK\r\n");
        writer.write("Content-Type: text/plain; version=0.0.4\r\n");
        writer.write("Content-Length: " + metricsOutput.length() + "\r\n");
        writer.write("Cache-Control: no-cache\r\n");
        writer.write("Connection: close\r\n");
        writer.write("\r\n");
        writer.write(metricsOutput);
        writer.flush();
    }

    private void sendNotFound(Writer writer, String version) throws IOException {
        String response = "{\"error\":\"Not Found\",\"message\":\"Health check endpoint not found\"}";

        String httpVersion = version.startsWith("HTTP/1.1") ? "HTTP/1.1" : "HTTP/1.0";
        writer.write(httpVersion + " 404 Not Found\r\n");
        writer.write("Content-Type: application/json\r\n");
        writer.write("Content-Length: " + response.length() + "\r\n");
        writer.write("Connection: close\r\n");
        writer.write("\r\n");
        writer.write(response);
        writer.flush();
    }
}
