package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;

class SecurityHeadersTest {

    private SecurityHeadersHandler securityHandler;

    @BeforeEach
    void setUp() {
        securityHandler = new SecurityHeadersHandler();
    }

    @Test
    void testSecurityHeadersForTLS() throws IOException {
        Writer writer = new StringWriter();
        securityHandler.addSecurityHeaders(writer, true);
        String result = writer.toString();

        assertTrue(result.contains("Strict-Transport-Security:"),
            "HSTS header should be present for TLS");
        assertTrue(result.contains("max-age=63072000"),
            "HSTS should have max-age of 63072000 seconds (2 years)");
        assertTrue(result.contains("includeSubDomains"),
            "HSTS should include subdomains");
        assertTrue(result.contains("preload"),
            "HSTS should have preload directive");
    }

    @Test
    void testSecurityHeadersWithoutTLS() throws IOException {
        Writer writer = new StringWriter();
        securityHandler.addSecurityHeaders(writer, false);
        String result = writer.toString();

        assertFalse(result.contains("Strict-Transport-Security:"),
            "HSTS header should not be present when TLS is disabled");
    }

    @Test
    void testContentSecurityPolicy() throws IOException {
        Writer writer = new StringWriter();
        securityHandler.addSecurityHeaders(writer, true);
        String result = writer.toString();

        assertTrue(result.contains("Content-Security-Policy:"),
            "CSP header should be present");
        assertTrue(result.contains("default-src 'self'"),
            "CSP should restrict default source to self");
        assertTrue(result.contains("script-src 'self'"),
            "CSP should restrict scripts to self");
        assertTrue(result.contains("frame-ancestors 'none'"),
            "CSP should prevent clickjacking");
    }

    @Test
    void testXFrameOptions() throws IOException {
        Writer writer = new StringWriter();
        securityHandler.addSecurityHeaders(writer, true);
        String result = writer.toString();

        assertTrue(result.contains("X-Frame-Options: DENY"),
            "X-Frame-Options should deny framing");
    }

    @Test
    void testXContentTypeOptions() throws IOException {
        Writer writer = new StringWriter();
        securityHandler.addSecurityHeaders(writer, true);
        String result = writer.toString();

        assertTrue(result.contains("X-Content-Type-Options: nosniff"),
            "X-Content-Type-Options should prevent MIME sniffing");
    }

    @Test
    void testReferrerPolicy() throws IOException {
        Writer writer = new StringWriter();
        securityHandler.addSecurityHeaders(writer, true);
        String result = writer.toString();

        assertTrue(result.contains("Referrer-Policy: strict-origin-when-cross-origin"),
            "Referrer-Policy should protect privacy");
    }

    @Test
    void testPermissionsPolicy() throws IOException {
        Writer writer = new StringWriter();
        securityHandler.addSecurityHeaders(writer, true);
        String result = writer.toString();

        assertTrue(result.contains("Permissions-Policy:"),
            "Permissions-Policy header should be present");
        assertTrue(result.contains("geolocation=()"),
            "Geolocation should be disabled");
        assertTrue(result.contains("microphone=()"),
            "Microphone should be disabled");
        assertTrue(result.contains("camera=()"),
            "Camera should be disabled");
    }

    @Test
    void testAllHeadersPresent() throws IOException {
        Writer writer = new StringWriter();
        securityHandler.addSecurityHeaders(writer, true);
        String result = writer.toString();

        String[] requiredHeaders = {
            "Content-Security-Policy:",
            "X-Frame-Options:",
            "X-Content-Type-Options:",
            "Referrer-Policy:",
            "Permissions-Policy:",
            "Strict-Transport-Security:"
        };

        for (String header : requiredHeaders) {
            assertTrue(result.contains(header),
                "Security header '" + header + "' should be present");
        }
    }

    @Test
    void testHeaderFormatWithCRLF() throws IOException {
        Writer writer = new StringWriter();
        securityHandler.addSecurityHeaders(writer, true);
        String result = writer.toString();

        assertTrue(result.contains("\r\n"),
            "Headers should use CRLF line endings");
    }
}
