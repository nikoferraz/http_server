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
 * Test suite for RequestBodyParser.
 * Tests parsing of JSON, form, multipart, and text body content.
 */
@DisplayName("RequestBodyParser Tests")
class RequestBodyParserTest {

    private RequestBodyParser parser;

    @BeforeEach
    void setUp() {
        parser = new RequestBodyParser();
    }

    @Nested
    @DisplayName("JSON Body Parsing")
    class JsonBodyTests {

        @Test
        @DisplayName("Should parse JSON request body")
        void testParseJsonBody() throws IOException {
            String jsonContent = "{\"name\":\"John\",\"age\":30}";
            InputStream input = new ByteArrayInputStream(jsonContent.getBytes());

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "application/json");
            headers.put("content-length", String.valueOf(jsonContent.length()));

            RequestBodyParser.ParsedBody body = parser.parseBody(input, headers);

            assertThat(body).isNotNull();
            assertThat(body.getContentType()).isEqualTo("application/json");
        }

        @Test
        @DisplayName("Should handle valid JSON structure")
        void testValidJsonStructure() throws IOException {
            String jsonContent = "[{\"id\":1},{\"id\":2}]";
            InputStream input = new ByteArrayInputStream(jsonContent.getBytes());

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "application/json");
            headers.put("content-length", String.valueOf(jsonContent.length()));

            RequestBodyParser.ParsedBody body = parser.parseBody(input, headers);

            assertThat(body).isNotNull();
        }

        @Test
        @DisplayName("Should handle empty JSON object")
        void testEmptyJsonObject() throws IOException {
            String jsonContent = "{}";
            InputStream input = new ByteArrayInputStream(jsonContent.getBytes());

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "application/json");
            headers.put("content-length", String.valueOf(jsonContent.length()));

            RequestBodyParser.ParsedBody body = parser.parseBody(input, headers);

            assertThat(body).isNotNull();
        }

        @Test
        @DisplayName("Should handle empty JSON array")
        void testEmptyJsonArray() throws IOException {
            String jsonContent = "[]";
            InputStream input = new ByteArrayInputStream(jsonContent.getBytes());

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "application/json");
            headers.put("content-length", String.valueOf(jsonContent.length()));

            RequestBodyParser.ParsedBody body = parser.parseBody(input, headers);

            assertThat(body).isNotNull();
        }
    }

    @Nested
    @DisplayName("Form Data Parsing")
    class FormDataTests {

        @Test
        @DisplayName("Should parse URL-encoded form data")
        void testParseFormData() throws IOException {
            String formContent = "username=john&password=secret&email=john@example.com";
            InputStream input = new ByteArrayInputStream(formContent.getBytes());

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "application/x-www-form-urlencoded");
            headers.put("content-length", String.valueOf(formContent.length()));

            RequestBodyParser.ParsedBody body = parser.parseBody(input, headers);

            assertThat(body).isNotNull();
        }

        @Test
        @DisplayName("Should handle form data with special characters")
        void testFormDataSpecialChars() throws IOException {
            String formContent = "message=hello%20world&email=test%2Btest%40example.com";
            InputStream input = new ByteArrayInputStream(formContent.getBytes());

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "application/x-www-form-urlencoded");
            headers.put("content-length", String.valueOf(formContent.length()));

            RequestBodyParser.ParsedBody body = parser.parseBody(input, headers);

            assertThat(body).isNotNull();
        }

        @Test
        @DisplayName("Should handle empty form data")
        void testEmptyFormData() throws IOException {
            String formContent = "";
            InputStream input = new ByteArrayInputStream(formContent.getBytes());

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "application/x-www-form-urlencoded");
            headers.put("content-length", "0");

            RequestBodyParser.ParsedBody body = parser.parseBody(input, headers);

            assertThat(body).isNotNull();
        }
    }

    @Nested
    @DisplayName("Text Body Parsing")
    class TextBodyTests {

        @Test
        @DisplayName("Should parse plain text body")
        void testParsePlainText() throws IOException {
            String textContent = "This is plain text content";
            InputStream input = new ByteArrayInputStream(textContent.getBytes());

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "text/plain");
            headers.put("content-length", String.valueOf(textContent.length()));

            RequestBodyParser.ParsedBody body = parser.parseBody(input, headers);

            assertThat(body).isNotNull();
        }

        @Test
        @DisplayName("Should handle multiline text")
        void testMultilineText() throws IOException {
            String textContent = "Line 1\nLine 2\nLine 3";
            InputStream input = new ByteArrayInputStream(textContent.getBytes());

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "text/plain");
            headers.put("content-length", String.valueOf(textContent.length()));

            RequestBodyParser.ParsedBody body = parser.parseBody(input, headers);

            assertThat(body).isNotNull();
        }
    }

    @Nested
    @DisplayName("Content-Length Validation")
    class ContentLengthValidationTests {

        @Test
        @DisplayName("Should reject request exceeding max body size")
        void testExceedsMaxSize() throws IOException {
            RequestBodyParser smallParser = new RequestBodyParser(100); // 100 byte limit

            String largeContent = "x".repeat(200);
            InputStream input = new ByteArrayInputStream(largeContent.getBytes());

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "text/plain");
            headers.put("content-length", String.valueOf(largeContent.length()));

            assertThatThrownBy(() -> smallParser.parseBody(input, headers))
                    .isInstanceOf(RequestBodyParser.PayloadTooLargeException.class);
        }

        @Test
        @DisplayName("Should accept request within max body size")
        void testWithinMaxSize() throws IOException {
            RequestBodyParser largeParser = new RequestBodyParser(1000); // 1000 byte limit

            String content = "x".repeat(500);
            InputStream input = new ByteArrayInputStream(content.getBytes());

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "text/plain");
            headers.put("content-length", String.valueOf(content.length()));

            RequestBodyParser.ParsedBody body = largeParser.parseBody(input, headers);

            assertThat(body).isNotNull();
        }

        @Test
        @DisplayName("Should reject negative Content-Length")
        void testNegativeContentLength() throws IOException {
            InputStream input = new ByteArrayInputStream(new byte[0]);

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "text/plain");
            headers.put("content-length", "-100");

            assertThatThrownBy(() -> parser.parseBody(input, headers))
                    .isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("Should reject missing Content-Length")
        void testMissingContentLength() throws IOException {
            InputStream input = new ByteArrayInputStream("test".getBytes());

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "text/plain");

            assertThatThrownBy(() -> parser.parseBody(input, headers))
                    .isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("Should reject invalid Content-Length format")
        void testInvalidContentLength() throws IOException {
            InputStream input = new ByteArrayInputStream("test".getBytes());

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "text/plain");
            headers.put("content-length", "not-a-number");

            assertThatThrownBy(() -> parser.parseBody(input, headers))
                    .isInstanceOf(IOException.class);
        }
    }

    @Nested
    @DisplayName("Stream Handling")
    class StreamHandlingTests {

        @Test
        @DisplayName("Should handle complete stream read")
        void testCompleteStreamRead() throws IOException {
            String content = "test content";
            InputStream input = new ByteArrayInputStream(content.getBytes());

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "text/plain");
            headers.put("content-length", String.valueOf(content.length()));

            RequestBodyParser.ParsedBody body = parser.parseBody(input, headers);

            assertThat(body).isNotNull();
        }

        @Test
        @DisplayName("Should handle large streams")
        void testLargeStream() throws IOException {
            String content = "x".repeat(10000);
            InputStream input = new ByteArrayInputStream(content.getBytes());

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "text/plain");
            headers.put("content-length", String.valueOf(content.length()));

            RequestBodyParser.ParsedBody body = parser.parseBody(input, headers);

            assertThat(body).isNotNull();
        }

        @Test
        @DisplayName("Should handle empty stream")
        void testEmptyStream() throws IOException {
            InputStream input = new ByteArrayInputStream(new byte[0]);

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "text/plain");
            headers.put("content-length", "0");

            RequestBodyParser.ParsedBody body = parser.parseBody(input, headers);

            assertThat(body).isNotNull();
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle unknown content type")
        void testUnknownContentType() throws IOException {
            String content = "some data";
            InputStream input = new ByteArrayInputStream(content.getBytes());

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "application/custom-type");
            headers.put("content-length", String.valueOf(content.length()));

            RequestBodyParser.ParsedBody body = parser.parseBody(input, headers);

            assertThat(body).isNotNull();
        }

        @Test
        @DisplayName("Should handle content type with charset")
        void testContentTypeWithCharset() throws IOException {
            String content = "test content";
            InputStream input = new ByteArrayInputStream(content.getBytes());

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "application/json; charset=utf-8");
            headers.put("content-length", String.valueOf(content.length()));

            RequestBodyParser.ParsedBody body = parser.parseBody(input, headers);

            assertThat(body).isNotNull();
        }

        @Test
        @DisplayName("Should handle UTF-8 content")
        void testUtf8Content() throws IOException {
            String content = "Hello 世界 🌍";
            InputStream input = new ByteArrayInputStream(content.getBytes());

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "text/plain; charset=utf-8");
            headers.put("content-length", String.valueOf(content.getBytes().length));

            RequestBodyParser.ParsedBody body = parser.parseBody(input, headers);

            assertThat(body).isNotNull();
        }

        @Test
        @DisplayName("Should handle binary data")
        void testBinaryData() throws IOException {
            byte[] binaryData = new byte[256];
            for (int i = 0; i < 256; i++) {
                binaryData[i] = (byte) i;
            }
            InputStream input = new ByteArrayInputStream(binaryData);

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "application/octet-stream");
            headers.put("content-length", String.valueOf(binaryData.length));

            RequestBodyParser.ParsedBody body = parser.parseBody(input, headers);

            assertThat(body).isNotNull();
        }
    }
}
