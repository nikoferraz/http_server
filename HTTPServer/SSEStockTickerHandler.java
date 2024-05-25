package HTTPServer;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Example SSE handler that simulates stock price updates.
 *
 * Sends simulated stock price updates to all connected clients.
 * Stocks: AAPL, GOOGL, MSFT, AMZN, TSLA
 */
public class SSEStockTickerHandler implements SSEHandler {

    private static final Logger logger = Logger.getLogger(SSEStockTickerHandler.class.getName());

    private static class Stock {
        String symbol;
        double price;
        double change;

        Stock(String symbol, double initialPrice) {
            this.symbol = symbol;
            this.price = initialPrice;
            this.change = 0.0;
        }

        void update() {
            double changePercent = (ThreadLocalRandom.current().nextDouble() - 0.5) * 2; // -1 to +1
            double adjustment = price * changePercent / 100;
            price += adjustment;
            change = (adjustment / (price - adjustment)) * 100;
        }
    }

    private final Map<String, Stock> stocks;
    private final CopyOnWriteArrayList<SSEConnection> connections = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;
    private Thread tickerThread;

    public SSEStockTickerHandler() {
        stocks = new LinkedHashMap<>();
        stocks.put("AAPL", new Stock("AAPL", 195.50));
        stocks.put("GOOGL", new Stock("GOOGL", 140.80));
        stocks.put("MSFT", new Stock("MSFT", 380.50));
        stocks.put("AMZN", new Stock("AMZN", 175.25));
        stocks.put("TSLA", new Stock("TSLA", 248.75));

        startTickerThread();
    }

    private void startTickerThread() {
        tickerThread = Thread.ofVirtual().start(() -> {
            try {
                long eventId = 0;
                while (running) {
                    Thread.sleep(2000); // Update every 2 seconds

                    // Update all stock prices
                    for (Stock stock : stocks.values()) {
                        stock.update();
                    }

                    // Send update to all clients
                    for (Stock stock : stocks.values()) {
                        String data = String.format(
                            "{\"symbol\":\"%s\",\"price\":%.2f,\"change\":%.2f}",
                            stock.symbol, stock.price, stock.change
                        );

                        SSEEvent event = new SSEEvent(
                            data,
                            "price_update",
                            String.valueOf(++eventId)
                        );

                        for (SSEConnection connection : connections) {
                            if (connection.isOpen()) {
                                try {
                                    connection.sendEvent(event);
                                } catch (IllegalStateException e) {
                                    logger.log(Level.FINE, "Connection closed, skipping price update");
                                }
                            }
                        }
                    }

                    // Clean up closed connections
                    connections.removeIf(conn -> !conn.isOpen());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.fine("Ticker thread interrupted");
            }
        });
    }

    @Override
    public void onOpen(SSEConnection connection) {
        connections.add(connection);
        logger.fine("Stock ticker client connected from " + connection.getClientIp() +
                   ", total clients: " + connections.size());

        // Send initial stock data
        for (Stock stock : stocks.values()) {
            String data = String.format(
                "{\"symbol\":\"%s\",\"price\":%.2f,\"change\":0.0,\"initial\":true}",
                stock.symbol, stock.price
            );
            SSEEvent event = new SSEEvent(data, "price_initial");
            try {
                connection.sendEvent(event);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error sending initial stock data", e);
            }
        }
    }

    @Override
    public void onClose(SSEConnection connection) {
        connections.remove(connection);
        logger.fine("Stock ticker client disconnected, remaining clients: " + connections.size());
    }

    @Override
    public void onError(SSEConnection connection, Throwable error) {
        logger.log(Level.WARNING, "Stock ticker handler error for " + connection.getClientIp(), error);
    }

    public void shutdown() {
        running = false;
        if (tickerThread != null) {
            tickerThread.interrupt();
        }
    }

    public int getConnectionCount() {
        return connections.size();
    }

    public Map<String, Double> getCurrentPrices() {
        Map<String, Double> prices = new LinkedHashMap<>();
        for (Stock stock : stocks.values()) {
            prices.put(stock.symbol, stock.price);
        }
        return prices;
    }
}
