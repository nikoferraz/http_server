package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Test suite for MetricsCollector.
 * Tests counter, gauge, and histogram metrics with memory bounds verification.
 */
@DisplayName("MetricsCollector Tests")
class MetricsCollectorTest {

    private MetricsCollector metrics;

    @BeforeEach
    void setUp() {
        // Get singleton instance
        metrics = MetricsCollector.getInstance();
        // Clear any state from previous tests by getting fresh instance
        // Note: In real scenario, may need to reset state
    }

    @Nested
    @DisplayName("Counter Metrics")
    class CounterTests {

        @Test
        @DisplayName("Should increment counter")
        void testIncrementCounter() {
            String counterName = "test_requests_total";

            long initial = metrics.getCounter(counterName);
            metrics.incrementCounter(counterName);
            long after = metrics.getCounter(counterName);

            assertThat(after).isGreaterThan(initial);
        }

        @Test
        @DisplayName("Should increment counter with tags")
        void testIncrementCounterWithTags() {
            String counterName = "http_requests_total";
            String tags = "method=GET,status=200";

            long initial = metrics.getCounter(counterName, tags);
            metrics.incrementCounter(counterName, tags);
            long after = metrics.getCounter(counterName, tags);

            assertThat(after).isGreaterThan(initial);
        }

        @Test
        @DisplayName("Should handle counter with multiple tag values")
        void testCounterMultipleTags() {
            metrics.incrementCounter("test", "method=GET");
            metrics.incrementCounter("test", "method=POST");
            metrics.incrementCounter("test", "method=GET");

            long getCount = metrics.getCounter("test", "method=GET");
            long postCount = metrics.getCounter("test", "method=POST");

            assertThat(getCount).isGreaterThan(postCount);
        }

        @Test
        @DisplayName("Should handle counter with empty tags")
        void testCounterEmptyTags() {
            metrics.incrementCounter("test_counter", "");
            long count = metrics.getCounter("test_counter", "");

            assertThat(count).isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Gauge Metrics")
    class GaugeTests {

        @Test
        @DisplayName("Should set gauge value")
        void testSetGauge() {
            String gaugeName = "test_gauge";
            long value = 42;

            metrics.setGauge(gaugeName, value);
            long retrievedValue = metrics.getGauge(gaugeName);

            assertThat(retrievedValue).isEqualTo(value);
        }

        @Test
        @DisplayName("Should increment gauge")
        void testIncrementGauge() {
            String gaugeName = "test_increment_gauge";

            long initial = metrics.getGauge(gaugeName);
            metrics.incrementGauge(gaugeName);
            long after = metrics.getGauge(gaugeName);

            assertThat(after).isGreaterThan(initial);
        }

        @Test
        @DisplayName("Should decrement gauge")
        void testDecrementGauge() {
            String gaugeName = "test_decrement_gauge";
            metrics.setGauge(gaugeName, 10);

            metrics.decrementGauge(gaugeName);
            long value = metrics.getGauge(gaugeName);

            assertThat(value).isLessThan(10);
        }

        @Test
        @DisplayName("Should handle negative gauge values")
        void testNegativeGaugeValue() {
            String gaugeName = "test_negative_gauge";

            metrics.setGauge(gaugeName, -100);
            long value = metrics.getGauge(gaugeName);

            assertThat(value).isEqualTo(-100);
        }

        @Test
        @DisplayName("Should overwrite previous gauge value")
        void testGaugeOverwrite() {
            String gaugeName = "test_overwrite_gauge";

            metrics.setGauge(gaugeName, 10);
            metrics.setGauge(gaugeName, 20);
            long value = metrics.getGauge(gaugeName);

            assertThat(value).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("Histogram Metrics")
    class HistogramTests {

        @Test
        @DisplayName("Should record histogram observation")
        void testRecordHistogramObservation() {
            String histogramName = "test_latency_ms";

            metrics.recordHistogramObservation(histogramName, 100.0);
            boolean isEmpty = metrics.isHistogramEmpty(histogramName);

            assertThat(isEmpty).isFalse();
        }

        @Test
        @DisplayName("Should handle multiple histogram observations")
        void testMultipleObservations() {
            String histogramName = "test_latency";

            metrics.recordHistogramObservation(histogramName, 10.0);
            metrics.recordHistogramObservation(histogramName, 20.0);
            metrics.recordHistogramObservation(histogramName, 30.0);

            int observationCount = metrics.getHistogramObservationCount(histogramName);
            assertThat(observationCount).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("Should enforce bounded histogram (max 1000 observations)")
        void testHistogramBoundedSize() {
            String histogramName = "test_bounded_histogram";

            // Add more than 1000 observations
            for (int i = 0; i < 1500; i++) {
                metrics.recordHistogramObservation(histogramName, Math.random() * 100);
            }

            int observationCount = metrics.getHistogramObservationCount(histogramName);

            // Should not exceed 1000
            assertThat(observationCount).isLessThanOrEqualTo(1000);
        }

        @Test
        @DisplayName("Should handle zero value observations")
        void testZeroValueObservation() {
            String histogramName = "test_zero_histogram";

            metrics.recordHistogramObservation(histogramName, 0.0);
            int count = metrics.getHistogramObservationCount(histogramName);

            assertThat(count).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should handle large value observations")
        void testLargeValueObservation() {
            String histogramName = "test_large_histogram";

            metrics.recordHistogramObservation(histogramName, Double.MAX_VALUE);
            int count = metrics.getHistogramObservationCount(histogramName);

            assertThat(count).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should identify empty histograms")
        void testEmptyHistogramDetection() {
            String emptyHistogram = "nonexistent_histogram";

            boolean isEmpty = metrics.isHistogramEmpty(emptyHistogram);

            assertThat(isEmpty).isTrue();
        }
    }

    @Nested
    @DisplayName("Request Recording")
    class RequestRecordingTests {

        @Test
        @DisplayName("Should record request metrics")
        void testRecordRequest() {
            String method = "GET";
            int statusCode = 200;
            long duration = 50;
            long responseSize = 1024;

            metrics.recordRequest(method, statusCode, duration, responseSize);

            // Verify counters were incremented
            long requestCount = metrics.getCounter("http_requests_total", "method=" + method + ",status=" + statusCode);
            assertThat(requestCount).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should track request duration histogram")
        void testRequestDurationHistogram() {
            metrics.recordRequest("GET", 200, 100, 1024);

            int count = metrics.getHistogramObservationCount("http_request_duration_seconds");
            assertThat(count).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should track response size histogram")
        void testResponseSizeHistogram() {
            metrics.recordRequest("POST", 201, 50, 2048);

            int count = metrics.getHistogramObservationCount("http_response_size_bytes");
            assertThat(count).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should handle various HTTP methods")
        void testMultipleHttpMethods() {
            String[] methods = {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"};

            for (String method : methods) {
                metrics.recordRequest(method, 200, 50, 1024);
            }

            // All should be recorded
            for (String method : methods) {
                long count = metrics.getCounter("http_requests_total", "method=" + method + ",status=200");
                assertThat(count).isGreaterThan(0);
            }
        }

        @Test
        @DisplayName("Should track various HTTP status codes")
        void testStatusCodeTracking() {
            int[] statusCodes = {200, 201, 204, 301, 304, 400, 401, 403, 404, 500, 503};

            for (int code : statusCodes) {
                metrics.recordRequest("GET", code, 50, 1024);
            }

            // All should be recorded
            for (int code : statusCodes) {
                long count = metrics.getCounter("http_requests_total", "method=GET,status=" + code);
                assertThat(count).isGreaterThan(0);
            }
        }
    }

    @Nested
    @DisplayName("Metrics Export")
    class MetricsExportTests {

        @Test
        @DisplayName("Should export metrics in Prometheus format")
        void testPrometheusExport() {
            metrics.recordRequest("GET", 200, 50, 1024);

            String export = metrics.exportPrometheus();

            assertThat(export).isNotNull();
            assertThat(export).isNotEmpty();
            assertThat(export).contains("# HELP");
            assertThat(export).contains("# TYPE");
        }

        @Test
        @DisplayName("Should include counter metrics in export")
        void testCounterInExport() {
            metrics.incrementCounter("test_counter");

            String export = metrics.exportPrometheus();

            assertThat(export).contains("test_counter");
        }

        @Test
        @DisplayName("Should include gauge metrics in export")
        void testGaugeInExport() {
            metrics.setGauge("test_gauge", 100);

            String export = metrics.exportPrometheus();

            assertThat(export).contains("test_gauge");
        }

        @Test
        @DisplayName("Should include histogram metrics in export")
        void testHistogramInExport() {
            metrics.recordHistogramObservation("test_histogram", 100.0);

            String export = metrics.exportPrometheus();

            assertThat(export).contains("test_histogram");
        }

        @Test
        @DisplayName("Should format numbers correctly in Prometheus")
        void testPrometheusNumberFormat() {
            metrics.setGauge("test_number_gauge", 12345);

            String export = metrics.exportPrometheus();

            // Should contain the number in some form
            assertThat(export).contains("12345");
        }
    }

    @Nested
    @DisplayName("Concurrency")
    class ConcurrencyTests {

        @Test
        @DisplayName("Should handle concurrent counter increments")
        void testConcurrentCounterIncrements() throws InterruptedException {
            String counterName = "concurrent_test";
            int threadCount = 10;
            int incrementsPerThread = 100;

            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                Thread t = new Thread(() -> {
                    for (int j = 0; j < incrementsPerThread; j++) {
                        metrics.incrementCounter(counterName);
                    }
                });
                threads.add(t);
                t.start();
            }

            for (Thread t : threads) {
                t.join();
            }

            long count = metrics.getCounter(counterName);
            // Should have incremented by all threads
            assertThat(count).isGreaterThanOrEqualTo(threadCount * incrementsPerThread);
        }

        @Test
        @DisplayName("Should handle concurrent histogram recording")
        void testConcurrentHistogramRecording() throws InterruptedException {
            String histogramName = "concurrent_histogram";
            int threadCount = 10;
            int recordsPerThread = 100;

            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                Thread t = new Thread(() -> {
                    for (int j = 0; j < recordsPerThread; j++) {
                        metrics.recordHistogramObservation(histogramName, Math.random() * 100);
                    }
                });
                threads.add(t);
                t.start();
            }

            for (Thread t : threads) {
                t.join();
            }

            int count = metrics.getHistogramObservationCount(histogramName);
            assertThat(count).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should handle concurrent gauge updates")
        void testConcurrentGaugeUpdates() throws InterruptedException {
            String gaugeName = "concurrent_gauge";
            int threadCount = 10;

            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                int value = i;
                Thread t = new Thread(() -> {
                    metrics.setGauge(gaugeName, value);
                });
                threads.add(t);
                t.start();
            }

            for (Thread t : threads) {
                t.join();
            }

            long finalValue = metrics.getGauge(gaugeName);
            assertThat(finalValue).isBetween(0L, 9L);
        }
    }

    @Nested
    @DisplayName("Memory Bounds")
    class MemoryBoundsTests {

        @Test
        @DisplayName("Should not exceed memory bounds with histogram")
        void testHistogramMemoryBounds() {
            String histogramName = "memory_test_histogram";

            // Record many observations
            for (int i = 0; i < 5000; i++) {
                metrics.recordHistogramObservation(histogramName, Math.random() * 1000);
            }

            // Should still not exceed max observations
            int count = metrics.getHistogramObservationCount(histogramName);
            assertThat(count).isLessThanOrEqualTo(1000);
        }

        @Test
        @DisplayName("Should handle many counter names")
        void testManyCounters() {
            // Create many distinct counters
            for (int i = 0; i < 1000; i++) {
                metrics.incrementCounter("counter_" + i);
            }

            // Should not crash or consume excessive memory
            long count = metrics.getCounter("counter_500");
            assertThat(count).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should handle many gauge names")
        void testManyGauges() {
            // Set many distinct gauges
            for (int i = 0; i < 1000; i++) {
                metrics.setGauge("gauge_" + i, i);
            }

            // Should not crash
            long value = metrics.getGauge("gauge_500");
            assertThat(value).isEqualTo(500);
        }
    }

    @Nested
    @DisplayName("Singleton Behavior")
    class SingletonTests {

        @Test
        @DisplayName("Should return same instance on multiple calls")
        void testSingletonInstance() {
            MetricsCollector instance1 = MetricsCollector.getInstance();
            MetricsCollector instance2 = MetricsCollector.getInstance();

            assertThat(instance1).isSameAs(instance2);
        }
    }
}
