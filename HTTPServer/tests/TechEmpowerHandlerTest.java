package HTTPServer.tests;

import HTTPServer.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.*;

public class TechEmpowerHandlerTest {

    @BeforeAll
    static void setupDatabase() {
        String dbUrl = System.getenv("DB_URL");
        String dbUser = System.getenv("DB_USER");
        String dbPassword = System.getenv("DB_PASSWORD");

        if (dbUrl == null) {
            dbUrl = "jdbc:postgresql://localhost:5432/benchmarkdb";
        }
        if (dbUser == null) {
            dbUser = "benchmarkdbuser";
        }
        if (dbPassword == null) {
            dbPassword = "benchmarkdbpass";
        }

        try {
            DatabaseConnectionPool.initialize(dbUrl, dbUser, dbPassword, 10);
        } catch (Exception e) {
            System.err.println("Warning: Could not initialize database connection pool: " + e.getMessage());
        }
    }

    @Test
    void testPlaintext() throws IOException {
        StringWriter writer = new StringWriter();
        TechEmpowerHandler.handlePlaintext(writer, "HTTP/1.1", true);
        String response = writer.toString();

        assertThat(response)
                .contains("200 OK")
                .contains("Content-Type: text/plain")
                .contains("Hello, World!");
    }

    @Test
    void testPlaintextResponseFormat() throws IOException {
        StringWriter writer = new StringWriter();
        TechEmpowerHandler.handlePlaintext(writer, "HTTP/1.1", false);
        String response = writer.toString();

        String[] parts = response.split("\r\n\r\n");
        assertThat(parts).hasSize(2);
        assertThat(parts[1]).isEqualTo("Hello, World!");
        assertThat(parts[0]).contains("Content-Length: 13");
    }

    @Test
    void testJson() throws IOException {
        StringWriter writer = new StringWriter();
        TechEmpowerHandler.handleJson(writer, "HTTP/1.1", true);
        String response = writer.toString();

        assertThat(response)
                .contains("200 OK")
                .contains("Content-Type: application/json")
                .contains("{\"message\":\"Hello, World!\"}");
    }

    @Test
    void testJsonResponseFormat() throws IOException {
        StringWriter writer = new StringWriter();
        TechEmpowerHandler.handleJson(writer, "HTTP/1.1", false);
        String response = writer.toString();

        String[] parts = response.split("\r\n\r\n");
        assertThat(parts).hasSize(2);
        assertThat(parts[1]).isEqualTo("{\"message\":\"Hello, World!\"}");
        assertThat(parts[0]).contains("Content-Length: 27");
    }

    @Test
    void testExtractQueryCountDefault() {
        int count = TechEmpowerHandler.extractQueryCount("");
        assertThat(count).isEqualTo(1);
    }

    @Test
    void testExtractQueryCountWithParameter() {
        int count = TechEmpowerHandler.extractQueryCount("queries=5");
        assertThat(count).isEqualTo(5);
    }

    @Test
    void testExtractQueryCountMultipleParameters() {
        int count = TechEmpowerHandler.extractQueryCount("foo=bar&queries=10&baz=qux");
        assertThat(count).isEqualTo(10);
    }

    @Test
    void testExtractQueryCountClamping() {
        int countTooLow = TechEmpowerHandler.extractQueryCount("queries=0");
        assertThat(countTooLow).isEqualTo(1);

        int countTooHigh = TechEmpowerHandler.extractQueryCount("queries=600");
        assertThat(countTooHigh).isEqualTo(500);
    }

    @Test
    void testExtractQueryCountInvalid() {
        int count = TechEmpowerHandler.extractQueryCount("queries=abc");
        assertThat(count).isEqualTo(1);
    }

    @Test
    void testJsonEscaping() {
        String escaped = JsonUtil.escapeJson("Hello \"World\"");
        assertThat(escaped).isEqualTo("Hello \\\"World\\\"");

        String escaped2 = JsonUtil.escapeJson("Line1\nLine2");
        assertThat(escaped2).isEqualTo("Line1\\nLine2");

        String escaped3 = JsonUtil.escapeJson("Path\\To\\File");
        assertThat(escaped3).isEqualTo("Path\\\\To\\\\File");
    }

    @Test
    void testHtmlEscaping() {
        String escaped = JsonUtil.escapeHtmlAttribute("<script>alert('xss')</script>");
        assertThat(escaped)
                .contains("&lt;")
                .contains("&gt;")
                .contains("&#39;");

        String escaped2 = JsonUtil.escapeHtmlAttribute("A & B");
        assertThat(escaped2).isEqualTo("A &amp; B");
    }

    @Test
    void testWorldToJson() {
        String json = JsonUtil.worldToJson(1, 5623);
        assertThat(json).isEqualTo("{\"id\":1,\"randomNumber\":5623}");
    }

    @Test
    void testWorldsToJson() {
        int[][] worlds = new int[][]{{1, 100}, {2, 200}};
        String json = JsonUtil.worldsToJson(worlds);
        assertThat(json).isEqualTo("[{\"id\":1,\"randomNumber\":100},{\"id\":2,\"randomNumber\":200}]");
    }

    @Test
    void testKeepAliveHandling() throws IOException {
        StringWriter writerWithKeepalive = new StringWriter();
        TechEmpowerHandler.handlePlaintext(writerWithKeepalive, "HTTP/1.1", true);
        assertThat(writerWithKeepalive.toString()).contains("Connection: keep-alive");

        StringWriter writerWithoutKeepalive = new StringWriter();
        TechEmpowerHandler.handlePlaintext(writerWithoutKeepalive, "HTTP/1.1", false);
        assertThat(writerWithoutKeepalive.toString()).contains("Connection: close");
    }

    @Test
    void testHttpVersionInResponse() throws IOException {
        StringWriter writer10 = new StringWriter();
        TechEmpowerHandler.handleJson(writer10, "HTTP/1.0", true);
        assertThat(writer10.toString()).startsWith("HTTP/1.0 200");

        StringWriter writer11 = new StringWriter();
        TechEmpowerHandler.handleJson(writer11, "HTTP/1.1", true);
        assertThat(writer11.toString()).startsWith("HTTP/1.1 200");
    }

    @Test
    void testContentLengthCalculation() throws IOException {
        StringWriter writer = new StringWriter();
        TechEmpowerHandler.handleJson(writer, "HTTP/1.1", true);
        String response = writer.toString();

        String[] parts = response.split("\r\n");
        String contentLengthHeader = null;
        for (String part : parts) {
            if (part.startsWith("Content-Length:")) {
                contentLengthHeader = part;
                break;
            }
        }

        assertThat(contentLengthHeader).isNotNull();
        int declaredLength = Integer.parseInt(contentLengthHeader.split(":")[1].trim());
        assertThat(declaredLength).isEqualTo(27);
    }

    @Test
    void testServerHeader() throws IOException {
        StringWriter writer = new StringWriter();
        TechEmpowerHandler.handlePlaintext(writer, "HTTP/1.1", true);
        String response = writer.toString();

        assertThat(response).contains("Server: TechEmpower");
    }
}
