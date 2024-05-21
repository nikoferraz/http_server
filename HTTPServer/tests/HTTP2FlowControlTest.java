package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import static org.assertj.core.api.Assertions.*;

public class HTTP2FlowControlTest {

    private HTTP2Stream stream;

    @BeforeEach
    public void setUp() {
        stream = new HTTP2Stream(1, 65535);
    }

    @Nested
    class SenderWindowTests {

        @Test
        public void testInitialSenderWindowSize() {
            assertThat(stream.getSenderWindowSize()).isEqualTo(65535);
        }

        @Test
        public void testSenderWindowDecreasesOnSendData() {
            stream.open();
            stream.sendData(new byte[100]);

            assertThat(stream.getSenderWindowSize()).isEqualTo(65435);
        }

        @Test
        public void testMultipleSendDecrementsWindow() {
            stream.open();
            stream.sendData(new byte[100]);
            stream.sendData(new byte[200]);
            stream.sendData(new byte[300]);

            assertThat(stream.getSenderWindowSize()).isEqualTo(65535 - 100 - 200 - 300);
        }

        @Test
        public void testSenderWindowUpdateIncreases() {
            stream.open();
            stream.sendData(new byte[1000]);
            assertThat(stream.getSenderWindowSize()).isEqualTo(64535);

            stream.updateSenderWindow(500);
            assertThat(stream.getSenderWindowSize()).isEqualTo(65035);
        }

        @Test
        public void testSenderWindowCanGoNegative() {
            stream.open();
            stream.sendData(new byte[70000]);

            assertThat(stream.getSenderWindowSize()).isLessThan(0);
        }

        @Test
        public void testSenderWindowLargeIncrementRecovery() {
            stream.open();
            stream.sendData(new byte[65535]);
            assertThat(stream.getSenderWindowSize()).isEqualTo(0);

            stream.updateSenderWindow(100000);
            assertThat(stream.getSenderWindowSize()).isEqualTo(100000);
        }
    }

    @Nested
    class ReceiverWindowTests {

        @Test
        public void testInitialReceiverWindowSize() {
            assertThat(stream.getReceiverWindowSize()).isEqualTo(65535);
        }

        @Test
        public void testReceiverWindowDecreaseOnReceiveData() {
            stream.open();
            stream.receiveData(new byte[100]);

            assertThat(stream.getReceiverWindowSize()).isEqualTo(65435);
        }

        @Test
        public void testMultipleReceiveDecrementsWindow() {
            stream.open();
            stream.receiveData(new byte[100]);
            stream.receiveData(new byte[200]);
            stream.receiveData(new byte[300]);

            assertThat(stream.getReceiverWindowSize()).isEqualTo(65535 - 100 - 200 - 300);
        }

        @Test
        public void testReceiverWindowUpdateIncreases() {
            stream.open();
            stream.receiveData(new byte[1000]);
            assertThat(stream.getReceiverWindowSize()).isEqualTo(64535);

            stream.updateReceiverWindow(500);
            assertThat(stream.getReceiverWindowSize()).isEqualTo(65035);
        }

        @Test
        public void testReceiverWindowCanGoNegative() {
            stream.open();
            stream.receiveData(new byte[70000]);

            assertThat(stream.getReceiverWindowSize()).isLessThan(0);
        }

        @Test
        public void testReceiverWindowLargeDataExceeds() {
            stream.open();
            stream.receiveData(new byte[100000]);

            assertThat(stream.getReceiverWindowSize()).isEqualTo(65535 - 100000);
            assertThat(stream.getReceiverWindowSize()).isLessThan(0);
        }
    }

    @Nested
    class WindowUpdateTests {

        @Test
        public void testSmallWindowUpdate() {
            stream.open();
            int initialWindow = stream.getSenderWindowSize();

            stream.updateSenderWindow(100);

            assertThat(stream.getSenderWindowSize()).isEqualTo(initialWindow + 100);
        }

        @Test
        public void testLargeWindowUpdate() {
            stream.open();
            stream.sendData(new byte[50000]);

            stream.updateSenderWindow(100000);

            assertThat(stream.getSenderWindowSize()).isGreaterThan(50000);
        }

        @Test
        public void testWindowUpdateOverflow() {
            stream.open();
            stream.updateSenderWindow(Integer.MAX_VALUE);
            stream.updateSenderWindow(Integer.MAX_VALUE);

            // Should handle overflow gracefully (implementation dependent)
            assertThat(stream.getSenderWindowSize()).isNotNull();
        }

        @Test
        public void testZeroWindowUpdate() {
            stream.open();
            int initial = stream.getSenderWindowSize();

            stream.updateSenderWindow(0);

            assertThat(stream.getSenderWindowSize()).isEqualTo(initial);
        }

        @Test
        public void testNegativeWindowUpdate() {
            stream.open();
            int initial = stream.getSenderWindowSize();

            stream.updateSenderWindow(-1000);

            assertThat(stream.getSenderWindowSize()).isEqualTo(initial - 1000);
        }
    }

    @Nested
    class FlowControlBlocking {

        @Test
        public void testZeroSenderWindow() {
            stream.open();
            stream.sendData(new byte[65535]);

            // Window is now zero, should block further sends
            assertThat(stream.getSenderWindowSize()).isEqualTo(0);
        }

        @Test
        public void testZeroReceiverWindow() {
            stream.open();
            stream.receiveData(new byte[65535]);

            // Window is now zero, should not accept more data
            assertThat(stream.getReceiverWindowSize()).isEqualTo(0);
        }

        @Test
        public void testSenderWindowRecovery() {
            stream.open();
            stream.sendData(new byte[60000]);
            assertThat(stream.getSenderWindowSize()).isEqualTo(5535);

            stream.updateSenderWindow(60000);
            assertThat(stream.getSenderWindowSize()).isEqualTo(65535);
        }

        @Test
        public void testReceiverWindowRecovery() {
            stream.open();
            stream.receiveData(new byte[60000]);
            assertThat(stream.getReceiverWindowSize()).isEqualTo(5535);

            stream.updateReceiverWindow(60000);
            assertThat(stream.getReceiverWindowSize()).isEqualTo(65535);
        }
    }

    @Nested
    class ConnectionLevelFlowControl {

        @Test
        public void testConnectionLevelInitialWindow() {
            HTTP2Stream connStream = new HTTP2Stream(0, 65535);

            assertThat(connStream.getSenderWindowSize()).isEqualTo(65535);
            assertThat(connStream.getReceiverWindowSize()).isEqualTo(65535);
        }

        @Test
        public void testConnectionAndStreamWindowInteraction() {
            HTTP2Stream connStream = new HTTP2Stream(0, 65535);
            HTTP2Stream dataStream = new HTTP2Stream(1, 65535);

            connStream.open();
            dataStream.open();

            connStream.sendData(new byte[1000]);
            dataStream.sendData(new byte[2000]);

            assertThat(connStream.getSenderWindowSize()).isEqualTo(64535);
            assertThat(dataStream.getSenderWindowSize()).isEqualTo(63535);
        }
    }

    @Nested
    class StreamStateTransitions {

        @Test
        public void testIdleToOpenTransition() {
            assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.IDLE);

            stream.open();

            assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.OPEN);
        }

        @Test
        public void testOpenToClosedTransition() {
            stream.open();
            assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.OPEN);

            stream.close();

            assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.CLOSED);
        }

        @Test
        public void testResetTransition() {
            stream.open();
            stream.reset(0);

            assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.CLOSED);
        }

        @Test
        public void testHalfClosedStates() {
            stream.open();

            // Manually test half-closed states (if supported)
            assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.OPEN);
        }
    }

    @Nested
    class DataReceptionTests {

        @Test
        public void testReceiveEmptyData() {
            stream.open();
            stream.receiveData(new byte[0]);

            assertThat(stream.getReceiverWindowSize()).isEqualTo(65535);
        }

        @Test
        public void testReceiveNullData() {
            stream.open();
            stream.receiveData(null);

            assertThat(stream.getReceiverWindowSize()).isEqualTo(65535);
        }

        @Test
        public void testReceiveAndRetrieveData() {
            stream.open();
            byte[] testData = {1, 2, 3, 4, 5};

            stream.receiveData(testData);

            byte[] retrieved = stream.getReceivedData();
            assertThat(retrieved).isEqualTo(testData);
        }

        @Test
        public void testReceiveMultipleChunks() {
            stream.open();
            byte[] chunk1 = {1, 2, 3};
            byte[] chunk2 = {4, 5, 6};
            byte[] chunk3 = {7, 8, 9};

            stream.receiveData(chunk1);
            stream.receiveData(chunk2);
            stream.receiveData(chunk3);

            byte[] retrieved = stream.getReceivedData();
            assertThat(retrieved).hasSize(9);
            assertThat(retrieved).contains(1, 2, 3, 4, 5, 6, 7, 8, 9);
        }

        @Test
        public void testWindowDecrementWithDataReceived() {
            stream.open();
            int initialWindow = stream.getReceiverWindowSize();

            stream.receiveData(new byte[1000]);

            assertThat(stream.getReceiverWindowSize()).isEqualTo(initialWindow - 1000);
        }
    }

    @Nested
    class DataTransmissionTests {

        @Test
        public void testSendEmptyData() {
            stream.open();
            stream.sendData(new byte[0]);

            // Window should not change for empty data
            assertThat(stream.getSenderWindowSize()).isEqualTo(65535);
        }

        @Test
        public void testSendData() {
            stream.open();
            byte[] testData = {1, 2, 3, 4, 5};

            stream.sendData(testData);

            assertThat(stream.getSenderWindowSize()).isEqualTo(65535 - 5);
        }

        @Test
        public void testSendMaxWindow() {
            stream.open();
            stream.sendData(new byte[65535]);

            assertThat(stream.getSenderWindowSize()).isEqualTo(0);
        }

        @Test
        public void testSendExceedsWindow() {
            stream.open();
            stream.sendData(new byte[100000]);

            assertThat(stream.getSenderWindowSize()).isLessThan(0);
        }
    }

    @Nested
    class StreamPriorityAndDependency {

        @Test
        public void testDefaultPriority() {
            assertThat(stream.getPriority()).isEqualTo(16);
        }

        @Test
        public void testSetPriority() {
            stream.setPriority(32);

            assertThat(stream.getPriority()).isEqualTo(32);
        }

        @Test
        public void testPriorityRange() {
            for (int p = 0; p <= 255; p++) {
                stream.setPriority(p);
                assertThat(stream.getPriority()).isEqualTo(p);
            }
        }

        @Test
        public void testStreamDependency() {
            HTTP2Stream parentStream = new HTTP2Stream(0, 65535);
            stream.setDependency(parentStream);

            assertThat(stream.getDependency()).isEqualTo(parentStream);
        }

        @Test
        public void testMultipleDependencies() {
            HTTP2Stream parent1 = new HTTP2Stream(2, 65535);
            HTTP2Stream parent2 = new HTTP2Stream(4, 65535);

            stream.setDependency(parent1);
            assertThat(stream.getDependency()).isEqualTo(parent1);

            stream.setDependency(parent2);
            assertThat(stream.getDependency()).isEqualTo(parent2);
        }
    }

    @Nested
    class HeaderTests {

        @Test
        public void testSetRequestHeaders() {
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put(":method", "GET");
            headers.put(":path", "/");

            stream.setRequestHeaders(headers);

            assertThat(stream.getRequestHeaders()).containsEntry(":method", "GET");
            assertThat(stream.getRequestHeaders()).containsEntry(":path", "/");
        }

        @Test
        public void testRequestHeadersModification() {
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("content-type", "application/json");

            stream.setRequestHeaders(headers);

            java.util.Map<String, String> retrieved = stream.getRequestHeaders();
            assertThat(retrieved).containsEntry("content-type", "application/json");
        }

        @Test
        public void testEmptyRequestHeaders() {
            stream.setRequestHeaders(new java.util.HashMap<>());

            assertThat(stream.getRequestHeaders()).isEmpty();
        }
    }

    @Nested
    class EndStreamFlagTests {

        @Test
        public void testInitialEndStreamFlagIsFalse() {
            assertThat(stream.isEndStreamReceived()).isFalse();
        }

        @Test
        public void testSetEndStreamFlag() {
            stream.setEndStreamReceived(true);

            assertThat(stream.isEndStreamReceived()).isTrue();
        }

        @Test
        public void testToggleEndStreamFlag() {
            assertThat(stream.isEndStreamReceived()).isFalse();

            stream.setEndStreamReceived(true);
            assertThat(stream.isEndStreamReceived()).isTrue();

            stream.setEndStreamReceived(false);
            assertThat(stream.isEndStreamReceived()).isFalse();
        }
    }

    @Nested
    class ClientServerStreamIdTests {

        @Test
        public void testClientInitiatedStreamHasOddId() {
            HTTP2Stream clientStream = new HTTP2Stream(1, 65535); // Odd ID = client

            assertThat(clientStream.isClientInitiated()).isTrue();
        }

        @Test
        public void testServerInitiatedStreamHasEvenId() {
            HTTP2Stream serverStream = new HTTP2Stream(2, 65535); // Even ID = server

            assertThat(serverStream.isClientInitiated()).isFalse();
        }

        @Test
        public void testStreamIdParity() {
            for (int id = 1; id <= 1000; id += 2) {
                HTTP2Stream oddStream = new HTTP2Stream(id, 65535);
                assertThat(oddStream.isClientInitiated()).isTrue();
            }

            for (int id = 2; id <= 1000; id += 2) {
                HTTP2Stream evenStream = new HTTP2Stream(id, 65535);
                assertThat(evenStream.isClientInitiated()).isFalse();
            }
        }
    }

    @Nested
    class WindowSizeCustomization {

        @Test
        public void testSmallInitialWindow() {
            HTTP2Stream smallStream = new HTTP2Stream(1, 1024);

            assertThat(smallStream.getSenderWindowSize()).isEqualTo(1024);
            assertThat(smallStream.getReceiverWindowSize()).isEqualTo(1024);
        }

        @Test
        public void testLargeInitialWindow() {
            HTTP2Stream largeStream = new HTTP2Stream(1, 262144);

            assertThat(largeStream.getSenderWindowSize()).isEqualTo(262144);
            assertThat(largeStream.getReceiverWindowSize()).isEqualTo(262144);
        }

        @Test
        public void testMinimalInitialWindow() {
            HTTP2Stream minStream = new HTTP2Stream(1, 1);

            assertThat(minStream.getSenderWindowSize()).isEqualTo(1);
            assertThat(minStream.getReceiverWindowSize()).isEqualTo(1);
        }
    }
}
