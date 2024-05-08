package HTTPServer;

import java.util.UUID;

public class TraceContextHandler {

    private static final String TRACE_VERSION = "00";

    public static class TraceContext {
        private final String traceId;
        private final String spanId;
        private final String parentSpanId;
        private final boolean sampled;

        public TraceContext() {
            this.traceId = generateTraceId();
            this.spanId = generateSpanId();
            this.parentSpanId = null;
            this.sampled = true;
        }

        private TraceContext(String traceId, String spanId, String parentSpanId, boolean sampled) {
            this.traceId = traceId;
            this.spanId = spanId;
            this.parentSpanId = parentSpanId;
            this.sampled = sampled;
        }

        public static TraceContext fromTraceparent(String traceparent) {
            if (traceparent == null || traceparent.isEmpty()) {
                return new TraceContext();
            }

            try {
                String[] parts = traceparent.split("-");
                if (parts.length != 4) {
                    return new TraceContext();
                }

                String version = parts[0];
                if (!version.equals(TRACE_VERSION)) {
                    return new TraceContext();
                }

                String traceId = parts[1];
                String parentSpanId = parts[2];
                String flags = parts[3];
                boolean sampled = flags.endsWith("1");

                String newSpanId = generateSpanId();
                return new TraceContext(traceId, newSpanId, parentSpanId, sampled);
            } catch (Exception e) {
                return new TraceContext();
            }
        }

        public String toTraceparent() {
            return String.format("%s-%s-%s-%s",
                TRACE_VERSION,
                traceId,
                spanId,
                sampled ? "01" : "00");
        }

        public String getTraceId() {
            return traceId;
        }

        public String getSpanId() {
            return spanId;
        }

        public String getParentSpanId() {
            return parentSpanId;
        }

        public boolean isSampled() {
            return sampled;
        }
    }

    public TraceContext extractContext(String traceparentHeader) {
        return TraceContext.fromTraceparent(traceparentHeader);
    }

    private static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "") +
               UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String generateSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
