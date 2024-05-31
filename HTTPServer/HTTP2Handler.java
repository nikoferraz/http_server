package HTTPServer;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.logging.Level;

public class HTTP2Handler {

    private static final Logger logger = Logger.getLogger(HTTP2Handler.class.getName());

    private static final String HTTP2_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";
    private static final byte[] HTTP2_PREFACE_BYTES = HTTP2_PREFACE.getBytes();

    private static final int DEFAULT_INITIAL_WINDOW_SIZE = 65535;
    private static final int DEFAULT_HEADER_TABLE_SIZE = 4096;
    private static final int SETTINGS_TIMEOUT_MS = 5000;

    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;
    private final MetricsCollector metrics;
    private final StructuredLogger structuredLogger;

    private final HTTP2FrameParser frameParser;
    private final HPACKEncoder hpackEncoder;
    private final HPACKDecoder hpackDecoder;

    private final ConcurrentHashMap<Integer, HTTP2Stream> streams;
    private final AtomicInteger nextStreamId;

    private int connectionWindowSize;
    private int remoteInitialWindowSize;
    private int localInitialWindowSize;
    private boolean settingsAcked;

    public HTTP2Handler(Socket socket, MetricsCollector metrics, StructuredLogger structuredLogger) throws IOException {
        this.socket = socket;
        this.input = socket.getInputStream();
        this.output = socket.getOutputStream();
        this.metrics = metrics;
        this.structuredLogger = structuredLogger;

        this.frameParser = new HTTP2FrameParser();
        this.hpackEncoder = new HPACKEncoder(DEFAULT_HEADER_TABLE_SIZE);
        this.hpackDecoder = new HPACKDecoder(DEFAULT_HEADER_TABLE_SIZE);

        this.streams = new ConcurrentHashMap<>();
        this.nextStreamId = new AtomicInteger(2); // Server-initiated streams use even IDs

        this.connectionWindowSize = DEFAULT_INITIAL_WINDOW_SIZE;
        this.remoteInitialWindowSize = DEFAULT_INITIAL_WINDOW_SIZE;
        this.localInitialWindowSize = DEFAULT_INITIAL_WINDOW_SIZE;
        this.settingsAcked = false;
    }

    public void handleConnection() throws IOException {
        try {
            if (!verifyClientPreface()) {
                sendGoAway(1, "Invalid HTTP/2 preface");
                return;
            }

            sendSettings();

            ByteBuffer buffer = ByteBuffer.allocate(16384);

            while (socket.isConnected()) {
                int bytesRead = input.read(buffer.array(), buffer.position(), buffer.remaining());

                if (bytesRead == -1) {
                    break;
                }

                buffer.position(buffer.position() + bytesRead);
                buffer.flip();

                processFrames(buffer);

                buffer.clear();
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Error handling HTTP/2 connection", e);
        } finally {
            closeConnection();
        }
    }

    private boolean verifyClientPreface() throws IOException {
        byte[] preface = new byte[HTTP2_PREFACE_BYTES.length];
        int bytesRead = input.read(preface);

        if (bytesRead != HTTP2_PREFACE_BYTES.length) {
            return false;
        }

        for (int i = 0; i < preface.length; i++) {
            if (preface[i] != HTTP2_PREFACE_BYTES[i]) {
                return false;
            }
        }

        return true;
    }

    private void processFrames(ByteBuffer buffer) throws IOException {
        while (buffer.remaining() >= 9) {
            HTTP2Frame frame = frameParser.parseFrame(buffer);

            if (frame == null) {
                break;
            }

            try {
                processFrame(frame);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error processing frame: " + frame, e);
                sendRstStream(frame.getStreamId(), 1); // PROTOCOL_ERROR
            }
        }
    }

    private void processFrame(HTTP2Frame frame) throws IOException, HTTP2Stream.FlowControlException {
        switch (frame.getType()) {
            case DATA:
                handleDataFrame(frame);
                break;
            case HEADERS:
                handleHeadersFrame(frame);
                break;
            case SETTINGS:
                handleSettingsFrame(frame);
                break;
            case WINDOW_UPDATE:
                handleWindowUpdateFrame(frame);
                break;
            case PING:
                handlePingFrame(frame);
                break;
            case GOAWAY:
                handleGoAwayFrame(frame);
                break;
            case RST_STREAM:
                handleRstStreamFrame(frame);
                break;
            case PRIORITY:
                handlePriorityFrame(frame);
                break;
            case PUSH_PROMISE:
                handlePushPromiseFrame(frame);
                break;
            default:
                logger.warning("Unknown frame type: " + frame.getType());
        }
    }

    private void handleDataFrame(HTTP2Frame frame) throws IOException, HTTP2Stream.FlowControlException {
        HTTP2Stream stream = getOrCreateStream(frame.getStreamId());

        byte[] payload = frame.getPayload();

        // Check connection-level window BEFORE consuming
        if (connectionWindowSize < payload.length) {
            logger.severe("Connection flow control window exceeded");
            sendGoAway(0, "Flow control window exceeded");
            return;
        }

        // Check stream-level window BEFORE consuming
        if (stream.getReceiverWindowSize() < payload.length) {
            logger.warning("Stream " + frame.getStreamId() + " flow control window exceeded");
            sendRstStream(frame.getStreamId(), 3);
            return;
        }

        // Now it's safe to decrement
        connectionWindowSize -= payload.length;
        stream.receiveData(payload);

        if (frame.isEndStream()) {
            stream.setEndStreamReceived(true);
        }

        metrics.incrementCounter("http2_data_frames_received");
        metrics.observeHistogram("http2_frame_payload_size", (double) payload.length);
    }

    private void handleHeadersFrame(HTTP2Frame frame) throws IOException {
        HTTP2Stream stream = getOrCreateStream(frame.getStreamId());

        if (!frame.isEndHeaders()) {
            logger.warning("Fragmented HEADERS frame not fully supported yet");
            return;
        }

        try {
            byte[] payload = frame.getPayload();
            Map<String, String> headers = hpackDecoder.decode(payload);

            stream.setRequestHeaders(headers);
            stream.open();

            if (frame.isEndStream()) {
                stream.setEndStreamReceived(true);
            }

            metrics.incrementCounter("http2_headers_frames_received");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error decoding headers", e);
            sendRstStream(frame.getStreamId(), 9); // COMPRESSION_ERROR
        }
    }

    private void handleSettingsFrame(HTTP2Frame frame) throws IOException {
        byte[] payload = frame.getPayload();

        if (payload.length % 6 != 0) {
            sendGoAway(1, "Invalid SETTINGS frame");
            return;
        }

        for (int i = 0; i < payload.length; i += 6) {
            short settingId = (short) (((payload[i] & 0xFF) << 8) | (payload[i + 1] & 0xFF));
            int value = ((payload[i + 2] & 0xFF) << 24)
                      | ((payload[i + 3] & 0xFF) << 16)
                      | ((payload[i + 4] & 0xFF) << 8)
                      | (payload[i + 5] & 0xFF);

            switch (settingId) {
                case 1: // HEADER_TABLE_SIZE
                    hpackDecoder.setMaxTableSize(value);
                    break;
                case 4: // INITIAL_WINDOW_SIZE
                    remoteInitialWindowSize = value;
                    break;
                case 5: // MAX_FRAME_SIZE
                    break;
                default:
                    break;
            }
        }

        if ((frame.getFlags() & HTTP2Frame.Flags.ACK) == 0) {
            sendSettingsAck();
        } else {
            settingsAcked = true;
        }

        metrics.incrementCounter("http2_settings_frames_received");
    }

    private void handleWindowUpdateFrame(HTTP2Frame frame) throws IOException {
        byte[] payload = frame.getPayload();
        if (payload.length != 4) {
            sendGoAway(1, "Invalid WINDOW_UPDATE frame");
            return;
        }

        int windowIncrement = (((payload[0] & 0xFF) << 24)
                            | ((payload[1] & 0xFF) << 16)
                            | ((payload[2] & 0xFF) << 8)
                            | (payload[3] & 0xFF)) & 0x7FFFFFFF;

        if (frame.getStreamId() == 0) {
            connectionWindowSize += windowIncrement;
        } else {
            HTTP2Stream stream = streams.get(frame.getStreamId());
            if (stream != null) {
                stream.updateSenderWindow(windowIncrement);
            }
        }

        metrics.incrementCounter("http2_window_update_frames_received");
    }

    private void handlePingFrame(HTTP2Frame frame) throws IOException {
        if (frame.getPayload().length != 8) {
            sendGoAway(1, "Invalid PING frame");
            return;
        }

        HTTP2Frame pongFrame = new HTTP2Frame(
            HTTP2Frame.FrameType.PING,
            HTTP2Frame.Flags.ACK,
            0,
            frame.getPayload()
        );

        sendFrame(pongFrame);
        metrics.incrementCounter("http2_ping_frames_received");
    }

    private void handleGoAwayFrame(HTTP2Frame frame) throws IOException {
        logger.info("Received GOAWAY frame");
        closeConnection();
    }

    private void handleRstStreamFrame(HTTP2Frame frame) throws IOException {
        HTTP2Stream stream = streams.get(frame.getStreamId());
        if (stream != null) {
            stream.reset(0);
        }
        metrics.incrementCounter("http2_rst_stream_frames_received");
    }

    private void handlePriorityFrame(HTTP2Frame frame) throws IOException {
        HTTP2Stream stream = getOrCreateStream(frame.getStreamId());

        if (frame.getPayload().length != 5) {
            sendRstStream(frame.getStreamId(), 6); // FRAME_SIZE_ERROR
            return;
        }

        byte[] payload = frame.getPayload();
        int dependencyStreamId = (((payload[0] & 0xFF) << 24)
                                | ((payload[1] & 0xFF) << 16)
                                | ((payload[2] & 0xFF) << 8)
                                | (payload[3] & 0xFF)) & 0x7FFFFFFF;
        byte weight = payload[4];

        stream.setPriority(weight);

        metrics.incrementCounter("http2_priority_frames_received");
    }

    private void handlePushPromiseFrame(HTTP2Frame frame) throws IOException {
        logger.warning("PUSH_PROMISE frames not implemented");
    }

    private void sendSettings() throws IOException {
        HTTP2Frame settingsFrame = frameParser.createSettingsFrame(
            localInitialWindowSize,
            DEFAULT_HEADER_TABLE_SIZE,
            false
        );

        sendFrame(settingsFrame);
        metrics.incrementCounter("http2_settings_frames_sent");
    }

    private void sendSettingsAck() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(9);
        buffer.put((byte) 0x00);
        buffer.put((byte) 0x00);
        buffer.put((byte) 0x00);
        buffer.put((byte) 0x04); // SETTINGS frame type
        buffer.put((byte) 0x01); // ACK flag
        buffer.putInt(0); // Stream ID 0
        buffer.flip();

        output.write(buffer.array());
        output.flush();

        metrics.incrementCounter("http2_settings_ack_sent");
    }

    private void sendGoAway(int lastStreamId, String reason) throws IOException {
        HTTP2Frame goAwayFrame = frameParser.createGoAwayFrame(lastStreamId, 1, reason);
        sendFrame(goAwayFrame);
        metrics.incrementCounter("http2_goaway_frames_sent");
    }

    private void sendRstStream(int streamId, int errorCode) throws IOException {
        HTTP2Frame rstFrame = frameParser.createRstStreamFrame(streamId, errorCode);
        sendFrame(rstFrame);
        metrics.incrementCounter("http2_rst_stream_frames_sent");
    }

    public void sendResponse(int streamId, Map<String, String> headers, byte[] body) throws IOException {
        HTTP2Stream stream = streams.get(streamId);
        if (stream == null) {
            return;
        }

        try {
            byte[] encodedHeaders = hpackEncoder.encode(headers);

            HTTP2Frame headersFrame = frameParser.createHeadersFrame(
                streamId,
                encodedHeaders,
                body == null || body.length == 0,
                true
            );

            sendFrame(headersFrame);

            if (body != null && body.length > 0) {
                HTTP2Frame dataFrame = frameParser.createDataFrame(streamId, body, true);
                sendFrame(dataFrame);
            }

            stream.close();
            metrics.incrementCounter("http2_responses_sent");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error sending response", e);
            sendRstStream(streamId, 1);
        }
    }

    private void sendFrame(HTTP2Frame frame) throws IOException {
        ByteBuffer buffer = frameParser.encodeFrame(frame);
        output.write(buffer.array(), buffer.arrayOffset(), buffer.remaining());
        output.flush();

        metrics.incrementCounter("http2_frames_sent");
    }

    private HTTP2Stream getOrCreateStream(int streamId) {
        return streams.computeIfAbsent(streamId, id -> new HTTP2Stream(id, remoteInitialWindowSize));
    }

    private void closeConnection() {
        try {
            socket.close();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Error closing socket", e);
        }

        metrics.incrementCounter("http2_connections_closed");
    }

    public HTTP2Stream getStream(int streamId) {
        return streams.get(streamId);
    }

    public Map<Integer, HTTP2Stream> getAllStreams() {
        return Collections.unmodifiableMap(streams);
    }

    public int getConnectionWindowSize() {
        return connectionWindowSize;
    }

    public boolean isSettingsAcked() {
        return settingsAcked;
    }
}
