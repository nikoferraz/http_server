package HTTPServer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

public class MetricsCollector {

    private static MetricsCollector instance;

    // Counter metrics
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    // Gauge metrics
    private final ConcurrentHashMap<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    // Histogram buckets for request duration (in seconds)
    private static final double[] DURATION_BUCKETS = {0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0};

    // Histogram buckets for response size (in bytes)
    private static final long[] SIZE_BUCKETS = {100, 1000, 10000, 100000, 1000000, 10000000};

    // Store histogram observations
    private final ConcurrentHashMap<String, List<Double>> histograms = new ConcurrentHashMap<>();

    private MetricsCollector() {
        initializeMetrics();
    }

    public static synchronized MetricsCollector getInstance() {
        if (instance == null) {
            instance = new MetricsCollector();
        }
        return instance;
    }

    private void initializeMetrics() {
        // Initialize common counters
        gauges.put("http_active_connections", new AtomicLong(0));
        gauges.put("thread_pool_active_threads", new AtomicLong(0));
        gauges.put("thread_pool_queue_size", new AtomicLong(0));
        gauges.put("compression_ratio", new AtomicLong(100)); // 100 = 1.0 ratio
    }

    public void incrementCounter(String name, String... labels) {
        String key = buildKey(name, labels);
        counters.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }

    public void setGauge(String name, long value, String... labels) {
        String key = buildKey(name, labels);
        gauges.computeIfAbsent(key, k -> new AtomicLong(0)).set(value);
    }

    public void incrementGauge(String name, String... labels) {
        String key = buildKey(name, labels);
        gauges.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }

    public void decrementGauge(String name, String... labels) {
        String key = buildKey(name, labels);
        gauges.computeIfAbsent(key, k -> new AtomicLong(0)).decrementAndGet();
    }

    public void observeHistogram(String name, double value, String... labels) {
        String key = buildKey(name, labels);
        histograms.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }

    private String buildKey(String name, String... labels) {
        if (labels.length == 0) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name);
        for (String label : labels) {
            sb.append(":").append(label);
        }
        return sb.toString();
    }

    public void recordRequest(String method, int statusCode, long durationMs, long responseSize) {
        // Increment request counter
        incrementCounter("http_requests_total", "method=" + method, "status=" + statusCode);

        // Record duration histogram (convert ms to seconds)
        observeHistogram("http_request_duration_seconds", durationMs / 1000.0, "method=" + method);

        // Record response size histogram
        observeHistogram("http_response_size_bytes", responseSize, "method=" + method);
    }

    public void recordCacheHit() {
        incrementCounter("cache_hits_total");
    }

    public void recordCacheMiss() {
        incrementCounter("cache_misses_total");
    }

    public void setCompressionRatio(double ratio) {
        // Store as integer percentage (e.g., 64.5% = 6450)
        long value = (long) (ratio * 100);
        setGauge("compression_ratio", value);
    }

    public String exportPrometheusMetrics() {
        StringBuilder sb = new StringBuilder();

        // Export counters
        for (Map.Entry<String, AtomicLong> entry : counters.entrySet()) {
            String[] parts = parseKey(entry.getKey());
            String name = parts[0];
            String labels = parts[1];

            if (sb.indexOf("# TYPE " + name) == -1) {
                sb.append("# TYPE ").append(name).append(" counter\n");
            }
            sb.append(name);
            if (!labels.isEmpty()) {
                sb.append("{").append(labels).append("}");
            }
            sb.append(" ").append(entry.getValue().get()).append("\n");
        }

        // Export gauges
        for (Map.Entry<String, AtomicLong> entry : gauges.entrySet()) {
            String[] parts = parseKey(entry.getKey());
            String name = parts[0];
            String labels = parts[1];

            if (sb.indexOf("# TYPE " + name) == -1) {
                sb.append("# TYPE ").append(name).append(" gauge\n");
            }
            sb.append(name);
            if (!labels.isEmpty()) {
                sb.append("{").append(labels).append("}");
            }

            // Special handling for compression_ratio (convert back to decimal)
            if (name.equals("compression_ratio")) {
                double ratio = entry.getValue().get() / 100.0;
                sb.append(" ").append(ratio).append("\n");
            } else {
                sb.append(" ").append(entry.getValue().get()).append("\n");
            }
        }

        // Export histograms
        for (Map.Entry<String, List<Double>> entry : histograms.entrySet()) {
            String[] parts = parseKey(entry.getKey());
            String name = parts[0];
            String labels = parts[1];

            if (sb.indexOf("# TYPE " + name) == -1) {
                sb.append("# TYPE ").append(name).append(" histogram\n");
            }

            List<Double> observations = entry.getValue();
            if (observations.isEmpty()) {
                continue;
            }

            // Calculate histogram buckets
            if (name.equals("http_request_duration_seconds")) {
                exportDurationHistogram(sb, name, labels, observations);
            } else if (name.equals("http_response_size_bytes")) {
                exportSizeHistogram(sb, name, labels, observations);
            }
        }

        return sb.toString();
    }

    private void exportDurationHistogram(StringBuilder sb, String name, String labels, List<Double> observations) {
        long[] bucketCounts = new long[DURATION_BUCKETS.length];
        double sum = 0;

        for (Double value : observations) {
            sum += value;
            for (int i = 0; i < DURATION_BUCKETS.length; i++) {
                if (value <= DURATION_BUCKETS[i]) {
                    bucketCounts[i]++;
                }
            }
        }

        String baseLabels = labels.isEmpty() ? "" : labels + ",";

        for (int i = 0; i < DURATION_BUCKETS.length; i++) {
            sb.append(name).append("_bucket{").append(baseLabels)
              .append("le=\"").append(DURATION_BUCKETS[i]).append("\"} ")
              .append(bucketCounts[i]).append("\n");
        }

        sb.append(name).append("_bucket{").append(baseLabels).append("le=\"+Inf\"} ")
          .append(observations.size()).append("\n");

        sb.append(name).append("_sum");
        if (!labels.isEmpty()) {
            sb.append("{").append(labels).append("}");
        }
        sb.append(" ").append(sum).append("\n");

        sb.append(name).append("_count");
        if (!labels.isEmpty()) {
            sb.append("{").append(labels).append("}");
        }
        sb.append(" ").append(observations.size()).append("\n");
    }

    private void exportSizeHistogram(StringBuilder sb, String name, String labels, List<Double> observations) {
        long[] bucketCounts = new long[SIZE_BUCKETS.length];
        double sum = 0;

        for (Double value : observations) {
            sum += value;
            for (int i = 0; i < SIZE_BUCKETS.length; i++) {
                if (value <= SIZE_BUCKETS[i]) {
                    bucketCounts[i]++;
                }
            }
        }

        String baseLabels = labels.isEmpty() ? "" : labels + ",";

        for (int i = 0; i < SIZE_BUCKETS.length; i++) {
            sb.append(name).append("_bucket{").append(baseLabels)
              .append("le=\"").append(SIZE_BUCKETS[i]).append("\"} ")
              .append(bucketCounts[i]).append("\n");
        }

        sb.append(name).append("_bucket{").append(baseLabels).append("le=\"+Inf\"} ")
          .append(observations.size()).append("\n");

        sb.append(name).append("_sum");
        if (!labels.isEmpty()) {
            sb.append("{").append(labels).append("}");
        }
        sb.append(" ").append(sum).append("\n");

        sb.append(name).append("_count");
        if (!labels.isEmpty()) {
            sb.append("{").append(labels).append("}");
        }
        sb.append(" ").append(observations.size()).append("\n");
    }

    private String[] parseKey(String key) {
        int colonIndex = key.indexOf(':');
        if (colonIndex == -1) {
            return new String[]{key, ""};
        }

        String name = key.substring(0, colonIndex);
        String labelsStr = key.substring(colonIndex + 1).replace(":", ",");

        return new String[]{name, labelsStr};
    }

    public void reset() {
        counters.clear();
        gauges.clear();
        histograms.clear();
        initializeMetrics();
    }
}
