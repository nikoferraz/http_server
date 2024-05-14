package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Test-Driven Development tests for HTTP/1.1 Keep-Alive support.
 * Tests verify proper keep-alive handling, timeout behavior, and connection pooling.
 */
@DisplayName("HTTP/1.1 Keep-Alive Tests")
class HTTPKeepAliveTest {

    private Servlet servlet;
    private static final int TEST_PORT = 18081;
    private static final String LOCALHOST = "127.0.0.1";
    private static final long WAIT_FOR_START = 1000;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        File webroot = tempDir.toFile();
        ServerConfig config = new ServerConfig();
        config.setKeepAliveEnabled(true);
        config.setKeepAliveTimeout(5000);
        config.setKeepAliveMaxRequests(100);

        servlet = new Servlet(webroot, TEST_PORT, 1, config);
        servlet.start();
        Thread.sleep(WAIT_FOR_START);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (servlet != null) {
            servlet.stopServer();
            servlet.join(5000);
        }
    }

    @Nested
    @DisplayName("Keep-Alive Support")
    class KeepAliveSupportTests {

        @Test
        @DisplayName("Should support keep-alive for HTTP/1.1 requests")
        void testKeepAliveSupport() throws IOException {
            try (Socket socket = new Socket(LOCALHOST, TEST_PORT)) {
                BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                );
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                );

                // Send HTTP/1.1 request with keep-alive
                writer.write("GET / HTTP/1.1\r\n");
                writer.write("Host: localhost\r\n");
                writer.write("Connection: keep-alive\r\n");
                writer.write("\r\n");
                writer.flush();

                // Read response
                String statusLine = reader.readLine();
                assertThat(statusLine).contains("HTTP/1.1");

                // Read headers
                String line;
                String connectionHeader = null;
                String keepAliveHeader = null;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("connection:")) {
                        connectionHeader = line;
                    }
                    if (line.toLowerCase().startsWith("keep-alive:")) {
                        keepAliveHeader = line;
                    }
                }

                assertThat(connectionHeader).isNotNull()
                    .containsIgnoringCase("keep-alive");
                assertThat(keepAliveHeader).isNotNull()
                    .containsIgnoringCase("timeout")
                    .containsIgnoringCase("max");
            }
        }

        @Test
        @DisplayName("Should close connection when client sends Connection: close")
        void testClientCloseRequest() throws IOException {
            try (Socket socket = new Socket(LOCALHOST, TEST_PORT)) {
                BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                );
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                );

                // Send request with Connection: close
                writer.write("GET / HTTP/1.1\r\n");
                writer.write("Host: localhost\r\n");
                writer.write("Connection: close\r\n");
                writer.write("\r\n");
                writer.flush();

                // Read response
                String statusLine = reader.readLine();
                assertThat(statusLine).contains("HTTP/1.1");

                // Read headers and find Connection header
                String line;
                String connectionHeader = null;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("connection:")) {
                        connectionHeader = line;
                    }
                }

                assertThat(connectionHeader).isNotNull()
                    .containsIgnoringCase("close");

                // Verify socket is closed after response
                assertThat(socket.isClosed()).isFalse(); // Socket not explicitly closed by test
                // Try to read more - should reach EOF or get an exception
                String nextLine = reader.readLine();
                // Connection should close, so reading again should return null
                assertThat(nextLine).isNull();
            }
        }

        @Test
        @DisplayName("Should handle HTTP/1.0 without keep-alive by default")
        void testHTTP10NoKeepAlive() throws IOException {
            try (Socket socket = new Socket(LOCALHOST, TEST_PORT)) {
                BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                );
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                );

                // Send HTTP/1.0 request without keep-alive
                writer.write("GET / HTTP/1.0\r\n");
                writer.write("Connection: close\r\n");
                writer.write("\r\n");
                writer.flush();

                // Read response
                String statusLine = reader.readLine();
                assertThat(statusLine).contains("HTTP/1.0");

                // For HTTP/1.0, connection should close unless explicitly requested
                String line;
                String connectionHeader = null;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("connection:")) {
                        connectionHeader = line;
                    }
                }

                // HTTP/1.0 should not have keep-alive unless explicitly requested
                if (connectionHeader != null) {
                    assertThat(connectionHeader).doesNotContainIgnoringCase("keep-alive");
                }
            }
        }
    }

    @Nested
    @DisplayName("Multiple Requests on Same Connection")
    class MultipleRequestsTests {

        @Test
        @DisplayName("Should handle multiple requests on same connection")
        void testMultipleRequests() throws IOException {
            try (Socket socket = new Socket(LOCALHOST, TEST_PORT)) {
                BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                );
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                );

                // Send 5 requests without closing socket
                for (int i = 0; i < 5; i++) {
                    writer.write("GET / HTTP/1.1\r\n");
                    writer.write("Host: localhost\r\n");
                    writer.write("Connection: keep-alive\r\n");
                    writer.write("\r\n");
                    writer.flush();

                    // Read response headers
                    String statusLine = reader.readLine();
                    assertThat(statusLine).as("Request " + i + " status").contains("200");

                    // Skip headers
                    String line;
                    int contentLength = 0;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase().startsWith("content-length:")) {
                            contentLength = Integer.parseInt(line.split(":")[1].trim());
                        }
                    }

                    // Skip body
                    if (contentLength > 0) {
                        char[] buffer = new char[contentLength];
                        reader.read(buffer);
                    }
                }

                // All requests should succeed
                assertThat(socket.isClosed()).isFalse();
            }
        }

        @Test
        @DisplayName("Should measure performance improvement with keep-alive")
        void testPerformanceImprovement() throws IOException {
            int numRequests = 20;

            // Test with keep-alive
            long keepAliveTime = System.currentTimeMillis();
            try (Socket socket = new Socket(LOCALHOST, TEST_PORT)) {
                BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                );
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                );

                for (int i = 0; i < numRequests; i++) {
                    writer.write("GET / HTTP/1.1\r\n");
                    writer.write("Host: localhost\r\n");
                    writer.write("Connection: keep-alive\r\n");
                    writer.write("\r\n");
                    writer.flush();

                    // Read response
                    String statusLine = reader.readLine();
                    assertThat(statusLine).contains("200");

                    // Skip headers and body
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        // Skip
                    }
                }
            }
            keepAliveTime = System.currentTimeMillis() - keepAliveTime;

            // Test without keep-alive (separate connections)
            long noKeepAliveTime = System.currentTimeMillis();
            for (int i = 0; i < numRequests; i++) {
                try (Socket socket = new Socket(LOCALHOST, TEST_PORT)) {
                    BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                    );
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                    );

                    writer.write("GET / HTTP/1.1\r\n");
                    writer.write("Host: localhost\r\n");
                    writer.write("Connection: close\r\n");
                    writer.write("\r\n");
                    writer.flush();

                    // Read response
                    String statusLine = reader.readLine();
                    assertThat(statusLine).contains("200");

                    // Skip headers and body
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        // Skip
                    }
                }
            }
            noKeepAliveTime = System.currentTimeMillis() - noKeepAliveTime;

            // Keep-alive should be faster
            System.out.println("Keep-Alive Time: " + keepAliveTime + "ms");
            System.out.println("No Keep-Alive Time: " + noKeepAliveTime + "ms");
            assertThat(keepAliveTime).isLessThan(noKeepAliveTime);
        }
    }

    @Nested
    @DisplayName("Keep-Alive Limits")
    class KeepAliveLimitsTests {

        @Test
        @DisplayName("Should respect max requests per connection")
        void testMaxRequestsLimit() throws IOException {
            ServerConfig config = new ServerConfig();
            config.setKeepAliveEnabled(true);
            config.setKeepAliveMaxRequests(3); // Set low limit for testing

            servlet.stopServer();
            servlet.join(2000);

            servlet = new Servlet(new File("."), TEST_PORT, 1, config);
            servlet.start();
            Thread.sleep(WAIT_FOR_START);

            try (Socket socket = new Socket(LOCALHOST, TEST_PORT)) {
                BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                );
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                );

                // Send 4 requests (max is 3)
                for (int i = 0; i < 4; i++) {
                    writer.write("GET / HTTP/1.1\r\n");
                    writer.write("Host: localhost\r\n");
                    writer.write("Connection: keep-alive\r\n");
                    writer.write("\r\n");
                    writer.flush();

                    // Read response
                    String statusLine = reader.readLine();
                    assertThat(statusLine).contains("HTTP/");

                    // Read and check Connection header
                    String line;
                    String connectionHeader = null;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase().startsWith("connection:")) {
                            connectionHeader = line;
                        }
                    }

                    // After 3rd request, connection should close
                    if (i == 2) {
                        assertThat(connectionHeader).isNotNull()
                            .containsIgnoringCase("close");
                    }
                }
            }
        }

        @Test
        @DisplayName("Should handle timeout with keep-alive")
        void testKeepAliveTimeout() throws IOException, InterruptedException {
            ServerConfig config = new ServerConfig();
            config.setKeepAliveEnabled(true);
            config.setKeepAliveTimeout(1000); // 1 second timeout

            servlet.stopServer();
            servlet.join(2000);

            servlet = new Servlet(new File("."), TEST_PORT, 1, config);
            servlet.start();
            Thread.sleep(WAIT_FOR_START);

            try (Socket socket = new Socket(LOCALHOST, TEST_PORT)) {
                socket.setSoTimeout(3000);
                BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                );
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                );

                // Send first request
                writer.write("GET / HTTP/1.1\r\n");
                writer.write("Host: localhost\r\n");
                writer.write("Connection: keep-alive\r\n");
                writer.write("\r\n");
                writer.flush();

                // Read response
                String statusLine = reader.readLine();
                assertThat(statusLine).contains("200");

                // Skip headers
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    // Skip
                }

                // Wait for timeout to expire
                Thread.sleep(1500);

                // Send second request - should timeout
                writer.write("GET / HTTP/1.1\r\n");
                writer.write("Host: localhost\r\n");
                writer.write("Connection: keep-alive\r\n");
                writer.write("\r\n");
                writer.flush();

                // This should either get EOF or an error since connection should be closed
                String nextStatus = reader.readLine();
                // Connection should be closed due to timeout
                assertThat(nextStatus).isNull();
            }
        }
    }

    @Nested
    @DisplayName("Keep-Alive Header Format")
    class KeepAliveHeaderFormatTests {

        @Test
        @DisplayName("Should include proper Keep-Alive header with timeout and max")
        void testKeepAliveHeaderFormat() throws IOException {
            try (Socket socket = new Socket(LOCALHOST, TEST_PORT)) {
                BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                );
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                );

                writer.write("GET / HTTP/1.1\r\n");
                writer.write("Host: localhost\r\n");
                writer.write("Connection: keep-alive\r\n");
                writer.write("\r\n");
                writer.flush();

                // Read response
                String statusLine = reader.readLine();
                assertThat(statusLine).contains("200");

                // Find Keep-Alive header
                String line;
                String keepAliveHeader = null;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("keep-alive:")) {
                        keepAliveHeader = line;
                        break;
                    }
                }

                assertThat(keepAliveHeader).isNotNull()
                    .matchesPattern("(?i)Keep-Alive:.*timeout=\\d+.*max=\\d+");
            }
        }
    }

    @Nested
    @DisplayName("Default Keep-Alive Behavior")
    class DefaultKeepAliveBehaviorTests {

        @Test
        @DisplayName("Should use keep-alive by default for HTTP/1.1 without explicit Connection header")
        void testDefaultKeepAliveHTTP11() throws IOException {
            try (Socket socket = new Socket(LOCALHOST, TEST_PORT)) {
                BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                );
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                );

                // Send request without explicit Connection header
                writer.write("GET / HTTP/1.1\r\n");
                writer.write("Host: localhost\r\n");
                writer.write("\r\n");
                writer.flush();

                // Read response
                String statusLine = reader.readLine();
                assertThat(statusLine).contains("200");

                // Check Connection header - should default to keep-alive
                String line;
                String connectionHeader = null;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("connection:")) {
                        connectionHeader = line;
                        break;
                    }
                }

                // For HTTP/1.1, keep-alive is implicit, so header may not be present
                // or should indicate keep-alive
                if (connectionHeader != null) {
                    assertThat(connectionHeader).doesNotContainIgnoringCase("close");
                }
            }
        }
    }
}
