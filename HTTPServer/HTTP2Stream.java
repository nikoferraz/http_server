package HTTPServer;

import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class HTTP2Stream {

    public enum StreamState {
        IDLE,
        OPEN,
        HALF_CLOSED_LOCAL,
        HALF_CLOSED_REMOTE,
        CLOSED,
        RESERVED_LOCAL,
        RESERVED_REMOTE
    }

    private final int streamId;
    private StreamState state;
    private int senderWindowSize;
    private int receiverWindowSize;
    private final BlockingQueue<byte[]> dataQueue;
    private Map<String, String> requestHeaders;
    private Map<String, String> responseHeaders;
    private byte[] responseBody;
    private boolean endStreamReceived;
    private int priority;
    private HTTP2Stream dependency;
    private boolean exclusive;

    public HTTP2Stream(int streamId, int initialWindowSize) {
        this.streamId = streamId;
        this.state = StreamState.IDLE;
        this.senderWindowSize = initialWindowSize;
        this.receiverWindowSize = initialWindowSize;
        this.dataQueue = new LinkedBlockingQueue<>();
        this.requestHeaders = new HashMap<>();
        this.responseHeaders = new HashMap<>();
        this.endStreamReceived = false;
        this.priority = 16; // Default priority
        this.exclusive = false;
    }

    public int getStreamId() {
        return streamId;
    }

    public StreamState getState() {
        return state;
    }

    public void open() {
        if (state == StreamState.IDLE) {
            state = StreamState.OPEN;
        }
    }

    public void close() {
        state = StreamState.CLOSED;
    }

    public void reset(int errorCode) {
        state = StreamState.CLOSED;
    }

    public int getSenderWindowSize() {
        return senderWindowSize;
    }

    public int getReceiverWindowSize() {
        return receiverWindowSize;
    }

    public void updateSenderWindow(int increment) {
        senderWindowSize += increment;
    }

    public void updateReceiverWindow(int increment) {
        receiverWindowSize += increment;
    }

    public void receiveData(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }

        receiverWindowSize -= data.length;
        dataQueue.offer(data);
    }

    public byte[] getReceivedData() {
        if (dataQueue.isEmpty()) {
            return null;
        }

        List<byte[]> allData = new ArrayList<>();
        dataQueue.drainTo(allData);

        int totalLength = allData.stream().mapToInt(b -> b.length).sum();
        byte[] result = new byte[totalLength];
        int offset = 0;

        for (byte[] chunk : allData) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }

        return result;
    }

    public void sendData(byte[] data) {
        if (data != null) {
            senderWindowSize -= data.length;
        }
    }

    public void setRequestHeaders(Map<String, String> headers) {
        this.requestHeaders = new HashMap<>(headers);
    }

    public Map<String, String> getRequestHeaders() {
        return requestHeaders;
    }

    public void setResponseHeaders(Map<String, String> headers) {
        this.responseHeaders = new HashMap<>(headers);
    }

    public Map<String, String> getResponseHeaders() {
        return responseHeaders;
    }

    public void setResponseBody(byte[] body) {
        this.responseBody = body;
    }

    public byte[] getResponseBody() {
        return responseBody;
    }

    public void setEndStreamReceived(boolean endStreamReceived) {
        this.endStreamReceived = endStreamReceived;
        if (endStreamReceived && state == StreamState.OPEN) {
            state = StreamState.HALF_CLOSED_REMOTE;
        }
    }

    public boolean isEndStreamReceived() {
        return endStreamReceived;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public HTTP2Stream getDependency() {
        return dependency;
    }

    public void setDependency(HTTP2Stream dependency) {
        this.dependency = dependency;
    }

    public void setExclusive(boolean exclusive) {
        this.exclusive = exclusive;
    }

    public boolean isExclusive() {
        return exclusive;
    }

    public boolean isClientInitiated() {
        return (streamId & 1) == 1;
    }

    public boolean isServerInitiated() {
        return (streamId & 1) == 0;
    }

    public boolean isActive() {
        return state == StreamState.OPEN || state == StreamState.HALF_CLOSED_LOCAL || state == StreamState.HALF_CLOSED_REMOTE;
    }

    public synchronized void checkFlowControlViolation() throws FlowControlException {
        if (senderWindowSize < 0) {
            throw new FlowControlException("Sender flow control window exceeded");
        }
        if (receiverWindowSize < 0) {
            throw new FlowControlException("Receiver flow control window exceeded");
        }
    }

    public static class FlowControlException extends Exception {
        public FlowControlException(String message) {
            super(message);
        }
    }
}
