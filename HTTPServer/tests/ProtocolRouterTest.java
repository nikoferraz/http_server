package HTTPServer;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.Socket;

import static org.assertj.core.api.Assertions.*;

public class ProtocolRouterTest {

    @Test
    public void testDetectHTTP2ByPreface() throws Exception {
        Socket mockSocket = new MockSocket("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\nGET / HTTP/1.1");

        ProtocolRouter.Protocol protocol = ProtocolRouter.detectProtocol(mockSocket, null);

        assertThat(protocol).isEqualTo(ProtocolRouter.Protocol.HTTP_2);
    }

    @Test
    public void testDetectHTTP1WithGET() throws Exception {
        Socket mockSocket = new MockSocket("GET / HTTP/1.1\r\nHost: example.com");

        ProtocolRouter.Protocol protocol = ProtocolRouter.detectProtocol(mockSocket, null);

        assertThat(protocol).isEqualTo(ProtocolRouter.Protocol.HTTP_1_1);
    }

    @Test
    public void testDetectHTTP1WithPOST() throws Exception {
        Socket mockSocket = new MockSocket("POST /api HTTP/1.1\r\nHost: example.com");

        ProtocolRouter.Protocol protocol = ProtocolRouter.detectProtocol(mockSocket, null);

        assertThat(protocol).isEqualTo(ProtocolRouter.Protocol.HTTP_1_1);
    }

    @Test
    public void testDetectHTTP1WithHEAD() throws Exception {
        Socket mockSocket = new MockSocket("HEAD / HTTP/1.1\r\nHost: example.com");

        ProtocolRouter.Protocol protocol = ProtocolRouter.detectProtocol(mockSocket, null);

        assertThat(protocol).isEqualTo(ProtocolRouter.Protocol.HTTP_1_1);
    }

    @Test
    public void testDetectHTTP1WithDELETE() throws Exception {
        Socket mockSocket = new MockSocket("DELETE /resource HTTP/1.1");

        ProtocolRouter.Protocol protocol = ProtocolRouter.detectProtocol(mockSocket, null);

        assertThat(protocol).isEqualTo(ProtocolRouter.Protocol.HTTP_1_1);
    }

    @Test
    public void testDetectHTTP1WithPUT() throws Exception {
        Socket mockSocket = new MockSocket("PUT /resource HTTP/1.1");

        ProtocolRouter.Protocol protocol = ProtocolRouter.detectProtocol(mockSocket, null);

        assertThat(protocol).isEqualTo(ProtocolRouter.Protocol.HTTP_1_1);
    }

    @Test
    public void testDetectHTTP1WithOPTIONS() throws Exception {
        Socket mockSocket = new MockSocket("OPTIONS * HTTP/1.1");

        ProtocolRouter.Protocol protocol = ProtocolRouter.detectProtocol(mockSocket, null);

        assertThat(protocol).isEqualTo(ProtocolRouter.Protocol.HTTP_1_1);
    }

    @Test
    public void testDetectHTTP1WithPATCH() throws Exception {
        Socket mockSocket = new MockSocket("PATCH /resource HTTP/1.1");

        ProtocolRouter.Protocol protocol = ProtocolRouter.detectProtocol(mockSocket, null);

        assertThat(protocol).isEqualTo(ProtocolRouter.Protocol.HTTP_1_1);
    }

    @Test
    public void testDetectHTTP1WithCONNECT() throws Exception {
        Socket mockSocket = new MockSocket("CONNECT example.com:443 HTTP/1.1");

        ProtocolRouter.Protocol protocol = ProtocolRouter.detectProtocol(mockSocket, null);

        assertThat(protocol).isEqualTo(ProtocolRouter.Protocol.HTTP_1_1);
    }

    @Test
    public void testDetectHTTP1WithTRACE() throws Exception {
        Socket mockSocket = new MockSocket("TRACE / HTTP/1.1");

        ProtocolRouter.Protocol protocol = ProtocolRouter.detectProtocol(mockSocket, null);

        assertThat(protocol).isEqualTo(ProtocolRouter.Protocol.HTTP_1_1);
    }

    @Test
    public void testProtocolNames() {
        assertThat(ProtocolRouter.getProtocolName(ProtocolRouter.Protocol.HTTP_2)).isEqualTo("h2");
        assertThat(ProtocolRouter.getProtocolName(ProtocolRouter.Protocol.HTTP_1_1)).isEqualTo("http/1.1");
    }

    @Test
    public void testIsHTTP2Check() {
        assertThat(ProtocolRouter.isHTTP2(ProtocolRouter.Protocol.HTTP_2)).isTrue();
        assertThat(ProtocolRouter.isHTTP2(ProtocolRouter.Protocol.HTTP_1_1)).isFalse();
    }

    @Test
    public void testIsHTTP1Check() {
        assertThat(ProtocolRouter.isHTTP1(ProtocolRouter.Protocol.HTTP_1_1)).isTrue();
        assertThat(ProtocolRouter.isHTTP1(ProtocolRouter.Protocol.HTTP_2)).isFalse();
    }

    @Test
    public void testEmptyDataDefaultsToHTTP1() throws Exception {
        Socket mockSocket = new MockSocket("");

        ProtocolRouter.Protocol protocol = ProtocolRouter.detectProtocol(mockSocket, null);

        assertThat(protocol).isEqualTo(ProtocolRouter.Protocol.HTTP_1_1);
    }

    @Test
    public void testInvalidDataDefaultsToHTTP1() throws Exception {
        Socket mockSocket = new MockSocket("INVALID DATA 123");

        ProtocolRouter.Protocol protocol = ProtocolRouter.detectProtocol(mockSocket, null);

        assertThat(protocol).isEqualTo(ProtocolRouter.Protocol.HTTP_1_1);
    }

    private static class MockSocket extends Socket {
        private ByteArrayInputStream input;

        public MockSocket(String data) {
            this.input = new ByteArrayInputStream(data.getBytes());
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public boolean isConnected() {
            return true;
        }
    }

    private static class ByteArrayInputStream extends InputStream {
        private byte[] data;
        private int position = 0;
        private int markPosition = 0;

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

        @Override
        public void mark(int readLimit) {
            markPosition = position;
        }

        @Override
        public void reset() {
            position = markPosition;
        }

        @Override
        public void close() {
        }
    }
}
