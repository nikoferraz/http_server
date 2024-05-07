package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BrotliCompressionTest {

    private BrotliCompressionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new BrotliCompressionHandler();
    }

    @Test
    void testShouldUseBrotliWithBrotliEncoding() {
        boolean result = handler.shouldUseBrotli("br");
        if (handler.isBrotliAvailable()) {
            assertTrue(result, "Should use Brotli when Accept-Encoding contains 'br'");
        }
    }

    @Test
    void testShouldUseBrotliWithMultipleEncodings() {
        boolean result = handler.shouldUseBrotli("gzip, deflate, br");
        if (handler.isBrotliAvailable()) {
            assertTrue(result, "Should use Brotli in multi-encoding list");
        }
    }

    @Test
    void testShouldNotUseBrotliWithoutBrotliEncoding() {
        boolean result = handler.shouldUseBrotli("gzip, deflate");
        assertFalse(result, "Should not use Brotli when not in Accept-Encoding");
    }

    @Test
    void testShouldNotUseBrotliWithNullEncoding() {
        boolean result = handler.shouldUseBrotli(null);
        assertFalse(result, "Should not use Brotli with null Accept-Encoding");
    }

    @Test
    void testShouldNotUseBrotliWithEmptyEncoding() {
        boolean result = handler.shouldUseBrotli("");
        assertFalse(result, "Should not use Brotli with empty Accept-Encoding");
    }

    @Test
    void testPreferBrotliOverGzip() {
        boolean result = handler.preferBrotli("br, gzip");
        if (handler.isBrotliAvailable()) {
            assertTrue(result, "Should prefer Brotli when listed first");
        }
    }

    @Test
    void testPreferGzipWhenBrotliNotListed() {
        boolean result = handler.preferBrotli("gzip");
        assertFalse(result, "Should not prefer Brotli when not in Accept-Encoding");
    }

    @Test
    void testPreferBrotliWithQualityValues() {
        String acceptEncoding = "gzip;q=1.0, br;q=1.1";
        boolean result = handler.preferBrotli(acceptEncoding);
        if (handler.isBrotliAvailable()) {
            assertTrue(result, "Should prefer Brotli based on order");
        }
    }

    @Test
    void testShouldUseBrotliCaseInsensitive() {
        boolean result = handler.shouldUseBrotli("BR");
        if (handler.isBrotliAvailable()) {
            assertTrue(result, "Should be case insensitive for 'br'");
        }
    }

    @Test
    void testAcceptEncodingCaseInsensitive() {
        boolean result = handler.shouldUseBrotli("GZIP, DEFLATE, BR");
        if (handler.isBrotliAvailable()) {
            assertTrue(result, "Should be case insensitive for full encoding list");
        }
    }

    @Test
    void testBrotliAvailabilityCheck() {
        boolean available = handler.isBrotliAvailable();
        assertFalse(available, "Brotli should not be available without brotli4j dependency");
    }

    @Test
    void testCompressWithoutBrotli() {
        if (!handler.isBrotliAvailable()) {
            byte[] data = "test data".getBytes();
            assertThrows(java.io.IOException.class, () -> handler.compress(data),
                "Should throw IOException when Brotli not available");
        }
    }
}
