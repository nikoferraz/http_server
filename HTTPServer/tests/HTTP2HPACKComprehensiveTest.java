package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

public class HTTP2HPACKComprehensiveTest {

    private HPACKEncoder encoder;
    private HPACKDecoder decoder;

    @BeforeEach
    public void setUp() {
        encoder = new HPACKEncoder();
        decoder = new HPACKDecoder();
    }

    @Nested
    class StaticTableTests {

        @Test
        public void testStaticTableEntry_Method_GET() {
            Map<String, String> headers = new HashMap<>();
            headers.put(":method", "GET");

            byte[] encoded = encoder.encode(headers);
            Map<String, String> decoded = decoder.decode(encoded);

            assertThat(decoded).containsEntry(":method", "GET");
        }

        @Test
        public void testStaticTableEntry_Method_POST() {
            Map<String, String> headers = new HashMap<>();
            headers.put(":method", "POST");

            byte[] encoded = encoder.encode(headers);
            Map<String, String> decoded = decoder.decode(encoded);

            assertThat(decoded).containsEntry(":method", "POST");
        }

        @Test
        public void testStaticTableEntry_Path_Root() {
            Map<String, String> headers = new HashMap<>();
            headers.put(":path", "/");

            byte[] encoded = encoder.encode(headers);
            Map<String, String> decoded = decoder.decode(encoded);

            assertThat(decoded).containsEntry(":path", "/");
        }

        @Test
        public void testStaticTableEntry_Path_IndexHtml() {
            Map<String, String> headers = new HashMap<>();
            headers.put(":path", "/index.html");

            byte[] encoded = encoder.encode(headers);
            Map<String, String> decoded = decoder.decode(encoded);

            assertThat(decoded).containsEntry(":path", "/index.html");
        }

        @Test
        public void testStaticTableEntry_Scheme_HTTP() {
            Map<String, String> headers = new HashMap<>();
            headers.put(":scheme", "http");

            byte[] encoded = encoder.encode(headers);
            Map<String, String> decoded = decoder.decode(encoded);

            assertThat(decoded).containsEntry(":scheme", "http");
        }

        @Test
        public void testStaticTableEntry_Scheme_HTTPS() {
            Map<String, String> headers = new HashMap<>();
            headers.put(":scheme", "https");

            byte[] encoded = encoder.encode(headers);
            Map<String, String> decoded = decoder.decode(encoded);

            assertThat(decoded).containsEntry(":scheme", "https");
        }

        @Test
        public void testStaticTableEntry_Status_200() {
            Map<String, String> headers = new HashMap<>();
            headers.put(":status", "200");

            byte[] encoded = encoder.encode(headers);
            Map<String, String> decoded = decoder.decode(encoded);

            assertThat(decoded).containsEntry(":status", "200");
        }

        @Test
        public void testMultipleStaticTableEntries() {
            Map<String, String> headers = new HashMap<>();
            headers.put(":method", "GET");
            headers.put(":scheme", "https");
            headers.put(":path", "/");
            headers.put(":authority", "example.com");

            byte[] encoded = encoder.encode(headers);
            Map<String, String> decoded = decoder.decode(encoded);

            assertThat(decoded).containsEntry(":method", "GET");
            assertThat(decoded).containsEntry(":scheme", "https");
            assertThat(decoded).containsEntry(":path", "/");
        }
    }

    @Nested
    class DynamicTableTests {

        @Test
        public void testDynamicTableEntryInserted() {
            Map<String, String> headers = new HashMap<>();
            headers.put("custom-header", "value1");

            byte[] encoded = encoder.encode(headers);
            assertThat(encoded).isNotNull();
            assertThat(encoded.length).isGreaterThan(0);
        }

        @Test
        public void testDynamicTableEviction() {
            HPACKEncoder smallEncoder = new HPACKEncoder(256); // Small table size

            Map<String, String> headers1 = new HashMap<>();
            headers1.put("header1", "value1_that_is_long_enough_to_take_space");

            Map<String, String> headers2 = new HashMap<>();
            headers2.put("header2", "value2_that_is_also_long_to_evict_previous");

            byte[] encoded1 = smallEncoder.encode(headers1);
            byte[] encoded2 = smallEncoder.encode(headers2);

            assertThat(encoded1).isNotNull();
            assertThat(encoded2).isNotNull();
        }

        @Test
        public void testDynamicTableSizeUpdate() {
            Map<String, String> headers1 = new HashMap<>();
            headers1.put("dynamic-header", "value1");
            byte[] encoded1 = encoder.encode(headers1);

            Map<String, String> headers2 = new HashMap<>();
            headers2.put("dynamic-header", "value2");
            byte[] encoded2 = encoder.encode(headers2);

            // Second encoding should be more efficient due to dynamic table
            assertThat(encoded2.length).isLessThanOrEqualTo(encoded1.length);
        }

        @Test
        public void testMultipleDynamicTableEntries() {
            Map<String, String> headers1 = new HashMap<>();
            headers1.put("custom-1", "value-1");
            byte[] encoded1 = encoder.encode(headers1);

            Map<String, String> headers2 = new HashMap<>();
            headers2.put("custom-2", "value-2");
            byte[] encoded2 = encoder.encode(headers2);

            Map<String, String> headers3 = new HashMap<>();
            headers3.put("custom-3", "value-3");
            byte[] encoded3 = encoder.encode(headers3);

            assertThat(encoded1).isNotNull();
            assertThat(encoded2).isNotNull();
            assertThat(encoded3).isNotNull();
        }
    }

    @Nested
    class TableSizeConstraintTests {

        @Test
        public void testMaxTableSizeConstraint() {
            HPACKEncoder encoder256 = new HPACKEncoder(256);
            HPACKEncoder encoder1024 = new HPACKEncoder(1024);
            HPACKEncoder encoder4096 = new HPACKEncoder(4096);

            Map<String, String> headers = new HashMap<>();
            headers.put("test", "value");

            byte[] encoded256 = encoder256.encode(headers);
            byte[] encoded1024 = encoder1024.encode(headers);
            byte[] encoded4096 = encoder4096.encode(headers);

            assertThat(encoded256).isNotNull();
            assertThat(encoded1024).isNotNull();
            assertThat(encoded4096).isNotNull();
        }

        @Test
        public void testHeaderExceedsTableSize() {
            HPACKEncoder smallEncoder = new HPACKEncoder(128);

            Map<String, String> headers = new HashMap<>();
            headers.put("very-long-header-name", "very-long-header-value-that-exceeds-table");

            byte[] encoded = smallEncoder.encode(headers);
            assertThat(encoded).isNotNull();
        }

        @Test
        public void testMaxTableSizeZero() {
            HPACKEncoder zeroEncoder = new HPACKEncoder(0);

            Map<String, String> headers = new HashMap<>();
            headers.put("header", "value");

            byte[] encoded = zeroEncoder.encode(headers);
            assertThat(encoded).isNotNull();
        }
    }

    @Nested
    class IntegerEncodingTests {

        @Test
        public void testSmallInteger() {
            byte[] encoded = encoder.encodeInteger(10, 5);
            assertThat(encoded).isNotNull();

            int decoded = decoder.decodeInteger(ByteBuffer.wrap(encoded), 5);
            assertThat(decoded).isEqualTo(10);
        }

        @Test
        public void testLargeInteger() {
            byte[] encoded = encoder.encodeInteger(1337, 5);
            assertThat(encoded).isNotNull();

            int decoded = decoder.decodeInteger(ByteBuffer.wrap(encoded), 5);
            assertThat(decoded).isEqualTo(1337);
        }

        @Test
        public void testVeryLargeInteger() {
            byte[] encoded = encoder.encodeInteger(100000, 5);
            assertThat(encoded).isNotNull();

            int decoded = decoder.decodeInteger(ByteBuffer.wrap(encoded), 5);
            assertThat(decoded).isEqualTo(100000);
        }

        @Test
        public void testIntegerWithDifferentPrefixBits() {
            for (int prefixBits = 1; prefixBits <= 8; prefixBits++) {
                byte[] encoded = encoder.encodeInteger(42, prefixBits);
                assertThat(encoded).isNotNull();

                int decoded = decoder.decodeInteger(ByteBuffer.wrap(encoded), prefixBits);
                assertThat(decoded).isEqualTo(42);
            }
        }

        @Test
        public void testIntegerBoundaryValues() {
            int[] testValues = {0, 1, 127, 128, 255, 256, 65535, 65536, Integer.MAX_VALUE};

            for (int value : testValues) {
                byte[] encoded = encoder.encodeInteger(value, 5);
                assertThat(encoded).isNotNull();

                int decoded = decoder.decodeInteger(ByteBuffer.wrap(encoded), 5);
                assertThat(decoded).isEqualTo(value);
            }
        }
    }

    @Nested
    class StringEncodingTests {

        @Test
        public void testSimpleString() {
            String testString = "Hello";
            byte[] encoded = encoder.encodeString(testString);
            assertThat(encoded).isNotNull();

            String decoded = decoder.decodeString(ByteBuffer.wrap(encoded));
            assertThat(decoded).isEqualTo(testString);
        }

        @Test
        public void testEmptyString() {
            String testString = "";
            byte[] encoded = encoder.encodeString(testString);
            assertThat(encoded).isNotNull();

            String decoded = decoder.decodeString(ByteBuffer.wrap(encoded));
            assertThat(decoded).isEqualTo(testString);
        }

        @Test
        public void testLongString() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 1000; i++) {
                sb.append("x");
            }
            String testString = sb.toString();

            byte[] encoded = encoder.encodeString(testString);
            assertThat(encoded).isNotNull();

            String decoded = decoder.decodeString(ByteBuffer.wrap(encoded));
            assertThat(decoded).isEqualTo(testString);
        }

        @Test
        public void testSpecialCharactersString() {
            String testString = "!@#$%^&*()_+-=[]{}|;:',.<>?/`~";
            byte[] encoded = encoder.encodeString(testString);
            assertThat(encoded).isNotNull();

            String decoded = decoder.decodeString(ByteBuffer.wrap(encoded));
            assertThat(decoded).isEqualTo(testString);
        }

        @Test
        public void testUnicodeString() {
            String testString = "Hello 世界 مرحبا мир";
            byte[] encoded = encoder.encodeString(testString);
            assertThat(encoded).isNotNull();

            String decoded = decoder.decodeString(ByteBuffer.wrap(encoded));
            assertThat(decoded).isEqualTo(testString);
        }

        @Test
        public void testWhitespaceString() {
            String testString = "  \t\n  ";
            byte[] encoded = encoder.encodeString(testString);
            assertThat(encoded).isNotNull();

            String decoded = decoder.decodeString(ByteBuffer.wrap(encoded));
            assertThat(decoded).isEqualTo(testString);
        }
    }

    @Nested
    class EdgeCaseEncodingsTests {

        @Test
        public void testEmptyHeaderMap() {
            Map<String, String> headers = new HashMap<>();

            byte[] encoded = encoder.encode(headers);
            Map<String, String> decoded = decoder.decode(encoded);

            assertThat(decoded).isEmpty();
        }

        @Test
        public void testSingleHeaderWithEmptyValue() {
            Map<String, String> headers = new HashMap<>();
            headers.put("empty-value", "");

            byte[] encoded = encoder.encode(headers);
            Map<String, String> decoded = decoder.decode(encoded);

            assertThat(decoded).containsEntry("empty-value", "");
        }

        @Test
        public void testMultipleHeadersWithEmptyValues() {
            Map<String, String> headers = new HashMap<>();
            headers.put("header1", "");
            headers.put("header2", "");
            headers.put("header3", "");

            byte[] encoded = encoder.encode(headers);
            Map<String, String> decoded = decoder.decode(encoded);

            assertThat(decoded).containsEntry("header1", "");
            assertThat(decoded).containsEntry("header2", "");
            assertThat(decoded).containsEntry("header3", "");
        }

        @Test
        public void testVeryLargeHeaderValue() {
            StringBuilder largeValue = new StringBuilder();
            for (int i = 0; i < 10000; i++) {
                largeValue.append("X");
            }

            Map<String, String> headers = new HashMap<>();
            headers.put("large-header", largeValue.toString());

            byte[] encoded = encoder.encode(headers);
            Map<String, String> decoded = decoder.decode(encoded);

            assertThat(decoded).containsEntry("large-header", largeValue.toString());
        }

        @Test
        public void testManySmallHeaders() {
            Map<String, String> headers = new HashMap<>();
            for (int i = 0; i < 100; i++) {
                headers.put("header-" + i, "value-" + i);
            }

            byte[] encoded = encoder.encode(headers);
            Map<String, String> decoded = decoder.decode(encoded);

            for (int i = 0; i < 100; i++) {
                assertThat(decoded).containsEntry("header-" + i, "value-" + i);
            }
        }
    }

    @Nested
    class CompressionRatioTests {

        @Test
        public void testStaticTableCompression() {
            Map<String, String> headers = new HashMap<>();
            headers.put(":method", "GET");
            headers.put(":scheme", "https");
            headers.put(":path", "/");

            byte[] encoded = encoder.encode(headers);
            // Static table entries should compress well
            assertThat(encoded.length).isLessThan(50);
        }

        @Test
        public void testDynamicTableCompression() {
            Map<String, String> headers1 = new HashMap<>();
            headers1.put("custom-header", "custom-value");
            byte[] encoded1 = encoder.encode(headers1);

            Map<String, String> headers2 = new HashMap<>();
            headers2.put("custom-header", "custom-value");
            byte[] encoded2 = encoder.encode(headers2);

            // Second encoding should be shorter
            assertThat(encoded2.length).isLessThanOrEqualTo(encoded1.length);
        }

        @Test
        public void testRepeatingHeadersCompression() {
            Map<String, String> headers = new HashMap<>();
            for (int i = 0; i < 10; i++) {
                headers.put("repeating", "header-value-" + i);
            }

            byte[] encoded = encoder.encode(headers);
            assertThat(encoded).isNotNull();
            assertThat(encoded.length).isGreaterThan(0);
        }
    }

    @Nested
    class RoundTripTests {

        @Test
        public void testSimpleRoundTrip() {
            Map<String, String> original = new HashMap<>();
            original.put("content-type", "text/html");

            byte[] encoded = encoder.encode(original);
            Map<String, String> decoded = decoder.decode(encoded);

            assertThat(decoded).isEqualTo(original);
        }

        @Test
        public void testComplexRoundTrip() {
            Map<String, String> original = new HashMap<>();
            original.put(":method", "GET");
            original.put(":path", "/api/users");
            original.put(":scheme", "https");
            original.put(":authority", "api.example.com");
            original.put("accept", "application/json");
            original.put("user-agent", "MyApp/1.0");

            byte[] encoded = encoder.encode(original);
            Map<String, String> decoded = decoder.decode(encoded);

            for (Map.Entry<String, String> entry : original.entrySet()) {
                assertThat(decoded).containsEntry(entry.getKey(), entry.getValue());
            }
        }

        @Test
        public void testRoundTripPreservesValues() {
            Map<String, String> original = new HashMap<>();
            original.put("header1", "value1");
            original.put("header2", "value2");
            original.put("header3", "value3");

            byte[] encoded = encoder.encode(original);
            Map<String, String> decoded = decoder.decode(encoded);

            assertThat(decoded.size()).isEqualTo(original.size());
            for (String key : original.keySet()) {
                assertThat(decoded.get(key)).isEqualTo(original.get(key));
            }
        }
    }

    @Nested
    class StatePreservationTests {

        @Test
        public void testEncoderDecoderConsistency() {
            HPACKEncoder encoder1 = new HPACKEncoder();
            HPACKDecoder decoder1 = new HPACKDecoder();

            Map<String, String> headers1 = new HashMap<>();
            headers1.put("test-1", "value-1");

            byte[] encoded1 = encoder1.encode(headers1);
            Map<String, String> decoded1 = decoder1.decode(encoded1);

            Map<String, String> headers2 = new HashMap<>();
            headers2.put("test-2", "value-2");

            byte[] encoded2 = encoder1.encode(headers2);
            Map<String, String> decoded2 = decoder1.decode(encoded2);

            assertThat(decoded1).containsEntry("test-1", "value-1");
            assertThat(decoded2).containsEntry("test-2", "value-2");
        }
    }
}
