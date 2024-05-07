package HTTPServer;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Benchmark for HTTP/1.1 Keep-Alive feature.
 * Compares performance with and without keep-alive enabled.
 */
public class KeepAliveBenchmark {

    private static final String LOCALHOST = "127.0.0.1";
    private static final int PORT = 18082;
    private static final int NUM_REQUESTS = 50;

    public static void main(String[] args) throws Exception {
        System.out.println("HTTP/1.1 Keep-Alive Benchmark");
        System.out.println("=" .repeat(50));

        // Start server without keep-alive
        System.out.println("\nTest 1: Multiple requests WITHOUT keep-alive");
        System.out.println("-" .repeat(50));
        benchmarkWithoutKeepAlive();

        System.out.println("\nTest 2: Multiple requests WITH keep-alive on same connection");
        System.out.println("-" .repeat(50));
        benchmarkWithKeepAlive();

        System.out.println("\n" + "=" .repeat(50));
        System.out.println("Benchmark complete");
    }

    private static void benchmarkWithoutKeepAlive() throws Exception {
        long startTime = System.currentTimeMillis();
        int successCount = 0;

        for (int i = 0; i < NUM_REQUESTS; i++) {
            try (Socket socket = new Socket(LOCALHOST, PORT)) {
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
                if (statusLine != null && statusLine.contains("200")) {
                    successCount++;
                }

                // Skip headers
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    // Skip headers
                }
            } catch (Exception e) {
                System.err.println("Request " + i + " failed: " + e.getMessage());
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        System.out.println("Requests: " + NUM_REQUESTS);
        System.out.println("Successful: " + successCount);
        System.out.println("Total time: " + duration + " ms");
        System.out.println("Avg per request: " + (duration / (double) NUM_REQUESTS) + " ms");
    }

    private static void benchmarkWithKeepAlive() throws Exception {
        long startTime = System.currentTimeMillis();
        int successCount = 0;

        try (Socket socket = new Socket(LOCALHOST, PORT)) {
            BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
            );
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
            );

            for (int i = 0; i < NUM_REQUESTS; i++) {
                // Send request with keep-alive
                writer.write("GET / HTTP/1.1\r\n");
                writer.write("Host: localhost\r\n");
                writer.write("Connection: keep-alive\r\n");
                writer.write("\r\n");
                writer.flush();

                // Read response
                String statusLine = reader.readLine();
                if (statusLine != null && statusLine.contains("200")) {
                    successCount++;
                }

                // Skip headers
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    // Skip headers
                }
            }
        } catch (Exception e) {
            System.err.println("Benchmark failed: " + e.getMessage());
            e.printStackTrace();
        }

        long duration = System.currentTimeMillis() - startTime;
        System.out.println("Requests: " + NUM_REQUESTS);
        System.out.println("Successful: " + successCount);
        System.out.println("Total time: " + duration + " ms");
        System.out.println("Avg per request: " + (duration / (double) NUM_REQUESTS) + " ms");
    }
}
