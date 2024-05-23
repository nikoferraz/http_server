package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

public class HTTP2ConcurrentStreamsTest {

    private ExecutorService executorService;

    @BeforeEach
    public void setUp() {
        executorService = Executors.newFixedThreadPool(10);
    }

    public void tearDown() {
        if (executorService != null) {
            executorService.shutdown();
        }
    }

    @Nested
    class MultipleConcurrentStreamsTests {

        @Test
        public void testTenConcurrentStreams() throws InterruptedException {
            List<HTTP2Stream> streams = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                streams.add(new HTTP2Stream(i * 2 - 1, 65535)); // Odd IDs
            }

            CountDownLatch latch = new CountDownLatch(10);

            for (HTTP2Stream stream : streams) {
                executorService.submit(() -> {
                    stream.open();
                    stream.sendData(new byte[1000]);
                    latch.countDown();
                });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

            for (HTTP2Stream stream : streams) {
                assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.OPEN);
                assertThat(stream.getSenderWindowSize()).isEqualTo(64535);
            }
        }

        @Test
        public void testHundredConcurrentStreams() throws InterruptedException {
            List<HTTP2Stream> streams = new ArrayList<>();
            for (int i = 1; i <= 100; i++) {
                streams.add(new HTTP2Stream(i * 2 - 1, 65535));
            }

            CountDownLatch latch = new CountDownLatch(100);

            for (HTTP2Stream stream : streams) {
                executorService.submit(() -> {
                    stream.open();
                    stream.sendData(new byte[100]);
                    latch.countDown();
                });
            }

            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(streams.stream().filter(s -> s.getState() == HTTP2Stream.StreamState.OPEN).count()).isEqualTo(100);
        }

        @Test
        public void testThousandConcurrentStreams() throws InterruptedException {
            List<HTTP2Stream> streams = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch creationLatch = new CountDownLatch(1000);

            for (int i = 1; i <= 1000; i++) {
                final int id = i;
                executorService.submit(() -> {
                    streams.add(new HTTP2Stream(id * 2 - 1, 65535));
                    creationLatch.countDown();
                });
            }

            assertThat(creationLatch.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(streams).hasSize(1000);
        }
    }

    @Nested
    class InterleavedDataFramesTests {

        @Test
        public void testInterleavedDataFromTwoStreams() throws InterruptedException {
            HTTP2Stream stream1 = new HTTP2Stream(1, 65535);
            HTTP2Stream stream2 = new HTTP2Stream(3, 65535);

            stream1.open();
            stream2.open();

            CountDownLatch latch = new CountDownLatch(2);

            executorService.submit(() -> {
                for (int i = 0; i < 10; i++) {
                    stream1.sendData(new byte[100]);
                }
                latch.countDown();
            });

            executorService.submit(() -> {
                for (int i = 0; i < 10; i++) {
                    stream2.sendData(new byte[100]);
                }
                latch.countDown();
            });

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(stream1.getSenderWindowSize()).isEqualTo(65535 - 1000);
            assertThat(stream2.getSenderWindowSize()).isEqualTo(65535 - 1000);
        }

        @Test
        public void testInterleavedReceiveFromMultipleStreams() throws InterruptedException {
            HTTP2Stream stream1 = new HTTP2Stream(1, 65535);
            HTTP2Stream stream2 = new HTTP2Stream(3, 65535);
            HTTP2Stream stream3 = new HTTP2Stream(5, 65535);

            stream1.open();
            stream2.open();
            stream3.open();

            CountDownLatch latch = new CountDownLatch(3);

            executorService.submit(() -> {
                for (int i = 0; i < 5; i++) {
                    stream1.receiveData(new byte[200]);
                }
                latch.countDown();
            });

            executorService.submit(() -> {
                for (int i = 0; i < 5; i++) {
                    stream2.receiveData(new byte[300]);
                }
                latch.countDown();
            });

            executorService.submit(() -> {
                for (int i = 0; i < 5; i++) {
                    stream3.receiveData(new byte[100]);
                }
                latch.countDown();
            });

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(stream1.getReceiverWindowSize()).isEqualTo(65535 - 1000);
            assertThat(stream2.getReceiverWindowSize()).isEqualTo(65535 - 1500);
            assertThat(stream3.getReceiverWindowSize()).isEqualTo(65535 - 500);
        }
    }

    @Nested
    class FlowControlConcurrencyTests {

        @Test
        public void testConcurrentWindowUpdates() throws InterruptedException {
            HTTP2Stream stream = new HTTP2Stream(1, 65535);
            stream.open();

            int updateCount = 100;
            int updateSize = 1000;
            CountDownLatch latch = new CountDownLatch(updateCount);

            for (int i = 0; i < updateCount; i++) {
                executorService.submit(() -> {
                    stream.updateSenderWindow(updateSize);
                    latch.countDown();
                });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

            // Each update adds updateSize to window
            assertThat(stream.getSenderWindowSize()).isEqualTo(65535 + (updateCount * updateSize));
        }

        @Test
        public void testConcurrentSendAndWindowUpdate() throws InterruptedException {
            HTTP2Stream stream = new HTTP2Stream(1, 65535);
            stream.open();

            CountDownLatch latch = new CountDownLatch(20);

            // 10 threads sending data
            for (int i = 0; i < 10; i++) {
                executorService.submit(() -> {
                    stream.sendData(new byte[1000]);
                    latch.countDown();
                });
            }

            // 10 threads updating window
            for (int i = 0; i < 10; i++) {
                executorService.submit(() -> {
                    stream.updateSenderWindow(1000);
                    latch.countDown();
                });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(stream.getSenderWindowSize()).isEqualTo(65535); // Should balance out
        }

        @Test
        public void testConcurrentReceiveAndWindowUpdate() throws InterruptedException {
            HTTP2Stream stream = new HTTP2Stream(1, 65535);
            stream.open();

            CountDownLatch latch = new CountDownLatch(20);

            // 10 threads receiving data
            for (int i = 0; i < 10; i++) {
                executorService.submit(() -> {
                    stream.receiveData(new byte[1000]);
                    latch.countDown();
                });
            }

            // 10 threads updating window
            for (int i = 0; i < 10; i++) {
                executorService.submit(() -> {
                    stream.updateReceiverWindow(1000);
                    latch.countDown();
                });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(stream.getReceiverWindowSize()).isEqualTo(65535);
        }
    }

    @Nested
    class StreamCreationAndClosureTests {

        @Test
        public void testConcurrentStreamCreation() throws InterruptedException {
            Map<Integer, HTTP2Stream> streams = Collections.synchronizedMap(new HashMap<>());
            CountDownLatch latch = new CountDownLatch(100);

            for (int i = 1; i <= 100; i++) {
                final int id = i;
                executorService.submit(() -> {
                    HTTP2Stream stream = new HTTP2Stream(id * 2 - 1, 65535);
                    streams.put(id, stream);
                    latch.countDown();
                });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(streams).hasSize(100);

            for (HTTP2Stream stream : streams.values()) {
                assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.IDLE);
            }
        }

        @Test
        public void testConcurrentStreamOpening() throws InterruptedException {
            List<HTTP2Stream> streams = new ArrayList<>();
            for (int i = 1; i <= 50; i++) {
                streams.add(new HTTP2Stream(i * 2 - 1, 65535));
            }

            CountDownLatch latch = new CountDownLatch(50);

            for (HTTP2Stream stream : streams) {
                executorService.submit(() -> {
                    stream.open();
                    latch.countDown();
                });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

            for (HTTP2Stream stream : streams) {
                assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.OPEN);
            }
        }

        @Test
        public void testConcurrentStreamClosure() throws InterruptedException {
            List<HTTP2Stream> streams = new ArrayList<>();
            for (int i = 1; i <= 50; i++) {
                HTTP2Stream stream = new HTTP2Stream(i * 2 - 1, 65535);
                stream.open();
                streams.add(stream);
            }

            CountDownLatch latch = new CountDownLatch(50);

            for (HTTP2Stream stream : streams) {
                executorService.submit(() -> {
                    stream.close();
                    latch.countDown();
                });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

            for (HTTP2Stream stream : streams) {
                assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.CLOSED);
            }
        }

        @Test
        public void testConcurrentStreamResets() throws InterruptedException {
            List<HTTP2Stream> streams = new ArrayList<>();
            for (int i = 1; i <= 50; i++) {
                HTTP2Stream stream = new HTTP2Stream(i * 2 - 1, 65535);
                stream.open();
                streams.add(stream);
            }

            CountDownLatch latch = new CountDownLatch(50);

            for (HTTP2Stream stream : streams) {
                executorService.submit(() -> {
                    stream.reset(0);
                    latch.countDown();
                });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

            for (HTTP2Stream stream : streams) {
                assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.CLOSED);
            }
        }
    }

    @Nested
    class StreamPriorityTests {

        @Test
        public void testConcurrentPriorityChanges() throws InterruptedException {
            HTTP2Stream stream = new HTTP2Stream(1, 65535);

            CountDownLatch latch = new CountDownLatch(100);

            for (int p = 0; p < 100; p++) {
                final int priority = p;
                executorService.submit(() -> {
                    stream.setPriority(priority);
                    latch.countDown();
                });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            // Final priority should be one of the values set
            assertThat(stream.getPriority()).isGreaterThanOrEqualTo(0).isLessThan(100);
        }

        @Test
        public void testStreamDependencyInConcurrentContext() throws InterruptedException {
            HTTP2Stream parent = new HTTP2Stream(0, 65535);
            List<HTTP2Stream> children = new ArrayList<>();

            for (int i = 1; i <= 20; i++) {
                children.add(new HTTP2Stream(i * 2 - 1, 65535));
            }

            CountDownLatch latch = new CountDownLatch(20);

            for (HTTP2Stream child : children) {
                executorService.submit(() -> {
                    child.setDependency(parent);
                    latch.countDown();
                });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

            for (HTTP2Stream child : children) {
                assertThat(child.getDependency()).isEqualTo(parent);
            }
        }
    }

    @Nested
    class DataTransmissionStressTests {

        @Test
        public void testRapidDataFramesOnSingleStream() throws InterruptedException {
            HTTP2Stream stream = new HTTP2Stream(1, 65535);
            stream.open();

            CountDownLatch latch = new CountDownLatch(100);
            AtomicInteger totalBytes = new AtomicInteger(0);

            for (int i = 0; i < 100; i++) {
                executorService.submit(() -> {
                    byte[] data = new byte[100];
                    stream.sendData(data);
                    totalBytes.addAndGet(100);
                    latch.countDown();
                });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(totalBytes.get()).isEqualTo(10000);
        }

        @Test
        public void testRapidDataFramesAcrossStreams() throws InterruptedException {
            List<HTTP2Stream> streams = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                HTTP2Stream s = new HTTP2Stream(i * 2 - 1, 65535);
                s.open();
                streams.add(s);
            }

            CountDownLatch latch = new CountDownLatch(100);

            for (int i = 0; i < 100; i++) {
                final int streamIndex = i % 10;
                executorService.submit(() -> {
                    streams.get(streamIndex).sendData(new byte[100]);
                    latch.countDown();
                });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

            for (HTTP2Stream stream : streams) {
                assertThat(stream.getSenderWindowSize()).isEqualTo(65535 - 1000);
            }
        }

        @Test
        public void testDataReceptionUnderLoad() throws InterruptedException {
            HTTP2Stream stream = new HTTP2Stream(1, 65535);
            stream.open();

            CountDownLatch latch = new CountDownLatch(50);

            for (int i = 0; i < 50; i++) {
                executorService.submit(() -> {
                    stream.receiveData(new byte[100]);
                    latch.countDown();
                });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(stream.getReceiverWindowSize()).isEqualTo(65535 - 5000);
        }
    }

    @Nested
    class HeaderManagementConcurrency {

        @Test
        public void testConcurrentHeaderSetting() throws InterruptedException {
            HTTP2Stream stream = new HTTP2Stream(1, 65535);

            CountDownLatch latch = new CountDownLatch(10);

            for (int i = 0; i < 10; i++) {
                final int index = i;
                executorService.submit(() -> {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("header-" + index, "value-" + index);
                    stream.setRequestHeaders(headers);
                    latch.countDown();
                });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(stream.getRequestHeaders()).isNotEmpty();
        }

        @Test
        public void testConcurrentHeaderReading() throws InterruptedException {
            HTTP2Stream stream = new HTTP2Stream(1, 65535);
            Map<String, String> initialHeaders = new HashMap<>();
            initialHeaders.put("x-test", "test-value");
            stream.setRequestHeaders(initialHeaders);

            CountDownLatch latch = new CountDownLatch(10);

            for (int i = 0; i < 10; i++) {
                executorService.submit(() -> {
                    Map<String, String> headers = stream.getRequestHeaders();
                    assertThat(headers).isNotNull();
                    latch.countDown();
                });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Nested
    class StreamStateConsistency {

        @Test
        public void testStateConsistencyAcrossOperations() throws InterruptedException {
            HTTP2Stream stream = new HTTP2Stream(1, 65535);

            CountDownLatch latch = new CountDownLatch(3);

            executorService.submit(() -> {
                stream.open();
                latch.countDown();
            });

            executorService.submit(() -> {
                stream.sendData(new byte[1000]);
                latch.countDown();
            });

            executorService.submit(() -> {
                stream.updateSenderWindow(500);
                latch.countDown();
            });

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(stream.getState()).isEqualTo(HTTP2Stream.StreamState.OPEN);
            assertThat(stream.getSenderWindowSize()).isEqualTo(65035);
        }
    }
}
