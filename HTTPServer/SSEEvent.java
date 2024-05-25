package HTTPServer;

import java.nio.charset.StandardCharsets;

/**
 * Represents a Server-Sent Event with optional id, retry, and event type fields.
 * Events are formatted according to the W3C Server-Sent Events specification.
 *
 * Format:
 *   [event: type\n]
 *   [id: identifier\n]
 *   [retry: milliseconds\n]
 *   data: content\n
 *   [\n]
 *
 * Each line must be terminated with \n (LF only).
 * Events are separated by \n\n (blank line).
 */
public class SSEEvent {

    private final String eventType;
    private final String data;
    private final String id;
    private final long retryMs;
    private final long timestamp;

    private static final String DEFAULT_EVENT_TYPE = "message";
    private static final String FIELD_PREFIX_EVENT = "event: ";
    private static final String FIELD_PREFIX_ID = "id: ";
    private static final String FIELD_PREFIX_RETRY = "retry: ";
    private static final String FIELD_PREFIX_DATA = "data: ";
    private static final String LINE_SEPARATOR = "\n";
    private static final String EVENT_SEPARATOR = "\n";

    public SSEEvent(String data) {
        this(data, null, null, -1);
    }

    public SSEEvent(String data, String eventType) {
        this(data, eventType, null, -1);
    }

    public SSEEvent(String data, String eventType, String id) {
        this(data, eventType, id, -1);
    }

    public SSEEvent(String data, String eventType, String id, long retryMs) {
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("SSE event data cannot be null or empty");
        }
        this.data = data;
        this.eventType = eventType != null ? eventType : DEFAULT_EVENT_TYPE;
        this.id = id;
        this.retryMs = retryMs > 0 ? retryMs : -1;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Formats the event according to SSE specification.
     * Returns the event as bytes in UTF-8 encoding ready to be sent over the wire.
     *
     * @return formatted event as byte array
     */
    public byte[] toBytes() {
        StringBuilder sb = new StringBuilder();

        if (eventType != null && !eventType.equals(DEFAULT_EVENT_TYPE)) {
            sb.append(FIELD_PREFIX_EVENT).append(eventType).append(LINE_SEPARATOR);
        }

        if (id != null && !id.isEmpty()) {
            sb.append(FIELD_PREFIX_ID).append(id).append(LINE_SEPARATOR);
        }

        if (retryMs > 0) {
            sb.append(FIELD_PREFIX_RETRY).append(retryMs).append(LINE_SEPARATOR);
        }

        // Handle multi-line data: each line must be prefixed with "data: "
        String[] lines = data.split("\n", -1);
        for (String line : lines) {
            sb.append(FIELD_PREFIX_DATA).append(line).append(LINE_SEPARATOR);
        }

        // Event separator: blank line
        sb.append(EVENT_SEPARATOR);

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Formats the event as a string representation.
     *
     * @return formatted event string
     */
    @Override
    public String toString() {
        return new String(toBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Creates a keepalive comment (colon line) to prevent timeout.
     * Comments are lines that start with ':' and are ignored by clients.
     *
     * @return keepalive event as bytes
     */
    public static byte[] keepaliveComment() {
        return (":" + LINE_SEPARATOR).getBytes(StandardCharsets.UTF_8);
    }

    // Getters
    public String getEventType() {
        return eventType;
    }

    public String getData() {
        return data;
    }

    public String getId() {
        return id;
    }

    public long getRetryMs() {
        return retryMs;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
