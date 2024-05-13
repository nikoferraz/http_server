package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

public class HTTP2HPACKTest {

    private HPACKEncoder encoder;
    private HPACKDecoder decoder;

    @BeforeEach
    public void setUp() {
        encoder = new HPACKEncoder();
        decoder = new HPACKDecoder();
    }

    @Test
    public void testEncodeSingleLiteralHeader() {
        Map<String, String> headers = new HashMap<>();
        headers.put("custom-key", "custom-header");

        byte[] encoded = encoder.encode(headers);

        assertThat(encoded).isNotNull();
        assertThat(encoded.length).isGreaterThan(0);
    }

    @Test
    public void testEncodeStaticTableReference() {
        Map<String, String> headers = new HashMap<>();
        headers.put(":method", "GET");

        byte[] encoded = encoder.encode(headers);

        assertThat(encoded).isNotNull();
        assertThat(encoded.length).isGreaterThan(0);
    }

    @Test
    public void testEncodeLiteralHeaderNameReference() {
        Map<String, String> headers = new HashMap<>();
        headers.put(":method", "POST");

        byte[] encoded = encoder.encode(headers);

        assertThat(encoded).isNotNull();
        assertThat(encoded.length).isGreaterThan(0);
    }

    @Test
    public void testDecodeLiteralHeader() {
        Map<String, String> headers = new HashMap<>();
        headers.put("custom-key", "custom-header");

        byte[] encoded = encoder.encode(headers);
        Map<String, String> decoded = decoder.decode(encoded);

        assertThat(decoded).containsEntry("custom-key", "custom-header");
    }

    @Test
    public void testRoundTripSingleHeader() {
        Map<String, String> original = new HashMap<>();
        original.put("content-type", "text/html");

        byte[] encoded = encoder.encode(original);
        Map<String, String> decoded = decoder.decode(encoded);

        assertThat(decoded).containsEntry("content-type", "text/html");
    }

    @Test
    public void testRoundTripMultipleHeaders() {
        Map<String, String> original = new HashMap<>();
        original.put(":method", "GET");
        original.put(":path", "/");
        original.put(":scheme", "https");
        original.put(":authority", "www.example.com");

        byte[] encoded = encoder.encode(original);
        Map<String, String> decoded = decoder.decode(encoded);

        assertThat(decoded).containsEntries(
            ":method", "GET",
            ":path", "/",
            ":scheme", "https",
            ":authority", "www.example.com"
        );
    }

    @Test
    public void testDynamicTableUpdate() {
        Map<String, String> headers1 = new HashMap<>();
        headers1.put("custom-header", "value1");

        byte[] encoded1 = encoder.encode(headers1);

        Map<String, String> headers2 = new HashMap<>();
        headers2.put("custom-header", "value2");

        byte[] encoded2 = encoder.encode(headers2);

        // Second encoding should be shorter due to dynamic table
        assertThat(encoded2.length).isLessThanOrEqualTo(encoded1.length);
    }

    @Test
    public void testEncoderMaxTableSize() {
        HPACKEncoder enc = new HPACKEncoder(256); // 256 byte max table size

        Map<String, String> headers = new HashMap<>();
        headers.put("very-long-header-name", "very-long-header-value-that-exceeds-table-size");

        byte[] encoded = enc.encode(headers);
        assertThat(encoded).isNotNull();
    }

    @Test
    public void testIntegerEncoding() {
        // Test HPACK integer encoding for values larger than single byte
        byte[] encoded = encoder.encodeInteger(1337, 5);
        assertThat(encoded).isNotNull();

        int decoded = decoder.decodeInteger(ByteBuffer.wrap(encoded), 5);
        assertThat(decoded).isEqualTo(1337);
    }

    @Test
    public void testStringEncoding() {
        String testString = "Hello, World!";
        byte[] encoded = encoder.encodeString(testString);

        assertThat(encoded).isNotNull();
        assertThat(encoded.length).isGreaterThan(0);

        String decoded = decoder.decodeString(ByteBuffer.wrap(encoded));
        assertThat(decoded).isEqualTo(testString);
    }

    @Test
    public void testEmptyHeaders() {
        Map<String, String> headers = new HashMap<>();

        byte[] encoded = encoder.encode(headers);
        Map<String, String> decoded = decoder.decode(encoded);

        assertThat(decoded).isEmpty();
    }

    @Test
    public void testLargeHeaderValue() {
        Map<String, String> headers = new HashMap<>();
        StringBuilder largeValue = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            largeValue.append("x");
        }
        headers.put("large-header", largeValue.toString());

        byte[] encoded = encoder.encode(headers);
        Map<String, String> decoded = decoder.decode(encoded);

        assertThat(decoded).containsEntry("large-header", largeValue.toString());
    }

    @Test
    public void testStaticTableReferences() {
        Map<String, String> headers = new HashMap<>();
        headers.put(":method", "GET");
        headers.put(":scheme", "https");
        headers.put(":path", "/index.html");

        byte[] encoded = encoder.encode(headers);

        // Encoding should be reasonably small for static table references
        assertThat(encoded.length).isLessThan(50);
    }
}
