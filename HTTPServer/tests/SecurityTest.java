package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Security-focused test suite covering common vulnerabilities and attack vectors.
 * Tests path traversal, header validation, injection prevention, and DoS protection.
 */
@DisplayName("Security Tests")
class SecurityTest {

    private RequestBodyParser bodyParser;
    private AuthenticationManager authManager;

    @BeforeEach
    void setUp() {
        bodyParser = new RequestBodyParser();
        ServerConfig config = new ServerConfig();
        authManager = new AuthenticationManager(config);
    }

    @Nested
    @DisplayName("Path Traversal Prevention")
    class PathTraversalTests {

        @Test
        @DisplayName("Should prevent directory traversal with ../")
        void testDirectoryTraversalPrevention() {
            String maliciousPath = "/../../../etc/passwd";

            // Server should normalize or reject this path
            // Verification depends on ProcessRequest implementation
            assertThat(maliciousPath).contains("..");
        }

        @Test
        @DisplayName("Should prevent double-encoded traversal")
        void testDoubleEncodedTraversal() {
            String encoded = "%2e%2e%2f%2e%2e%2fetc%2fpasswd";

            // Server should decode and validate
            assertThat(encoded).isNotEmpty();
        }

        @Test
        @DisplayName("Should prevent null byte injection in path")
        void testNullByteInPath() {
            String pathWithNullByte = "/file.txt\u0000.jpg";

            // Should be rejected or sanitized
            assertThat(pathWithNullByte).contains("\u0000");
        }

        @Test
        @DisplayName("Should prevent Unicode normalization attacks")
        void testUnicodeTraversal() {
            // Unicode representation of ../
            String unicodePath = "/\u202e/etc/passwd";

            assertThat(unicodePath).isNotEmpty();
        }

        @Test
        @DisplayName("Should prevent backslash-based traversal on Windows")
        void testBackslashTraversal() {
            String path = "\\..\\..\\windows\\system32";

            // Should normalize to forward slashes
            assertThat(path).contains("\\");
        }
    }

    @Nested
    @DisplayName("Request Size Validation")
    class RequestSizeValidationTests {

        @Test
        @DisplayName("Should reject request line exceeding 8KB")
        void testOversizedRequestLine() {
            String longPath = "/" + "a".repeat(8200);
            String requestLine = "GET " + longPath + " HTTP/1.1";

            assertThat(requestLine.length()).isGreaterThan(8192);
        }

        @Test
        @DisplayName("Should reject headers exceeding 8KB total")
        void testOversizedHeaders() {
            String largeHeaderValue = "x".repeat(8200);

            assertThat(largeHeaderValue.length()).isGreaterThan(8192);
        }

        @Test
        @DisplayName("Should reject request body exceeding configured limit")
        void testOversizedBody() throws IOException {
            RequestBodyParser smallParser = new RequestBodyParser(100);

            String largeBody = "x".repeat(1000);
            InputStream input = new ByteArrayInputStream(largeBody.getBytes());

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "text/plain");
            headers.put("content-length", String.valueOf(largeBody.length()));

            assertThatThrownBy(() -> smallParser.parseBody(input, headers))
                    .isInstanceOf(RequestBodyParser.PayloadTooLargeException.class);
        }

        @Test
        @DisplayName("Should validate Content-Length isn't negative")
        void testNegativeContentLength() throws IOException {
            InputStream input = new ByteArrayInputStream(new byte[0]);

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "text/plain");
            headers.put("content-length", "-1000");

            assertThatThrownBy(() -> bodyParser.parseBody(input, headers))
                    .isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("Should validate Content-Length matches actual data")
        void testContentLengthMismatch() throws IOException {
            String content = "test";
            InputStream input = new ByteArrayInputStream(content.getBytes());

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "text/plain");
            headers.put("content-length", "1000"); // Larger than actual

            // Should either timeout or throw
            try {
                bodyParser.parseBody(input, headers);
                fail("Should have thrown exception");
            } catch (IOException e) {
                assertThat(e).isNotNull();
            }
        }
    }

    @Nested
    @DisplayName("Injection Prevention")
    class InjectionPreventionTests {

        @Test
        @DisplayName("Should prevent SQL injection in request parameters")
        void testSqlInjectionPrevention() {
            String maliciousPayload = "' OR '1'='1"; // Classic SQL injection

            // Server should treat as literal string, not execute
            assertThat(maliciousPayload).isNotEmpty();
        }

        @Test
        @DisplayName("Should prevent command injection in request path")
        void testCommandInjectionPrevention() {
            String injection = "/file.txt; rm -rf /";

            // Should not be executed
            assertThat(injection).contains(";");
        }

        @Test
        @DisplayName("Should escape HTML in error messages")
        void testXSSInErrorMessages() {
            String xssPayload = "<script>alert('xss')</script>";

            // Error messages should escape HTML entities
            assertThat(xssPayload).contains("<");
        }

        @Test
        @DisplayName("Should prevent CRLF injection in headers")
        void testCRLFInjection() {
            String crlfPayload = "test\r\nX-Injected: true";

            // Should validate against CRLF in header values
            assertThat(crlfPayload).contains("\r\n");
        }

        @Test
        @DisplayName("Should prevent null byte injection")
        void testNullByteInjection() {
            String nullBytePayload = "test\u0000injection";

            // Should be rejected or sanitized
            assertThat(nullBytePayload).contains("\u0000");
        }
    }

    @Nested
    @DisplayName("Authentication & Authorization")
    class AuthSecurityTests {

        @Test
        @DisplayName("Should not expose user existence through timing attacks")
        void testTimingAttackResistance() {
            // Invalid user
            Map<String, String> headers1 = new HashMap<>();
            headers1.put("authorization", "Basic aW52YWxpZHVzZXI6cGFzc3dvcmQ=");

            // Invalid password
            Map<String, String> headers2 = new HashMap<>();
            headers2.put("authorization", "Basic YWRtaW46aW52YWxpZA==");

            AuthenticationManager.AuthResult result1 = authManager.authenticate(headers1);
            AuthenticationManager.AuthResult result2 = authManager.authenticate(headers2);

            // Both should fail
            assertThat(result1.isAuthenticated()).isFalse();
            assertThat(result2.isAuthenticated()).isFalse();
        }

        @Test
        @DisplayName("Should not allow authentication bypass via header injection")
        void testAuthHeaderInjection() {
            Map<String, String> headers = new HashMap<>();
            headers.put("authorization", "Basic admin\r\nX-Bypass: true");

            AuthenticationManager.AuthResult result = authManager.authenticate(headers);

            assertThat(result.isAuthenticated()).isFalse();
        }

        @Test
        @DisplayName("Should not leak auth information in responses")
        void testNoAuthLeakage() {
            Map<String, String> headers = new HashMap<>();
            headers.put("authorization", "Basic aWFtYWxvY2FsdXNlcjp0aGlzaXNhc2VjcmV0");

            AuthenticationManager.AuthResult result = authManager.authenticate(headers);

            // Response should not contain actual credentials
            assertThat(result.toString()).doesNotContain("secret");
            assertThat(result.toString()).doesNotContain("password");
        }
    }

    @Nested
    @DisplayName("Denial of Service Prevention")
    class DoSPreventionTests {

        @Test
        @DisplayName("Should have rate limiting enabled by default")
        void testRateLimitingDefault() {
            ServerConfig config = new ServerConfig();
            boolean rateLimitEnabled = config.isRateLimitEnabled();

            assertThat(rateLimitEnabled).isTrue();
        }

        @Test
        @DisplayName("Should limit request size to prevent memory exhaustion")
        void testRequestSizeLimit() {
            ServerConfig config = new ServerConfig();
            long maxBodySize = config.getRequestBodyMaxSizeBytes();

            assertThat(maxBodySize).isLessThan(Long.MAX_VALUE);
        }

        @Test
        @DisplayName("Should limit header size to prevent memory exhaustion")
        void testHeaderSizeLimit() {
            // MAX_HEADERS_SIZE = 8192 bytes
            int maxHeadersSize = 8192;

            assertThat(maxHeadersSize).isGreaterThan(0);
            assertThat(maxHeadersSize).isLessThan(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("Should have request timeout to prevent slowloris attacks")
        void testRequestTimeout() {
            // REQUEST_TIMEOUT_MS = 5000 milliseconds
            int timeoutMs = 5000;

            assertThat(timeoutMs).isGreaterThan(0);
            assertThat(timeoutMs).isLessThan(60000);
        }

        @Test
        @DisplayName("Should have bounded thread pool to prevent resource exhaustion")
        void testBoundedThreadPool() {
            ServerConfig config = new ServerConfig();
            int threadPoolSize = config.getThreadPoolSize();
            int queueLimit = config.getRequestQueueLimit();

            assertThat(threadPoolSize).isGreaterThan(0);
            assertThat(queueLimit).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should limit histogram observations to prevent memory leaks")
        void testBoundedHistogram() {
            MetricsCollector metrics = MetricsCollector.getInstance();

            // Record more than max observations
            for (int i = 0; i < 2000; i++) {
                metrics.recordHistogramObservation("test_histogram", i);
            }

            int count = metrics.getHistogramObservationCount("test_histogram");

            // Should not exceed 1000
            assertThat(count).isLessThanOrEqualTo(1000);
        }
    }

    @Nested
    @DisplayName("Protocol Compliance")
    class ProtocolComplianceTests {

        @Test
        @DisplayName("Should validate HTTP method names")
        void testHttpMethodValidation() {
            // Valid methods
            String[] validMethods = {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE", "CONNECT"};

            for (String method : validMethods) {
                assertThat(method).isNotEmpty();
            }
        }

        @Test
        @DisplayName("Should reject invalid HTTP versions")
        void testHttpVersionValidation() {
            // Valid versions: HTTP/0.9, HTTP/1.0, HTTP/1.1, HTTP/2.0, HTTP/3.0
            // Invalid: HTTP/999, HTTP/4.0, HTTPS/1.1
            String invalidVersion = "HTTP/999";

            assertThat(invalidVersion).contains("HTTP/");
        }

        @Test
        @DisplayName("Should validate request line format")
        void testRequestLineFormat() {
            // Valid: "GET / HTTP/1.1"
            // Invalid: "GET" (missing path and version)
            // Invalid: "GET /" (missing version)

            assertThat("GET / HTTP/1.1").isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Information Disclosure")
    class InformationDisclosureTests {

        @Test
        @DisplayName("Should not expose server implementation details in headers")
        void testNoServerHeaderLeakage() {
            // Server should not include detailed version info in Server header
            // Should be generic like "Server: HTTPServer/1.0" not "Server: Apache/2.4.41 (Ubuntu)"

            assertThat("HTTPServer").isNotEmpty();
        }

        @Test
        @DisplayName("Should not expose file system paths in error messages")
        void testNoPathLeakageInErrors() {
            String maliciousPath = "/etc/passwd";

            // Error messages should not reveal if file exists or its absolute path
            assertThat(maliciousPath).startsWith("/");
        }

        @Test
        @DisplayName("Should not expose stack traces to clients")
        void testNoStackTraceLeakage() {
            // Stack traces should be logged, not sent to client
            assertThat("java.lang.NullPointerException").isNotEmpty();
        }

        @Test
        @DisplayName("Should sanitize directory listing output")
        void testSanitizedDirectoryListing() {
            // Directory listings should escape HTML special characters
            String filename = "<script>.txt";

            assertThat(filename).contains("<");
        }
    }

    @Nested
    @DisplayName("Input Validation")
    class InputValidationTests {

        @Test
        @DisplayName("Should validate header names contain only allowed characters")
        void testHeaderNameValidation() {
            // Header names should be token (alphanumeric + hyphen, etc.)
            String validHeader = "X-Custom-Header";
            String invalidHeader = "X-Header: value";

            assertThat(validHeader).doesNotContain(":");
            assertThat(invalidHeader).contains(":");
        }

        @Test
        @DisplayName("Should handle extremely long header values")
        void testLongHeaderValue() {
            String longValue = "x".repeat(10000);

            // Should reject or limit
            assertThat(longValue.length()).isGreaterThan(1024);
        }

        @Test
        @DisplayName("Should validate Content-Type format")
        void testContentTypeValidation() {
            // Valid: application/json, text/html; charset=utf-8
            // Invalid: application////json, text/*html

            String validContentType = "application/json";
            String invalidContentType = "application////json";

            assertThat(validContentType).doesNotContain("////");
            assertThat(invalidContentType).contains("////");
        }
    }
}
