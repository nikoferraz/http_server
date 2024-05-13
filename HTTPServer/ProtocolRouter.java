package HTTPServer;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.logging.Logger;
import java.util.logging.Level;

public class ProtocolRouter {

    private static final Logger logger = Logger.getLogger(ProtocolRouter.class.getName());

    private static final String HTTP2_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";
    private static final byte[] HTTP2_PREFACE_BYTES = HTTP2_PREFACE.getBytes();

    private static final byte[] HTTP1_GET = "GET".getBytes();
    private static final byte[] HTTP1_POST = "POST".getBytes();
    private static final byte[] HTTP1_PUT = "PUT".getBytes();
    private static final byte[] HTTP1_DELETE = "DELETE".getBytes();
    private static final byte[] HTTP1_HEAD = "HEAD".getBytes();
    private static final byte[] HTTP1_OPTIONS = "OPTIONS".getBytes();
    private static final byte[] HTTP1_PATCH = "PATCH".getBytes();
    private static final byte[] HTTP1_CONNECT = "CONNECT".getBytes();
    private static final byte[] HTTP1_TRACE = "TRACE".getBytes();

    public enum Protocol {
        HTTP_1_1("http/1.1"),
        HTTP_2("h2");

        private final String name;

        Protocol(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    public static Protocol detectProtocol(Socket socket, TLSManager tlsManager) throws IOException {
        // First, try ALPN negotiation if TLS is used
        if (socket instanceof SSLSocket) {
            SSLSocket sslSocket = (SSLSocket) socket;

            if (tlsManager != null && tlsManager.isALPNSupported()) {
                String negotiatedProtocol = tlsManager.getApplicationProtocol(sslSocket);
                if (negotiatedProtocol != null && !negotiatedProtocol.isEmpty()) {
                    if ("h2".equals(negotiatedProtocol)) {
                        return Protocol.HTTP_2;
                    } else if ("http/1.1".equals(negotiatedProtocol)) {
                        return Protocol.HTTP_1_1;
                    }
                }
            }
        }

        // Fall back to connection preface detection
        return detectByPreface(socket);
    }

    private static Protocol detectByPreface(Socket socket) throws IOException {
        InputStream input = socket.getInputStream();

        // Mark position if supported
        if (input.markSupported()) {
            input.mark(HTTP2_PREFACE_BYTES.length + 1);
        }

        byte[] buffer = new byte[HTTP2_PREFACE_BYTES.length];
        int bytesRead = input.read(buffer);

        if (bytesRead >= HTTP2_PREFACE_BYTES.length) {
            // Check for HTTP/2 preface
            if (isHTTP2Preface(buffer)) {
                logger.fine("Detected HTTP/2 connection via preface");
                return Protocol.HTTP_2;
            }
        }

        // Reset input stream if possible
        if (input.markSupported()) {
            try {
                input.reset();
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to reset input stream", e);
            }
        } else {
            logger.warning("Input stream doesn't support mark/reset; protocol detection may fail");
        }

        // Check for HTTP/1.1 methods
        if (bytesRead > 0 && isHTTP1Method(buffer[0])) {
            logger.fine("Detected HTTP/1.1 connection via method");
            return Protocol.HTTP_1_1;
        }

        // Default to HTTP/1.1
        logger.fine("Defaulting to HTTP/1.1 protocol");
        return Protocol.HTTP_1_1;
    }

    private static boolean isHTTP2Preface(byte[] buffer) {
        if (buffer.length < HTTP2_PREFACE_BYTES.length) {
            return false;
        }

        for (int i = 0; i < HTTP2_PREFACE_BYTES.length; i++) {
            if (buffer[i] != HTTP2_PREFACE_BYTES[i]) {
                return false;
            }
        }

        return true;
    }

    private static boolean isHTTP1Method(byte b) {
        // Check first byte of HTTP methods
        // G = GET, POST, OPTIONS, DELETE, HEAD, PATCH
        // P = POST, PUT, PATCH
        // D = DELETE
        // H = HEAD
        // O = OPTIONS
        // C = CONNECT
        // T = TRACE

        switch (b) {
            case 'G': // GET
            case 'P': // POST, PUT, PATCH
            case 'D': // DELETE
            case 'H': // HEAD
            case 'O': // OPTIONS
            case 'C': // CONNECT
            case 'T': // TRACE
                return true;
            default:
                return false;
        }
    }

    public static String getProtocolName(Protocol protocol) {
        return protocol.getName();
    }

    public static boolean isHTTP2(Protocol protocol) {
        return protocol == Protocol.HTTP_2;
    }

    public static boolean isHTTP1(Protocol protocol) {
        return protocol == Protocol.HTTP_1_1;
    }
}
