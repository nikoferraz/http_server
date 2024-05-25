package HTTPServer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Manages Server-Sent Events broadcasting and topic subscriptions.
 *
 * Features:
 * - Broadcast events to multiple clients
 * - Topic-based subscriptions (e.g., "/events/news")
 * - Connection registration and cleanup
 * - Memory limits per topic
 * - Statistics and metrics
 *
 * Thread-safe singleton pattern.
 */
public class SSEManager {

    private static final Logger logger = Logger.getLogger(SSEManager.class.getName());
    private static volatile SSEManager instance;
    private static final Object instanceLock = new Object();

    // Connection tracking by topic
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SSEConnection>> topicConnections;

    // Connection to topic mappings for cleanup
    private final ConcurrentHashMap<String, Set<String>> connectionTopics;

    // Configuration limits
    private final int maxConnectionsPerTopic;
    private final int maxConnectionsPerIp;

    // Statistics
    private final AtomicLong totalConnectionsClosed;
    private final ConcurrentHashMap<String, AtomicLong> topicEventCounts;

    private final MetricsCollector metrics;

    private static final int DEFAULT_MAX_CONNECTIONS_PER_TOPIC = 1000;
    private static final int DEFAULT_MAX_CONNECTIONS_PER_IP = 10;

    private SSEManager() {
        this(DEFAULT_MAX_CONNECTIONS_PER_TOPIC, DEFAULT_MAX_CONNECTIONS_PER_IP);
    }

    private SSEManager(int maxConnectionsPerTopic, int maxConnectionsPerIp) {
        this.topicConnections = new ConcurrentHashMap<>();
        this.connectionTopics = new ConcurrentHashMap<>();
        this.maxConnectionsPerTopic = maxConnectionsPerTopic;
        this.maxConnectionsPerIp = maxConnectionsPerIp;
        this.totalConnectionsClosed = new AtomicLong(0);
        this.topicEventCounts = new ConcurrentHashMap<>();
        this.metrics = MetricsCollector.getInstance();
    }

    /**
     * Gets the singleton instance of SSEManager.
     */
    public static SSEManager getInstance() {
        if (instance == null) {
            synchronized (instanceLock) {
                if (instance == null) {
                    instance = new SSEManager();
                }
            }
        }
        return instance;
    }

    /**
     * Registers a connection for a topic.
     * Returns false if limits are exceeded.
     *
     * @param topic the topic path (e.g., "/events/news")
     * @param connection the SSE connection
     * @return true if successfully registered, false if limits exceeded
     */
    public boolean registerConnection(String topic, SSEConnection connection) {
        if (topic == null || topic.isEmpty() || connection == null) {
            throw new IllegalArgumentException("Topic and connection cannot be null or empty");
        }

        // Check IP-based connection limit
        long connectionCount = topicConnections.values().stream()
            .flatMap(CopyOnWriteArrayList::stream)
            .filter(conn -> conn.getClientIp().equals(connection.getClientIp()))
            .count();

        if (connectionCount >= maxConnectionsPerIp) {
            logger.fine("Max connections per IP exceeded for " + connection.getClientIp());
            if (metrics != null) {
                metrics.incrementCounter("sse_connection_limit_exceeded_ip");
            }
            return false;
        }

        // Check topic-based connection limit
        CopyOnWriteArrayList<SSEConnection> connections =
            topicConnections.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>());

        if (connections.size() >= maxConnectionsPerTopic) {
            logger.fine("Max connections per topic exceeded for " + topic);
            if (metrics != null) {
                metrics.incrementCounter("sse_connection_limit_exceeded_topic");
            }
            return false;
        }

        // Register connection
        connections.add(connection);
        connectionTopics.computeIfAbsent(connection.getConnectionId(), k -> ConcurrentHashMap.newKeySet())
            .add(topic);

        if (metrics != null) {
            metrics.incrementGauge("sse_topic_subscriptions");
        }

        logger.fine("Registered connection " + connection.getConnectionId() + " for topic " + topic);
        return true;
    }

    /**
     * Unregisters a connection from all topics.
     *
     * @param connection the SSE connection
     */
    public void unregisterConnection(SSEConnection connection) {
        if (connection == null) {
            return;
        }

        String connectionId = connection.getConnectionId();
        Set<String> topics = connectionTopics.remove(connectionId);

        if (topics != null) {
            for (String topic : topics) {
                CopyOnWriteArrayList<SSEConnection> connections = topicConnections.get(topic);
                if (connections != null) {
                    connections.remove(connection);
                    if (connections.isEmpty()) {
                        topicConnections.remove(topic);
                    }

                    if (metrics != null) {
                        metrics.decrementGauge("sse_topic_subscriptions");
                    }
                }
            }
        }

        totalConnectionsClosed.incrementAndGet();
        logger.fine("Unregistered connection " + connectionId);
    }

    /**
     * Broadcasts an event to all connections on a topic.
     *
     * @param topic the topic path
     * @param event the SSE event
     * @return number of connections that received the event
     */
    public int broadcast(String topic, SSEEvent event) {
        if (topic == null || event == null) {
            throw new IllegalArgumentException("Topic and event cannot be null");
        }

        CopyOnWriteArrayList<SSEConnection> connections = topicConnections.get(topic);
        if (connections == null || connections.isEmpty()) {
            logger.fine("No connections for topic " + topic);
            return 0;
        }

        int sent = 0;
        int failed = 0;

        for (SSEConnection connection : connections) {
            try {
                if (connection.isOpen()) {
                    connection.sendEvent(event);
                    sent++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                logger.log(Level.FINE, "Error broadcasting to connection", e);
                failed++;
            }
        }

        // Clean up closed connections
        if (failed > 0) {
            connections.removeIf(conn -> !conn.isOpen());
        }

        topicEventCounts.computeIfAbsent(topic, k -> new AtomicLong(0)).incrementAndGet();

        if (metrics != null) {
            metrics.incrementCounter("sse_broadcasts");
            metrics.observeHistogram("sse_broadcast_recipients", sent);
        }

        logger.fine("Broadcast to topic " + topic + ": " + sent + " recipients");
        return sent;
    }

    /**
     * Broadcasts an event to multiple topics.
     *
     * @param topics list of topic paths
     * @param event the SSE event
     * @return total number of connections that received the event
     */
    public int broadcastToTopics(List<String> topics, SSEEvent event) {
        int total = 0;
        for (String topic : topics) {
            total += broadcast(topic, event);
        }
        return total;
    }

    /**
     * Broadcasts an event to all active connections (all topics).
     *
     * @param event the SSE event
     * @return total number of connections that received the event
     */
    public int broadcastToAll(SSEEvent event) {
        return broadcastToTopics(new ArrayList<>(topicConnections.keySet()), event);
    }

    /**
     * Gets all active topics.
     *
     * @return set of active topic paths
     */
    public Set<String> getActiveTopics() {
        return topicConnections.keySet();
    }

    /**
     * Gets active connections for a topic.
     *
     * @param topic the topic path
     * @return list of connections, or empty list if topic doesn't exist
     */
    public List<SSEConnection> getConnections(String topic) {
        CopyOnWriteArrayList<SSEConnection> connections = topicConnections.get(topic);
        return connections != null ? new ArrayList<>(connections) : Collections.emptyList();
    }

    /**
     * Gets the number of active connections for a topic.
     *
     * @param topic the topic path
     * @return number of connections
     */
    public int getConnectionCount(String topic) {
        CopyOnWriteArrayList<SSEConnection> connections = topicConnections.get(topic);
        return connections != null ? connections.size() : 0;
    }

    /**
     * Gets the total number of active connections across all topics.
     *
     * @return total connection count
     */
    public int getTotalConnections() {
        return (int) topicConnections.values().stream()
            .mapToLong(CopyOnWriteArrayList::size)
            .sum();
    }

    /**
     * Gets the number of unique topics with active connections.
     *
     * @return number of topics
     */
    public int getTopicCount() {
        return topicConnections.size();
    }

    /**
     * Gets statistics about SSE manager state.
     *
     * @return map of statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total_connections", getTotalConnections());
        stats.put("active_topics", getTopicCount());
        stats.put("total_connections_closed", totalConnectionsClosed.get());
        stats.put("max_connections_per_topic", maxConnectionsPerTopic);
        stats.put("max_connections_per_ip", maxConnectionsPerIp);

        // Topic-specific stats
        Map<String, Object> topicStats = new LinkedHashMap<>();
        for (String topic : getActiveTopics()) {
            Map<String, Object> topicStat = new LinkedHashMap<>();
            topicStat.put("connections", getConnectionCount(topic));
            topicStat.put("events_sent", topicEventCounts.getOrDefault(topic, new AtomicLong(0)).get());
            topicStats.put(topic, topicStat);
        }
        stats.put("topics", topicStats);

        return stats;
    }

    /**
     * Closes all connections (for graceful shutdown).
     */
    public void closeAllConnections() {
        List<SSEConnection> allConnections = topicConnections.values().stream()
            .flatMap(CopyOnWriteArrayList::stream)
            .collect(Collectors.toList());

        for (SSEConnection connection : allConnections) {
            try {
                connection.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing connection during shutdown", e);
            }
        }

        topicConnections.clear();
        connectionTopics.clear();

        logger.info("All SSE connections closed");
    }

    // Test/configuration methods
    public static void resetInstance() {
        instance = null;
    }
}
