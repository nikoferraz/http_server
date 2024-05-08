package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceContextTest {

    private TraceContextHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TraceContextHandler();
    }

    @Test
    void testCreateNewTraceContext() {
        TraceContextHandler.TraceContext context = new TraceContextHandler.TraceContext();

        assertNotNull(context.getTraceId(), "TraceId should not be null");
        assertNotNull(context.getSpanId(), "SpanId should not be null");
        assertTrue(context.isSampled(), "New context should be sampled by default");
    }

    @Test
    void testTraceIdFormat() {
        TraceContextHandler.TraceContext context = new TraceContextHandler.TraceContext();
        String traceId = context.getTraceId();

        assertEquals(32, traceId.length(), "TraceId should be 32 hex characters");
        assertTrue(traceId.matches("[0-9a-f]{32}"), "TraceId should only contain hex characters");
    }

    @Test
    void testSpanIdFormat() {
        TraceContextHandler.TraceContext context = new TraceContextHandler.TraceContext();
        String spanId = context.getSpanId();

        assertEquals(16, spanId.length(), "SpanId should be 16 hex characters");
        assertTrue(spanId.matches("[0-9a-f]{16}"), "SpanId should only contain hex characters");
    }

    @Test
    void testTraceparentFormat() {
        TraceContextHandler.TraceContext context = new TraceContextHandler.TraceContext();
        String traceparent = context.toTraceparent();

        String[] parts = traceparent.split("-");
        assertEquals(4, parts.length, "Traceparent should have 4 parts");
        assertEquals("00", parts[0], "Version should be 00");
        assertEquals(32, parts[1].length(), "TraceId should be 32 hex chars");
        assertEquals(16, parts[2].length(), "SpanId should be 16 hex chars");
        assertEquals("01", parts[3], "Flags should be 01 for sampled");
    }

    @Test
    void testParseValidTraceparent() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        TraceContextHandler.TraceContext context =
            TraceContextHandler.TraceContext.fromTraceparent(traceparent);

        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", context.getTraceId(),
            "Should extract traceId from traceparent");
        assertEquals("00f067aa0ba902b7", context.getParentSpanId(),
            "Should extract parent spanId from traceparent");
        assertTrue(context.isSampled(), "Should be marked as sampled");
    }

    @Test
    void testParseTraceparentNotSampled() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00";
        TraceContextHandler.TraceContext context =
            TraceContextHandler.TraceContext.fromTraceparent(traceparent);

        assertFalse(context.isSampled(), "Should not be sampled with flags=00");
    }

    @Test
    void testParseInvalidTraceparent() {
        String traceparent = "invalid-format";
        TraceContextHandler.TraceContext context =
            TraceContextHandler.TraceContext.fromTraceparent(traceparent);

        assertNotNull(context, "Should return new context for invalid input");
        assertNotNull(context.getTraceId(), "Should generate new traceId for invalid input");
    }

    @Test
    void testParseNullTraceparent() {
        TraceContextHandler.TraceContext context =
            TraceContextHandler.TraceContext.fromTraceparent(null);

        assertNotNull(context, "Should return new context for null input");
        assertTrue(context.isSampled(), "New context should be sampled");
    }

    @Test
    void testParseEmptyTraceparent() {
        TraceContextHandler.TraceContext context =
            TraceContextHandler.TraceContext.fromTraceparent("");

        assertNotNull(context, "Should return new context for empty input");
        assertTrue(context.isSampled(), "New context should be sampled");
    }

    @Test
    void testExtractContextFromHeader() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        TraceContextHandler.TraceContext context = handler.extractContext(traceparent);

        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", context.getTraceId());
        assertTrue(context.isSampled());
    }

    @Test
    void testNewSpanGeneratedFromExistingTrace() {
        String originalTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        TraceContextHandler.TraceContext context =
            TraceContextHandler.TraceContext.fromTraceparent(originalTraceparent);

        assertNotEquals("00f067aa0ba902b7", context.getSpanId(),
            "Should generate new spanId for child span");
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", context.getTraceId(),
            "Should preserve traceId in child span");
    }

    @Test
    void testUniquenessOfGeneratedTraces() {
        TraceContextHandler.TraceContext context1 = new TraceContextHandler.TraceContext();
        TraceContextHandler.TraceContext context2 = new TraceContextHandler.TraceContext();

        assertNotEquals(context1.getTraceId(), context2.getTraceId(),
            "Generated traceIds should be unique");
        assertNotEquals(context1.getSpanId(), context2.getSpanId(),
            "Generated spanIds should be unique");
    }

    @Test
    void testInvalidVersionTraceparent() {
        String traceparent = "02-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        TraceContextHandler.TraceContext context =
            TraceContextHandler.TraceContext.fromTraceparent(traceparent);

        assertNotNull(context, "Should return new context for unsupported version");
        assertTrue(context.isSampled(), "New context should be sampled");
    }
}
