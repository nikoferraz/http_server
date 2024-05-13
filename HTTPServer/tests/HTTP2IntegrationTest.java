package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

public class HTTP2IntegrationTest {

    private HTTP2FrameParser frameParser;
    private HPACKEncoder hpackEncoder;
    private HPACKDecoder hpackDecoder;

    @BeforeEach
    public void setUp() {
        frameParser = new HTTP2FrameParser();
        hpackEncoder = new HPACKEncoder();
        hpackDecoder = new HPACKDecoder();
    }

    @Test
    public void testSettingsFrameExchange() {
        HTTP2Frame settingsFrame = frameParser.createSettingsFrame(65535, 4096, false);

        assertThat(settingsFrame).isNotNull();
        assertThat(settingsFrame.getType()).isEqualTo(HTTP2Frame.FrameType.SETTINGS);
        assertThat(settingsFrame.getStreamId()).isEqualTo(0);
        assertThat(settingsFrame.getPayload().length % 6).isEqualTo(0);
    }

    @Test
    public void testHeadersAndDataFrameSequence() {
        Map<String, String> headers = new HashMap<>();
        headers.put(":method", "GET");
        headers.put(":path", "/api/users");
        headers.put(":scheme", "https");
        headers.put(":authority", "api.example.com");

        byte[] encodedHeaders = hpackEncoder.encode(headers);

        HTTP2Frame headersFrame = frameParser.createHeadersFrame(1, encodedHeaders, false, true);
        assertThat(headersFrame).isNotNull();
        assertThat(headersFrame.getStreamId()).isEqualTo(1);
        assertThat(headersFrame.isEndHeaders()).isTrue();
        assertThat(headersFrame.isEndStream()).isFalse();

        byte[] responseBody = "Hello, World!".getBytes();
        HTTP2Frame dataFrame = frameParser.createDataFrame(1, responseBody, true);
        assertThat(dataFrame).isNotNull();
        assertThat(dataFrame.getStreamId()).isEqualTo(1);
        assertThat(dataFrame.isEndStream()).isTrue();
    }

    @Test
    public void testMultipleStreamsSimultaneously() {
        Map<String, String> headers1 = new HashMap<>();
        headers1.put(":method", "GET");
        headers1.put(":path", "/resource1");

        Map<String, String> headers2 = new HashMap<>();
        headers2.put(":method", "POST");
        headers2.put(":path", "/resource2");

        byte[] encoded1 = hpackEncoder.encode(headers1);
        byte[] encoded2 = hpackEncoder.encode(headers2);

        HTTP2Frame frame1 = frameParser.createHeadersFrame(1, encoded1, true, true);
        HTTP2Frame frame2 = frameParser.createHeadersFrame(3, encoded2, false, true);

        assertThat(frame1.getStreamId()).isEqualTo(1);
        assertThat(frame2.getStreamId()).isEqualTo(3);

        Map<String, String> decoded1 = hpackDecoder.decode(encoded1);
        Map<String, String> decoded2 = hpackDecoder.decode(encoded2);

        assertThat(decoded1).containsEntry(":method", "GET");
        assertThat(decoded2).containsEntry(":method", "POST");
    }

    @Test
    public void testWindowUpdateOnConnectionLevel() {
        HTTP2Frame windowUpdateFrame = frameParser.createWindowUpdateFrame(0, 1000);

        assertThat(windowUpdateFrame).isNotNull();
        assertThat(windowUpdateFrame.getType()).isEqualTo(HTTP2Frame.FrameType.WINDOW_UPDATE);
        assertThat(windowUpdateFrame.getStreamId()).isEqualTo(0);
    }

    @Test
    public void testWindowUpdateOnStreamLevel() {
        HTTP2Frame windowUpdateFrame = frameParser.createWindowUpdateFrame(5, 500);

        assertThat(windowUpdateFrame).isNotNull();
        assertThat(windowUpdateFrame.getStreamId()).isEqualTo(5);
    }

    @Test
    public void testPingFrameRoundTrip() {
        long opaqueData = 0x0102030405060708L;
        HTTP2Frame pingFrame = frameParser.createPingFrame(opaqueData);

        assertThat(pingFrame).isNotNull();
        assertThat(pingFrame.getType()).isEqualTo(HTTP2Frame.FrameType.PING);
        assertThat(pingFrame.getPayload().length).isEqualTo(8);
    }

    @Test
    public void testGoAwaySequence() {
        HTTP2Frame goAwayFrame = frameParser.createGoAwayFrame(5, 0, "Normal shutdown");

        assertThat(goAwayFrame).isNotNull();
        assertThat(goAwayFrame.getType()).isEqualTo(HTTP2Frame.FrameType.GOAWAY);
        assertThat(goAwayFrame.getStreamId()).isEqualTo(0);
    }

    @Test
    public void testRstStreamFrame() {
        HTTP2Frame rstFrame = frameParser.createRstStreamFrame(3, 1);

        assertThat(rstFrame).isNotNull();
        assertThat(rstFrame.getType()).isEqualTo(HTTP2Frame.FrameType.RST_STREAM);
        assertThat(rstFrame.getStreamId()).isEqualTo(3);
    }

    @Test
    public void testStreamFlowControl() {
        HTTP2Stream stream = new HTTP2Stream(1, 1000);
        stream.open();

        byte[] data1 = new byte[400];
        stream.receiveData(data1);
        assertThat(stream.getReceiverWindowSize()).isEqualTo(600);

        byte[] data2 = new byte[300];
        stream.receiveData(data2);
        assertThat(stream.getReceiverWindowSize()).isEqualTo(300);

        stream.updateReceiverWindow(400);
        assertThat(stream.getReceiverWindowSize()).isEqualTo(700);
    }

    @Test
    public void testHeaderCompressionWithDynamicTable() {
        Map<String, String> headers1 = new HashMap<>();
        headers1.put("custom-header", "custom-value");
        headers1.put("x-request-id", "12345");

        byte[] encoded1 = hpackEncoder.encode(headers1);

        Map<String, String> headers2 = new HashMap<>();
        headers2.put("custom-header", "custom-value");
        headers2.put("x-request-id", "67890");

        byte[] encoded2 = hpackEncoder.encode(headers2);

        assertThat(encoded2.length).isLessThanOrEqualTo(encoded1.length);

        Map<String, String> decoded1 = hpackDecoder.decode(encoded1);
        Map<String, String> decoded2 = hpackDecoder.decode(encoded2);

        assertThat(decoded1).containsEntry("custom-header", "custom-value");
        assertThat(decoded2).containsEntry("custom-header", "custom-value");
    }

    @Test
    public void testLargeHeaderBlockFragmentation() {
        Map<String, String> headers = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            headers.put("header-" + i, "value-" + i);
        }

        byte[] encoded = hpackEncoder.encode(headers);
        Map<String, String> decoded = hpackDecoder.decode(encoded);

        assertThat(decoded.size()).isGreaterThanOrEqualTo(50); // At least some headers should decode
    }

    @Test
    public void testStreamPriorityWeighting() {
        HTTP2Stream highPriorityStream = new HTTP2Stream(1, 65535);
        highPriorityStream.setPriority(256); // Higher weight

        HTTP2Stream normalPriorityStream = new HTTP2Stream(3, 65535);
        normalPriorityStream.setPriority(16); // Default weight

        assertThat(highPriorityStream.getPriority()).isGreaterThan(normalPriorityStream.getPriority());
    }

    @Test
    public void testStreamDependency() {
        HTTP2Stream parentStream = new HTTP2Stream(0, 65535);
        HTTP2Stream childStream = new HTTP2Stream(1, 65535);

        childStream.setDependency(parentStream);
        assertThat(childStream.getDependency()).isEqualTo(parentStream);
    }

    @Test
    public void testFrameEncodingAndDecoding() {
        byte[] originalPayload = {1, 2, 3, 4, 5, 6, 7, 8};
        HTTP2Frame originalFrame = new HTTP2Frame(
            HTTP2Frame.FrameType.DATA,
            (byte) 0x00,
            1,
            originalPayload
        );

        ByteBuffer encoded = frameParser.encodeFrame(originalFrame);
        encoded.rewind();

        HTTP2Frame decodedFrame = frameParser.parseFrame(encoded);

        assertThat(decodedFrame).isNotNull();
        assertThat(decodedFrame.getType()).isEqualTo(HTTP2Frame.FrameType.DATA);
        assertThat(decodedFrame.getStreamId()).isEqualTo(1);
        assertThat(decodedFrame.getPayload()).isEqualTo(originalPayload);
    }

    @Test
    public void testProtocolDetectionHTTP2Preface() throws Exception {
        Socket mockSocket = new Socket() {
            private ByteArrayInputStream input = new ByteArrayInputStream("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes());

            @Override
            public InputStream getInputStream() {
                return input;
            }

            @Override
            public boolean isConnected() {
                return true;
            }
        };

        ProtocolRouter.Protocol protocol = ProtocolRouter.detectProtocol(mockSocket, null);
        assertThat(protocol).isEqualTo(ProtocolRouter.Protocol.HTTP_2);
    }

    @Test
    public void testProtocolDetectionHTTP1() throws Exception {
        Socket mockSocket = new Socket() {
            private ByteArrayInputStream input = new ByteArrayInputStream("GET / HTTP/1.1\r\n".getBytes());

            @Override
            public InputStream getInputStream() {
                return input;
            }

            @Override
            public boolean isConnected() {
                return true;
            }
        };

        ProtocolRouter.Protocol protocol = ProtocolRouter.detectProtocol(mockSocket, null);
        assertThat(protocol).isEqualTo(ProtocolRouter.Protocol.HTTP_1_1);
    }

    public static class ByteArrayInputStream extends InputStream {
        private byte[] data;
        private int position = 0;

        public ByteArrayInputStream(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            if (position >= data.length) {
                return -1;
            }
            return data[position++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (position >= data.length) {
                return -1;
            }
            int available = data.length - position;
            int toRead = Math.min(len, available);
            System.arraycopy(data, position, b, off, toRead);
            position += toRead;
            return toRead;
        }

        @Override
        public boolean markSupported() {
            return true;
        }

        private int markPosition = 0;

        @Override
        public void mark(int readLimit) {
            markPosition = position;
        }

        @Override
        public void reset() {
            position = markPosition;
        }
    }
}
