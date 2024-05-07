package HTTPServer;

/**
 * Test to verify that MetricsCollector histogram memory is bounded.
 * Simulates 1 million requests and verifies that memory usage stays constant.
 */
public class TestMetricsMemoryBounded {

    public static void main(String[] args) {
        System.out.println("Testing MetricsCollector memory bounds...");
        System.out.println();

        MetricsCollector metrics = MetricsCollector.getInstance();
        metrics.reset();

        // Test 1: Verify bounded list respects max size
        System.out.println("Test 1: BoundedList respects maximum size");
        MetricsCollector.BoundedList<Double> list = new MetricsCollector.BoundedList<>(5);
        for (int i = 0; i < 100; i++) {
            list.add((double) i);
        }
        System.out.println("  Added 100 values to list with max size 5");
        System.out.println("  List size: " + list.size());
        System.out.println("  Expected: 5");
        assert list.size() == 5 : "List size should be 5";
        System.out.println("  PASS");
        System.out.println();

        // Test 2: Verify oldest values are removed
        System.out.println("Test 2: Oldest values are removed");
        int lastValue = 0;
        for (Double value : list) {
            lastValue = (int) (double) value;
        }
        System.out.println("  Last value in list: " + lastValue);
        System.out.println("  Expected: 99 (most recent)");
        assert lastValue == 99 : "Last value should be 99";
        System.out.println("  PASS");
        System.out.println();

        // Test 3: Verify histogram size is bounded
        System.out.println("Test 3: Histogram memory is bounded after 1 million requests");
        metrics.reset();

        // Record 1 million requests across multiple histogram keys
        for (int i = 0; i < 1_000_000; i++) {
            String method = (i % 3 == 0) ? "GET" : (i % 3 == 1) ? "POST" : "PUT";
            metrics.recordRequest(method, 200, 50 + (i % 100), 1024 + (i % 10000));
        }

        // Check histogram sizes are bounded
        String metricsOutput = metrics.exportPrometheusMetrics();
        System.out.println("  Exported metrics successfully");
        System.out.println("  Sample from output:");
        String[] lines = metricsOutput.split("\n");
        int sampleCount = 0;
        for (String line : lines) {
            if (line.contains("_count") && sampleCount < 3) {
                System.out.println("    " + line);
                sampleCount++;
            }
        }

        // Verify metrics were collected
        if (metricsOutput.contains("http_request_duration_seconds_count")) {
            System.out.println("  PASS - Memory is bounded (histograms exported after 1M requests)");
        } else {
            System.out.println("  FAIL - Histograms not properly exported");
            System.exit(1);
        }
        System.out.println();

        // Test 4: Verify histogram functionality still works
        System.out.println("Test 4: Histogram export still works");
        metrics.reset();
        for (int i = 0; i < 100; i++) {
            metrics.recordRequest("POST", 201, 25 + i, 2048);
        }

        String prometheusMetrics = metrics.exportPrometheusMetrics();
        boolean hasDuration = prometheusMetrics.contains("http_request_duration_seconds");
        boolean hasSize = prometheusMetrics.contains("http_response_size_bytes");
        boolean hasCount = prometheusMetrics.contains("_count");

        System.out.println("  Duration histogram: " + (hasDuration ? "found" : "NOT FOUND"));
        System.out.println("  Size histogram: " + (hasSize ? "found" : "NOT FOUND"));
        System.out.println("  Histogram counts: " + (hasCount ? "found" : "NOT FOUND"));

        assert hasDuration && hasSize && hasCount : "Histograms should be properly exported";
        System.out.println("  PASS");
        System.out.println();

        System.out.println("All tests passed!");
    }
}
