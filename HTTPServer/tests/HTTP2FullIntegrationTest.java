package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;

public class HTTP2FullIntegrationTest {

    private HTTP2FrameParser frameParser;
    private HPACKEncoder encoder;
    private HPACKDecoder decoder;
    private Map<Integer, HTTP2Stream> streams;

    @BeforeEach
    public void setUp() {
        frameParser = new HTTP2FrameParser();
        encoder = new HPACKEncoder();
        decoder = new HPACKDecoder();
        streams = new ConcurrentHashMap<>();
    }

    @Nested
    class FullRequestResponseCycle {

        @Test
        public void testCompleteGetRequest() {
            // 1. Create stream
            HTTP2Stream stream = new HTTP2Stream(1, 65535);
            stream.open();

            // 2. Send request headers
            Map<String, String> requestHeaders = new HashMap<>();
            requestHeaders.put(":method", "GET");
            requestHeaders.put(":path", "/api/users");
            requestHeaders.put(":scheme", "https");
            requestHeaders.put(":authority", "api.example.com");
            requestHeaders.put("user-agent", "Test/1.0");

            byte[] encodedHeaders = encoder.encode(requestHeaders);
            HTTP2Frame headersFrame = frameParser.createHeadersFrame(1, encodedHeaders, true, true);

            assertThat(headersFrame).isNotNull();
            assertThat(headersFrame.isEndStream()).isTrue();
            assertThat(headersFrame.isEndHeaders()).isTrue();

            stream.setRequestHeaders(requestHeaders);
            stream.setEndStreamReceived(true);

            // 3. Receive response headers
            Map<String, String> responseHeaders = new HashMap<>();
            responseHeaders.put(":status", "200");
            responseHeaders.put("content-type", "application/json");
            responseHeaders.put("content-length", "1234");

            byte[] encodedResponseHeaders = encoder.encode(responseHeaders);

            // 4. Receive response body
            byte[] responseBody = "{\"users\": [...]}".getBytes();
            HTTP2Frame dataFrame = frameParser.createDataFrame(1, responseBody, true);

            assertThat(dataFrame).isNotNull();
            assertThat(dataFrame.isEndStream()).isTrue();
            assertThat(dataFrame.getPayload()).hasSize(responseBody.length);

            stream.close();
            assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.CLOSED);
        }

        @Test
        public void testCompletePostRequest() {
            // 1. Create stream
            HTTP2Stream stream = new HTTP2Stream(1, 65535);
            stream.open();

            // 2. Send request headers
            Map<String, String> requestHeaders = new HashMap<>();
            requestHeaders.put(":method", "POST");
            requestHeaders.put(":path", "/api/users");
            requestHeaders.put(":scheme", "https");
            requestHeaders.put(":authority", "api.example.com");
            requestHeaders.put("content-type", "application/json");
            requestHeaders.put("content-length", "256");

            byte[] encodedHeaders = encoder.encode(requestHeaders);
            HTTP2Frame headersFrame = frameParser.createHeadersFrame(1, encodedHeaders, false, true);

            assertThat(headersFrame).isNotNull();

            stream.setRequestHeaders(requestHeaders);

            // 3. Send request body
            byte[] requestBody = "{\"name\": \"John\", \"email\": \"john@example.com\"}".getBytes();
            HTTP2Frame dataFrame = frameParser.createDataFrame(1, requestBody, true);

            assertThat(dataFrame).isNotNull();
            assertThat(dataFrame.isEndStream()).isTrue();

            stream.sendData(requestBody);
            assertThat(stream.getSenderWindowSize()).isEqualTo(65535 - requestBody.length);

            // 4. Receive response
            Map<String, String> responseHeaders = new HashMap<>();
            responseHeaders.put(":status", "201");
            responseHeaders.put("content-type", "application/json");

            byte[] responseBody = "{\"id\": 123}".getBytes();

            stream.close();
            assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.CLOSED);
        }
    }

    @Nested
    class MultiStreamScenarios {

        @Test
        public void testTwoSimultaneousRequests() {
            // Stream 1: GET request
            HTTP2Stream stream1 = new HTTP2Stream(1, 65535);
            stream1.open();

            Map<String, String> headers1 = new HashMap<>();
            headers1.put(":method", "GET");
            headers1.put(":path", "/api/users");

            stream1.setRequestHeaders(headers1);

            // Stream 3: GET request (server would use stream 2, 4, etc.)
            HTTP2Stream stream3 = new HTTP2Stream(3, 65535);
            stream3.open();

            Map<String, String> headers3 = new HashMap<>();
            headers3.put(":method", "GET");
            headers3.put(":path", "/api/posts");

            stream3.setRequestHeaders(headers3);

            // Both streams active
            assertThat(stream1.getState()).isEqualTo(HTTP2Stream.StreamState.OPEN);
            assertThat(stream3.getState()).isEqualTo(HTTP2Stream.StreamState.OPEN);

            // Send data on both
            stream1.sendData(new byte[1000]);
            stream3.sendData(new byte[2000]);

            assertThat(stream1.getSenderWindowSize()).isEqualTo(64535);
            assertThat(stream3.getSenderWindowSize()).isEqualTo(63535);

            // Close both
            stream1.close();
            stream3.close();

            assertThat(stream1.getState()).isEqualTo(HTTP2Stream.StreamState.CLOSED);
            assertThat(stream3.getState()).isEqualTo(HTTP2Stream.StreamState.CLOSED);
        }

        @Test
        public void testRapidStreamCreationAndClosure() {
            int[] streamIds = {1, 3, 5, 7, 9};

            for (int id : streamIds) {
                HTTP2Stream stream = new HTTP2Stream(id, 65535);
                stream.open();
                streams.put(id, stream);

                assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.OPEN);
            }

            for (int id : streamIds) {
                HTTP2Stream stream = streams.get(id);
                stream.close();
                assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.CLOSED);
            }
        }

        @Test
        public void testStreamPriorities() {
            HTTP2Stream[] streams = new HTTP2Stream[5];
            for (int i = 0; i < 5; i++) {
                streams[i] = new HTTP2Stream(i * 2 + 1, 65535);
                streams[i].open();
                streams[i].setPriority(i * 10);
            }

            for (int i = 0; i < 5; i++) {
                assertThat(streams[i].getPriority()).isEqualTo(i * 10);
            }
        }
    }

    @Nested
    class CompressionAndDecompression {

        @Test
        public void testHeaderCompressionAcrossStreams() {
            HPACKEncoder enc = new HPACKEncoder();
            HPACKDecoder dec = new HPACKDecoder();

            // Stream 1 headers
            Map<String, String> headers1 = new HashMap<>();
            headers1.put(":method", "GET");
            headers1.put(":path", "/api/users");
            headers1.put(":authority", "api.example.com");

            byte[] encoded1 = enc.encode(headers1);
            Map<String, String> decoded1 = dec.decode(encoded1);

            assertThat(decoded1).containsAllEntriesOf(headers1);

            // Stream 2 headers (should reuse dynamic table)
            Map<String, String> headers2 = new HashMap<>();
            headers2.put(":method", "GET");
            headers2.put(":path", "/api/posts");
            headers2.put(":authority", "api.example.com");

            byte[] encoded2 = enc.encode(headers2);
            assertThat(encoded2.length).isLessThanOrEqualTo(encoded1.length);

            Map<String, String> decoded2 = dec.decode(encoded2);
            assertThat(decoded2).containsAllEntriesOf(headers2);
        }

        @Test
        public void testLargeResponseHeaders() {
            HPACKEncoder enc = new HPACKEncoder();
            HPACKDecoder dec = new HPACKDecoder();

            Map<String, String> headers = new HashMap<>();
            headers.put(":status", "200");
            headers.put("content-type", "application/json");
            headers.put("cache-control", "public, max-age=3600");
            headers.put("set-cookie", "session=abc123; Path=/; HttpOnly");

            StringBuilder largeValue = new StringBuilder();
            for (int i = 0; i < 1000; i++) {
                largeValue.append("X");
            }
            headers.put("x-custom-header", largeValue.toString());

            byte[] encoded = enc.encode(headers);
            Map<String, String> decoded = dec.decode(encoded);

            assertThat(decoded).containsAllEntriesOf(headers);
        }
    }

    @Nested
    class WindowManagement {

        @Test
        public void testConnectionAndStreamWindowCoordination() {
            HTTP2Stream connStream = new HTTP2Stream(0, 65535);
            HTTP2Stream dataStream1 = new HTTP2Stream(1, 65535);
            HTTP2Stream dataStream2 = new HTTP2Stream(3, 65535);

            connStream.open();
            dataStream1.open();
            dataStream2.open();

            // Send data on streams
            dataStream1.sendData(new byte[10000]);
            dataStream2.sendData(new byte[20000]);

            assertThat(dataStream1.getSenderWindowSize()).isEqualTo(55535);
            assertThat(dataStream2.getSenderWindowSize()).isEqualTo(45535);

            // Receive window updates
            dataStream1.updateSenderWindow(5000);
            dataStream2.updateSenderWindow(10000);

            assertThat(dataStream1.getSenderWindowSize()).isEqualTo(60535);
            assertThat(dataStream2.getSenderWindowSize()).isEqualTo(55535);
        }

        @Test
        public void testFlowControlBlocking() {
            HTTP2Stream stream = new HTTP2Stream(1, 1000);
            stream.open();

            // Fill window
            stream.sendData(new byte[1000]);
            assertThat(stream.getSenderWindowSize()).isEqualTo(0);

            // Window is blocked, would need update to send more
            assertThat(stream.getSenderWindowSize()).isEqualTo(0);

            // Receive window update
            stream.updateSenderWindow(500);
            assertThat(stream.getSenderWindowSize()).isEqualTo(500);

            stream.sendData(new byte[500]);
            assertThat(stream.getSenderWindowSize()).isEqualTo(0);
        }
    }

    @Nested
    class FrameSequencing {

        @Test
        public void testValidFrameSequence() {
            // 1. SETTINGS frame
            HTTP2Frame settings = frameParser.createSettingsFrame(65535, 4096, false);
            assertThat(settings.getType()).isEqualTo(HTTP2Frame.FrameType.SETTINGS);

            // 2. HEADERS frame
            Map<String, String> headers = new HashMap<>();
            headers.put(":method", "GET");
            byte[] encoded = encoder.encode(headers);
            HTTP2Frame headersFrame = frameParser.createHeadersFrame(1, encoded, true, true);
            assertThat(headersFrame.getType()).isEqualTo(HTTP2Frame.FrameType.HEADERS);

            // 3. DATA frame
            HTTP2Frame dataFrame = frameParser.createDataFrame(1, new byte[100], true);
            assertThat(dataFrame.getType()).isEqualTo(HTTP2Frame.FrameType.DATA);
        }

        @Test
        public void testHeaderFragmentationWithContinuation() {
            // HEADERS without END_HEADERS
            Map<String, String> headers = new HashMap<>();
            headers.put(":method", "GET");
            byte[] part1 = encoder.encode(headers);
            HTTP2Frame headersFrame = frameParser.createHeadersFrame(1, part1, false, false);

            assertThat(headersFrame.isEndHeaders()).isFalse();

            // CONTINUATION with END_HEADERS
            byte[] part2 = new byte[10];
            ByteBuffer buffer = ByteBuffer.allocate(19);
            buffer.putInt(10);
            buffer.put((byte) 0x09); // CONTINUATION
            buffer.put((byte) 0x04); // END_HEADERS
            buffer.putInt(1);
            buffer.put(part2);
            buffer.position(0);

            HTTP2Frame contFrame = frameParser.parseFrame(buffer);
            assertThat(contFrame.getType()).isEqualTo(HTTP2Frame.FrameType.CONTINUATION);
        }
    }

    @Nested
    class ErrorRecovery {

        @Test
        public void testStreamResetRecovery() {
            HTTP2Stream stream = new HTTP2Stream(1, 65535);
            stream.open();
            stream.sendData(new byte[5000]);

            assertThat(stream.getSenderWindowSize()).isEqualTo(60535);

            // Stream reset
            stream.reset(0);
            assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.CLOSED);
        }

        @Test
        public void testPartialDataReceived() {
            HTTP2Stream stream = new HTTP2Stream(1, 65535);
            stream.open();

            // Receive partial data
            stream.receiveData(new byte[5000]);
            assertThat(stream.getReceiverWindowSize()).isEqualTo(60535);

            // Stream closes before all expected data arrives
            stream.close();
            assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.CLOSED);
        }

        @Test
        public void testConnectionGoAwayWithOpenStreams() {
            // Create multiple open streams
            for (int i = 1; i <= 5; i++) {
                HTTP2Stream stream = new HTTP2Stream(i * 2 - 1, 65535);
                stream.open();
                streams.put(i, stream);
            }

            // Send GOAWAY
            ByteBuffer buffer = ByteBuffer.allocate(17);
            buffer.putInt(0x000008);
            buffer.put((byte) 0x07); // GOAWAY
            buffer.put((byte) 0x00);
            buffer.putInt(1); // Last stream ID
            buffer.putInt(0); // Error code
            buffer.position(0);

            HTTP2Frame goaway = frameParser.parseFrame(buffer);
            assertThat(goaway.getType()).isEqualTo(HTTP2Frame.FrameType.GOAWAY);

            // After GOAWAY, no new streams can be created
            // Existing streams should be allowed to finish
            for (int id = 1; id <= 3; id++) {
                HTTP2Stream stream = streams.get(id);
                if (stream != null && stream.getState() == HTTP2Stream.StreamState.OPEN) {
                    stream.close();
                }
            }
        }
    }

    @Nested
    class StatelessValidation {

        @Test
        public void testFrameParsingConsistency() {
            byte[] testPayload = {1, 2, 3, 4, 5};
            HTTP2Frame original = new HTTP2Frame(HTTP2Frame.FrameType.DATA, (byte) 0x00, 1, testPayload);

            // Encode and parse multiple times
            for (int i = 0; i < 5; i++) {
                ByteBuffer encoded = frameParser.encodeFrame(original);
                encoded.rewind();
                HTTP2Frame parsed = frameParser.parseFrame(encoded);

                assertThat(parsed.getType()).isEqualTo(original.getType());
                assertThat(parsed.getStreamId()).isEqualTo(original.getStreamId());
                assertThat(parsed.getPayload()).isEqualTo(original.getPayload());
            }
        }

        @Test
        public void testHeaderRoundTripConsistency() {
            Map<String, String> original = new HashMap<>();
            original.put(":method", "GET");
            original.put(":path", "/test");
            original.put(":authority", "example.com");

            // Multiple encode/decode cycles
            for (int i = 0; i < 5; i++) {
                byte[] encoded = encoder.encode(original);
                Map<String, String> decoded = decoder.decode(encoded);

                for (String key : original.keySet()) {
                    assertThat(decoded).containsEntry(key, original.get(key));
                }
            }
        }
    }

    @Nested
    class BoundaryConditions {

        @Test
        public void testMaxStreamId() {
            HTTP2Stream stream = new HTTP2Stream(0x7FFFFFFF, 65535);
            stream.open();

            assertThat(stream.getStreamId()).isEqualTo(0x7FFFFFFF);
            assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.OPEN);
        }

        @Test
        public void testMaxWindowSize() {
            HTTP2Stream stream = new HTTP2Stream(1, Integer.MAX_VALUE);

            assertThat(stream.getSenderWindowSize()).isEqualTo(Integer.MAX_VALUE);
            assertThat(stream.getReceiverWindowSize()).isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        public void testMinimalStreamSetup() {
            HTTP2Stream stream = new HTTP2Stream(1, 1);
            stream.open();

            assertThat(stream.getSenderWindowSize()).isEqualTo(1);
            assertThat(stream.getReceiverWindowSize()).isEqualTo(1);
        }

        @Test
        public void testEmptyFramePayloads() {
            HTTP2Frame emptyData = new HTTP2Frame(HTTP2Frame.FrameType.DATA, (byte) 0x00, 1, new byte[0]);
            HTTP2Frame emptySettings = new HTTP2Frame(HTTP2Frame.FrameType.SETTINGS, (byte) 0x00, 0, new byte[0]);

            ByteBuffer encodedData = frameParser.encodeFrame(emptyData);
            ByteBuffer encodedSettings = frameParser.encodeFrame(emptySettings);

            assertThat(encodedData.remaining()).isEqualTo(9); // Header only
            assertThat(encodedSettings.remaining()).isEqualTo(9);
        }
    }
}
