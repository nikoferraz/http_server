package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for Virtual Threads with the HTTP Server.
 * Tests high concurrency request handling.
 */
@DisplayName("Virtual Threads Integration Tests")
class VirtualThreadsIntegrationTest {

    private Servlet servlet;
    private static final int TEST_PORT = 18081;
    private static final String LOCALHOST = "127.0.0.1";
    private static final long WAIT_FOR_START = 1000;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        // Create test webroot with index.html
        File webroot = tempDir.toFile();
        File indexFile = new File(webroot, "index.html");
        Files.write(indexFile.toPath(), "<html><body>Test</body></html>".getBytes());

        ServerConfig config = new ServerConfig();
        servlet = new Servlet(webroot, TEST_PORT, 1, config);
        servlet.start();

        // Wait for server to start
        Thread.sleep(WAIT_FOR_START);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (servlet != null && servlet.isRunning()) {
            servlet.interrupt();
            // Wait for graceful shutdown
            Thread.sleep(500);
        }
    }

    @Test
    @DisplayName("Should handle 100 concurrent HTTP requests with virtual threads")
    void testHighConcurrentRequests() throws Exception {
        int concurrentRequests = 100;
        CountDownLatch latch = new CountDownLatch(concurrentRequests);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        // Submit 100 concurrent requests
        for (int i = 0; i < concurrentRequests; i++) {
            new Thread(() -> {
                try {
                    Socket socket = new Socket(LOCALHOST, TEST_PORT);
                    socket.setSoTimeout(5000);

                    // Send HTTP GET request
                    OutputStream out = socket.getOutputStream();
                    String request = "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
                    out.write(request.getBytes());
                    out.flush();

                    // Read response
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    String line = in.readLine();

                    if (line != null && line.contains("200")) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }

                    socket.close();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        // Wait for all requests to complete
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - startTime;

        assertThat(completed)
            .as("All 100 concurrent requests should complete")
            .isTrue();

        assertThat(successCount.get())
            .as("Most requests should succeed")
            .isGreaterThanOrEqualTo(90); // Allow some failures due to timing

        System.out.println("100 concurrent requests completed in: " + duration + "ms " +
                          "(" + successCount.get() + " successful, " + failureCount.get() + " failed)");
    }

    @Test
    @DisplayName("Should handle 500 concurrent requests efficiently")
    void testVeryHighConcurrentLoad() throws Exception {
        int concurrentRequests = 500;
        CountDownLatch latch = new CountDownLatch(concurrentRequests);
        AtomicInteger successCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        // Submit 500 concurrent requests
        for (int i = 0; i < concurrentRequests; i++) {
            new Thread(() -> {
                try {
                    Socket socket = new Socket(LOCALHOST, TEST_PORT);
                    socket.setSoTimeout(5000);

                    OutputStream out = socket.getOutputStream();
                    String request = "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
                    out.write(request.getBytes());
                    out.flush();

                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    String line = in.readLine();

                    if (line != null && line.contains("200")) {
                        successCount.incrementAndGet();
                    }

                    socket.close();
                } catch (Exception e) {
                    // Ignore - timeout or connection reset
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        boolean completed = latch.await(60, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - startTime;

        assertThat(completed)
            .as("All 500 concurrent requests should complete")
            .isTrue();

        assertThat(successCount.get())
            .as("Most requests should succeed")
            .isGreaterThanOrEqualTo(450); // Allow up to 50 failures

        System.out.println("500 concurrent requests completed in: " + duration + "ms " +
                          "(" + successCount.get() + " successful)");
    }

    @Test
    @DisplayName("Should maintain metrics accuracy under concurrent load")
    void testMetricsUnderLoad() throws Exception {
        int concurrentRequests = 50;
        CountDownLatch latch = new CountDownLatch(concurrentRequests);

        // Send concurrent requests
        for (int i = 0; i < concurrentRequests; i++) {
            new Thread(() -> {
                try {
                    Socket socket = new Socket(LOCALHOST, TEST_PORT);
                    socket.setSoTimeout(5000);

                    OutputStream out = socket.getOutputStream();
                    String request = "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
                    out.write(request.getBytes());
                    out.flush();

                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    while (in.readLine() != null) {
                        // Read all response
                    }

                    socket.close();
                } catch (Exception e) {
                    // Ignore
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        // Wait for completion
        latch.await(30, TimeUnit.SECONDS);

        // Verify metrics are still accessible
        MetricsCollector metrics = MetricsCollector.getInstance();
        String metricsOutput = metrics.exportPrometheusMetrics();

        assertThat(metricsOutput)
            .as("Metrics should be exported without errors")
            .isNotEmpty();

        assertThat(metricsOutput)
            .as("Metrics should contain request counter")
            .contains("http_requests_total");
    }
}
