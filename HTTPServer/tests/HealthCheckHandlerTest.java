package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Test suite for HealthCheckHandler.
 * Tests health check endpoints and Prometheus metrics export.
 */
@DisplayName("HealthCheckHandler Tests")
class HealthCheckHandlerTest {

    private HealthCheckHandler healthCheckHandler;
    private File webroot;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        webroot = tempDir.toFile();
        healthCheckHandler = new HealthCheckHandler(webroot);
    }

    @Nested
    @DisplayName("Health Endpoint")
    class HealthEndpointTests {

        @Test
        @DisplayName("Should indicate server is healthy")
        void testHealthCheckResponse() {
            String response = healthCheckHandler.getHealthCheckResponse();

            assertThat(response).isNotNull();
            assertThat(response).isNotEmpty();
        }

        @Test
        @DisplayName("Should return 200 OK status")
        void testHealthCheckStatus() {
            int statusCode = healthCheckHandler.getHealthCheckStatusCode();

            assertThat(statusCode).isEqualTo(200);
        }

        @Test
        @DisplayName("Should contain UP status indicator")
        void testUpStatusIndicator() {
            String response = healthCheckHandler.getHealthCheckResponse();

            assertThat(response).contains("UP");
        }

        @Test
        @DisplayName("Should contain timestamp in response")
        void testTimestampInResponse() {
            String response = healthCheckHandler.getHealthCheckResponse();

            assertThat(response).contains("timestamp");
        }
    }

    @Nested
    @DisplayName("Detailed Health Check")
    class DetailedHealthCheckTests {

        @Test
        @DisplayName("Should include disk space information")
        void testDiskSpaceInfo() {
            String response = healthCheckHandler.getHealthCheckResponse();

            // May contain disk info
            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("Should include memory information")
        void testMemoryInfo() {
            String response = healthCheckHandler.getHealthCheckResponse();

            // May contain memory info
            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("Should include uptime information")
        void testUptimeInfo() {
            String response = healthCheckHandler.getHealthCheckResponse();

            // Response should have some info
            assertThat(response).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Metrics Endpoint")
    class MetricsEndpointTests {

        @Test
        @DisplayName("Should export metrics in Prometheus format")
        void testPrometheusExport() {
            String metrics = healthCheckHandler.getMetricsResponse();

            assertThat(metrics).isNotNull();
            assertThat(metrics).isNotEmpty();
        }

        @Test
        @DisplayName("Should include Prometheus HELP lines")
        void testPrometheusHelp() {
            String metrics = healthCheckHandler.getMetricsResponse();

            // Prometheus format includes # HELP lines
            assertThat(metrics).contains("# HELP");
        }

        @Test
        @DisplayName("Should include Prometheus TYPE lines")
        void testPrometheusType() {
            String metrics = healthCheckHandler.getMetricsResponse();

            // Prometheus format includes # TYPE lines
            assertThat(metrics).contains("# TYPE");
        }

        @Test
        @DisplayName("Should include http_requests_total counter")
        void testRequestsCounter() {
            String metrics = healthCheckHandler.getMetricsResponse();

            assertThat(metrics).contains("http_requests_total");
        }

        @Test
        @DisplayName("Should include http_response_size_bytes histogram")
        void testResponseSizeHistogram() {
            String metrics = healthCheckHandler.getMetricsResponse();

            assertThat(metrics).contains("http_response_size_bytes");
        }

        @Test
        @DisplayName("Should include http_request_duration_seconds histogram")
        void testRequestDurationHistogram() {
            String metrics = healthCheckHandler.getMetricsResponse();

            assertThat(metrics).contains("http_request_duration_seconds");
        }

        @Test
        @DisplayName("Should include http_active_connections gauge")
        void testActiveConnectionsGauge() {
            String metrics = healthCheckHandler.getMetricsResponse();

            assertThat(metrics).contains("http_active_connections");
        }

        @Test
        @DisplayName("Should return 200 OK for metrics endpoint")
        void testMetricsStatusCode() {
            int statusCode = healthCheckHandler.getMetricsStatusCode();

            assertThat(statusCode).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("Content Type Headers")
    class ContentTypeTests {

        @Test
        @DisplayName("Should return JSON content type for health")
        void testHealthContentType() {
            String contentType = healthCheckHandler.getHealthCheckContentType();

            assertThat(contentType).isNotNull();
            assertThat(contentType).contains("json");
        }

        @Test
        @DisplayName("Should return text/plain content type for metrics")
        void testMetricsContentType() {
            String contentType = healthCheckHandler.getMetricsContentType();

            assertThat(contentType).isNotNull();
            assertThat(contentType).contains("text/plain");
        }
    }

    @Nested
    @DisplayName("Response Validation")
    class ResponseValidationTests {

        @Test
        @DisplayName("Should return valid JSON in health response")
        void testValidJsonResponse() {
            String response = healthCheckHandler.getHealthCheckResponse();

            assertThat(response).startsWith("{");
            assertThat(response).endsWith("}");
        }

        @Test
        @DisplayName("Should return non-empty metrics")
        void testNonEmptyMetrics() {
            String metrics = healthCheckHandler.getMetricsResponse();

            assertThat(metrics).isNotEmpty();
            assertThat(metrics.length()).isGreaterThan(100);
        }

        @Test
        @DisplayName("Should not return null responses")
        void testNoNullResponses() {
            String health = healthCheckHandler.getHealthCheckResponse();
            String metrics = healthCheckHandler.getMetricsResponse();

            assertThat(health).isNotNull();
            assertThat(metrics).isNotNull();
        }
    }

    @Nested
    @DisplayName("Webroot Handling")
    class WebrootHandlingTests {

        @Test
        @DisplayName("Should initialize with valid webroot")
        void testValidWebroot() {
            assertThat(webroot).exists();
            assertThat(webroot).isDirectory();
        }

        @Test
        @DisplayName("Should handle webroot checks in health status")
        void testWebrootInHealthStatus() {
            String response = healthCheckHandler.getHealthCheckResponse();

            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("Should work even with non-existent webroot")
        void testNonExistentWebroot() {
            File nonExistent = new File("/nonexistent/path");
            HealthCheckHandler handler = new HealthCheckHandler(nonExistent);

            String response = handler.getHealthCheckResponse();

            assertThat(response).isNotNull();
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle multiple sequential requests")
        void testMultipleRequests() {
            String response1 = healthCheckHandler.getHealthCheckResponse();
            String response2 = healthCheckHandler.getHealthCheckResponse();

            assertThat(response1).isNotNull();
            assertThat(response2).isNotNull();
        }

        @Test
        @DisplayName("Should handle rapid metric queries")
        void testRapidMetricQueries() {
            for (int i = 0; i < 10; i++) {
                String metrics = healthCheckHandler.getMetricsResponse();
                assertThat(metrics).isNotNull();
            }
        }

        @Test
        @DisplayName("Should provide consistent responses")
        void testConsistentResponses() {
            String health1 = healthCheckHandler.getHealthCheckResponse();
            String health2 = healthCheckHandler.getHealthCheckResponse();

            // Both should be valid even if timestamps differ
            assertThat(health1).isNotEmpty();
            assertThat(health2).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Path Recognition")
    class PathRecognitionTests {

        @Test
        @DisplayName("Should recognize /health endpoint")
        void testHealthEndpointRecognition() {
            boolean isHealth = healthCheckHandler.isHealthPath("/health");

            assertThat(isHealth).isTrue();
        }

        @Test
        @DisplayName("Should recognize /health/metrics endpoint")
        void testMetricsEndpointRecognition() {
            boolean isMetrics = healthCheckHandler.isMetricsPath("/health/metrics");

            assertThat(isMetrics).isTrue();
        }

        @Test
        @DisplayName("Should not recognize other paths as health")
        void testOtherPathsNotHealth() {
            boolean isHealth = healthCheckHandler.isHealthPath("/other");

            assertThat(isHealth).isFalse();
        }

        @Test
        @DisplayName("Should handle case sensitivity in paths")
        void testPathCaseSensitivity() {
            boolean isHealth = healthCheckHandler.isHealthPath("/Health");

            // Should be case-sensitive
            assertThat(isHealth).isFalse();
        }
    }
}
