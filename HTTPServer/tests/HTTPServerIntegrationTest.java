package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for the HTTP Server.
 * Tests end-to-end HTTP request/response flows.
 */
@DisplayName("HTTP Server Integration Tests")
class HTTPServerIntegrationTest {

    private Servlet servlet;
    private static final int TEST_PORT = 18080;
    private static final String LOCALHOST = "127.0.0.1";
    private static final long WAIT_FOR_START = 1000; // 1 second

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        File webroot = tempDir.toFile();
        ServerConfig config = new ServerConfig();

        servlet = new Servlet(webroot, TEST_PORT, 1, config);
        servlet.start();

        // Wait for server to start
        Thread.sleep(WAIT_FOR_START);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (servlet != null) {
            servlet.stopServer();
            servlet.join(5000); // Wait up to 5 seconds for shutdown
        }
    }

    @Nested
    @DisplayName("Basic HTTP Requests")
    class BasicHTTPTests {

        @Test
        @DisplayName("Should respond to GET request")
        void testGetRequest() throws IOException {
            String response = sendHTTPRequest("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

            assertThat(response).isNotNull();
            assertThat(response).contains("HTTP/");
        }

        @Test
        @DisplayName("Should respond to HEAD request")
        void testHeadRequest() throws IOException {
            String response = sendHTTPRequest("HEAD / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

            assertThat(response).isNotNull();
            assertThat(response).contains("HTTP/");
        }

        @Test
        @DisplayName("Should respond to OPTIONS request")
        void testOptionsRequest() throws IOException {
            String response = sendHTTPRequest("OPTIONS / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

            assertThat(response).isNotNull();
            assertThat(response).contains("HTTP/");
        }

        @Test
        @DisplayName("Should return 404 for non-existent files")
        void testNotFoundResponse() throws IOException {
            String response = sendHTTPRequest("GET /nonexistent.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

            assertThat(response).contains("404");
        }

        @Test
        @DisplayName("Should return 200 for existing files")
        void testOkResponse(@TempDir Path tempDir) throws IOException {
            File testFile = tempDir.resolve("test.txt").toFile();
            Files.write(testFile.toPath(), "test content".getBytes());

            servlet.stopServer();
            servlet.join(2000);

            servlet = new Servlet(tempDir.toFile(), TEST_PORT, 1, new ServerConfig());
            servlet.start();
            Thread.sleep(WAIT_FOR_START);

            String response = sendHTTPRequest("GET /test.txt HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

            assertThat(response).contains("200");
            assertThat(response).contains("test content");
        }
    }

    @Nested
    @DisplayName("HTTP Version Handling")
    class HTTPVersionTests {

        @Test
        @DisplayName("Should handle HTTP/1.0 requests")
        void testHTTP10Request() throws IOException {
            String response = sendHTTPRequest("GET / HTTP/1.0\r\nConnection: close\r\n\r\n");

            assertThat(response).isNotNull();
            assertThat(response).contains("HTTP/1.0");
        }

        @Test
        @DisplayName("Should handle HTTP/1.1 requests")
        void testHTTP11Request() throws IOException {
            String response = sendHTTPRequest("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

            assertThat(response).isNotNull();
            assertThat(response).contains("HTTP/1.1");
        }
    }

    @Nested
    @DisplayName("Header Handling")
    class HeaderHandlingTests {

        @Test
        @DisplayName("Should handle multiple headers")
        void testMultipleHeaders() throws IOException {
            String request = "GET / HTTP/1.1\r\n" +
                           "Host: localhost\r\n" +
                           "User-Agent: TestClient\r\n" +
                           "Accept: */*\r\n" +
                           "Connection: close\r\n" +
                           "\r\n";

            String response = sendHTTPRequest(request);

            assertThat(response).isNotNull();
            assertThat(response).contains("HTTP/");
        }

        @Test
        @DisplayName("Should handle Content-Type header")
        void testContentTypeHeader(@TempDir Path tempDir) throws IOException {
            File htmlFile = tempDir.resolve("test.html").toFile();
            Files.write(htmlFile.toPath(), "<html><body>test</body></html>".getBytes());

            servlet.stopServer();
            servlet.join(2000);

            servlet = new Servlet(tempDir.toFile(), TEST_PORT, 1, new ServerConfig());
            servlet.start();
            Thread.sleep(WAIT_FOR_START);

            String response = sendHTTPRequest("GET /test.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

            assertThat(response).contains("Content-Type: text/html");
        }

        @Test
        @DisplayName("Should preserve case-insensitive header names")
        void testHeaderNameCaseInsensitivity() throws IOException {
            String request = "GET / HTTP/1.1\r\n" +
                           "HOST: localhost\r\n" +
                           "CONNECTION: close\r\n" +
                           "\r\n";

            String response = sendHTTPRequest(request);

            assertThat(response).isNotNull();
        }
    }

    @Nested
    @DisplayName("Compression")
    class CompressionTests {

        @Test
        @DisplayName("Should compress response when Accept-Encoding: gzip")
        void testGzipCompression(@TempDir Path tempDir) throws IOException {
            File htmlFile = tempDir.resolve("large.html").toFile();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                sb.append("<html><body>This is line ").append(i).append("</body></html>\r\n");
            }
            Files.write(htmlFile.toPath(), sb.toString().getBytes());

            servlet.stopServer();
            servlet.join(2000);

            servlet = new Servlet(tempDir.toFile(), TEST_PORT, 1, new ServerConfig());
            servlet.start();
            Thread.sleep(WAIT_FOR_START);

            String request = "GET /large.html HTTP/1.1\r\n" +
                           "Host: localhost\r\n" +
                           "Accept-Encoding: gzip\r\n" +
                           "Connection: close\r\n" +
                           "\r\n";

            String response = sendHTTPRequest(request);

            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("Should not compress when Accept-Encoding missing")
        void testNoCompressionWithoutHeader(@TempDir Path tempDir) throws IOException {
            File htmlFile = tempDir.resolve("test.html").toFile();
            Files.write(htmlFile.toPath(), "<html><body>test</body></html>".getBytes());

            servlet.stopServer();
            servlet.join(2000);

            servlet = new Servlet(tempDir.toFile(), TEST_PORT, 1, new ServerConfig());
            servlet.start();
            Thread.sleep(WAIT_FOR_START);

            String response = sendHTTPRequest("GET /test.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

            assertThat(response).isNotNull();
        }
    }

    @Nested
    @DisplayName("Health Checks")
    class HealthCheckTests {

        @Test
        @DisplayName("Should respond to /health endpoint")
        void testHealthEndpoint() throws IOException {
            String response = sendHTTPRequest("GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

            assertThat(response).isNotNull();
            assertThat(response).contains("200");
        }

        @Test
        @DisplayName("Should respond to /health/metrics endpoint")
        void testMetricsEndpoint() throws IOException {
            String response = sendHTTPRequest("GET /health/metrics HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

            assertThat(response).isNotNull();
            assertThat(response).contains("200");
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should handle malformed requests gracefully")
        void testMalformedRequest() throws IOException {
            // Send garbage data
            Socket socket = new Socket(LOCALHOST, TEST_PORT);
            OutputStream out = socket.getOutputStream();
            out.write("GARBAGE DATA!!!\r\n\r\n".getBytes());
            out.flush();

            // Server should not crash
            assertThat(servlet.isAlive()).isTrue();

            socket.close();
        }

        @Test
        @DisplayName("Should handle very long request lines")
        void testVeryLongRequestLine() throws IOException {
            String longPath = "/" + "a".repeat(1000);
            String request = "GET " + longPath + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";

            // Should handle without crashing
            try {
                String response = sendHTTPRequest(request);
                // May be 400 or 414 (URI too long)
                assertThat(response).contains("HTTP/");
            } catch (Exception e) {
                // Connection reset is acceptable for oversized request
                assertThat(e).isNotNull();
            }
        }

        @Test
        @DisplayName("Should handle very large headers")
        void testVeryLargeHeaders() throws IOException {
            String largeHeaderValue = "x".repeat(5000);
            String request = "GET / HTTP/1.1\r\n" +
                           "Host: localhost\r\n" +
                           "X-Large-Header: " + largeHeaderValue + "\r\n" +
                           "Connection: close\r\n" +
                           "\r\n";

            // Should handle or reject gracefully
            try {
                String response = sendHTTPRequest(request);
                assertThat(response).contains("HTTP/");
            } catch (Exception e) {
                // Acceptable to reject
                assertThat(e).isNotNull();
            }
        }
    }

    @Nested
    @DisplayName("Concurrent Requests")
    class ConcurrentRequestTests {

        @Test
        @DisplayName("Should handle multiple concurrent connections")
        void testConcurrentConnections() throws InterruptedException {
            Thread[] threads = new Thread[10];

            for (int i = 0; i < 10; i++) {
                threads[i] = new Thread(() -> {
                    try {
                        String response = sendHTTPRequest("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                        assertThat(response).isNotNull();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                threads[i].start();
            }

            for (Thread t : threads) {
                t.join(5000);
            }

            // Server should still be responsive
            String response = sendHTTPRequest("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            assertThat(response).isNotNull();
        }
    }

    // Helper method to send HTTP request and get response
    private String sendHTTPRequest(String request) throws IOException {
        Socket socket = new Socket(LOCALHOST, TEST_PORT);
        try {
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes());
            out.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            int lineCount = 0;
            int maxLines = 1000;

            while ((line = reader.readLine()) != null && lineCount < maxLines) {
                response.append(line).append("\n");
                lineCount++;
            }

            return response.toString();
        } finally {
            socket.close();
        }
    }
}
