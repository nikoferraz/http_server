package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

public class HTTP2StreamTest {

    private HTTP2Stream stream;

    @BeforeEach
    public void setUp() {
        stream = new HTTP2Stream(1, 65535);
    }

    @Test
    public void testStreamCreation() {
        assertThat(stream.getStreamId()).isEqualTo(1);
        assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.IDLE);
        assertThat(stream.getReceiverWindowSize()).isEqualTo(65535);
        assertThat(stream.getSenderWindowSize()).isEqualTo(65535);
    }

    @Test
    public void testOpenStream() {
        stream.open();

        assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.OPEN);
    }

    @Test
    public void testReceiveData() {
        stream.open();
        byte[] data = {1, 2, 3, 4, 5};

        stream.receiveData(data);

        assertThat(stream.getReceivedData()).isNotNull();
        assertThat(stream.getReceiverWindowSize()).isEqualTo(65535 - 5);
    }

    @Test
    public void testSendData() {
        stream.open();
        byte[] data = {1, 2, 3, 4, 5};

        stream.sendData(data);

        assertThat(stream.getSenderWindowSize()).isEqualTo(65535 - 5);
    }

    @Test
    public void testFlowControlWindowUpdate() {
        stream.open();
        byte[] data = {1, 2, 3, 4, 5};
        stream.sendData(data);

        assertThat(stream.getSenderWindowSize()).isEqualTo(65530);

        stream.updateSenderWindow(1000);

        assertThat(stream.getSenderWindowSize()).isEqualTo(66530);
    }

    @Test
    public void testStreamHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put(":method", "GET");
        headers.put(":path", "/");

        stream.setRequestHeaders(headers);

        assertThat(stream.getRequestHeaders()).containsEntry(":method", "GET");
        assertThat(stream.getRequestHeaders()).containsEntry(":path", "/");
    }

    @Test
    public void testCloseStream() {
        stream.open();
        stream.close();

        assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.CLOSED);
    }

    @Test
    public void testResetStream() {
        stream.open();
        stream.reset(0);

        assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.CLOSED);
    }

    @Test
    public void testMultipleDataFrames() {
        stream.open();

        stream.receiveData(new byte[]{1, 2, 3});
        assertThat(stream.getReceiverWindowSize()).isEqualTo(65532);

        stream.receiveData(new byte[]{4, 5});
        assertThat(stream.getReceiverWindowSize()).isEqualTo(65530);
    }

    @Test
    public void testExceedWindowSize() {
        stream.open();
        byte[] largeData = new byte[66000];

        stream.receiveData(largeData);

        assertThat(stream.getReceiverWindowSize()).isLessThan(0);
    }

    @Test
    public void testStreamPriority() {
        stream.setPriority(16);

        assertThat(stream.getPriority()).isEqualTo(16);
    }

    @Test
    public void testStreamDependency() {
        HTTP2Stream parentStream = new HTTP2Stream(0, 65535);
        stream.setDependency(parentStream);

        assertThat(stream.getDependency()).isEqualTo(parentStream);
    }

    @Test
    public void testEndStreamFlag() {
        stream.open();

        assertThat(stream.isEndStreamReceived()).isFalse();

        stream.setEndStreamReceived(true);

        assertThat(stream.isEndStreamReceived()).isTrue();
    }

    @Test
    public void testStreamIdValidity() {
        assertThat(stream.getStreamId()).isEqualTo(1);
        assertThat(stream.isClientInitiated()).isTrue(); // Odd ID = client

        HTTP2Stream evenStream = new HTTP2Stream(2, 65535);
        assertThat(evenStream.isClientInitiated()).isFalse(); // Even ID = server
    }
}
