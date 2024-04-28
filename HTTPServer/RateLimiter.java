package HTTPServer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.HashSet;
import java.util.Set;

public class RateLimiter {

    private final int requestsPerSecond;
    private final int burstSize;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final Set<String> whitelist = new HashSet<>();
    private final ScheduledExecutorService cleanupExecutor;

    public RateLimiter(int requestsPerSecond, int burstSize) {
        this.requestsPerSecond = requestsPerSecond;
        this.burstSize = burstSize;

        // Schedule periodic cleanup of old entries
        cleanupExecutor = Executors.newScheduledThreadPool(1);
        cleanupExecutor.scheduleAtFixedRate(this::cleanup, 1, 1, TimeUnit.MINUTES);
    }

    public void addToWhitelist(String ip) {
        whitelist.add(ip);
    }

    public void removeFromWhitelist(String ip) {
        whitelist.remove(ip);
    }

    public boolean isWhitelisted(String ip) {
        return whitelist.contains(ip);
    }

    public RateLimitResult tryAcquire(String clientIp) {
        // Check whitelist
        if (isWhitelisted(clientIp)) {
            return new RateLimitResult(true, requestsPerSecond, requestsPerSecond, 0);
        }

        // Get or create bucket for this IP
        TokenBucket bucket = buckets.computeIfAbsent(clientIp, k -> new TokenBucket(requestsPerSecond, burstSize));

        // Try to acquire a token
        boolean allowed = bucket.tryConsume();

        long remaining = bucket.getAvailableTokens();
        long resetTime = bucket.getNextRefillTime();

        return new RateLimitResult(allowed, requestsPerSecond, remaining, resetTime);
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        long maxAge = TimeUnit.MINUTES.toMillis(5); // Remove buckets inactive for 5 minutes

        buckets.entrySet().removeIf(entry -> {
            TokenBucket bucket = entry.getValue();
            return (now - bucket.getLastAccessTime()) > maxAge;
        });
    }

    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static class TokenBucket {
        private final int capacity;
        private final int refillRate;
        private long tokens;
        private long lastRefillTime;
        private long lastAccessTime;

        public TokenBucket(int refillRate, int capacity) {
            this.refillRate = refillRate;
            this.capacity = capacity;
            this.tokens = capacity;
            this.lastRefillTime = System.currentTimeMillis();
            this.lastAccessTime = System.currentTimeMillis();
        }

        public synchronized boolean tryConsume() {
            refill();
            lastAccessTime = System.currentTimeMillis();

            if (tokens > 0) {
                tokens--;
                return true;
            }

            return false;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long elapsedMs = now - lastRefillTime;

            if (elapsedMs > 0) {
                // Refill tokens based on elapsed time
                long tokensToAdd = (elapsedMs * refillRate) / 1000;

                if (tokensToAdd > 0) {
                    tokens = Math.min(capacity, tokens + tokensToAdd);
                    lastRefillTime = now;
                }
            }
        }

        public synchronized long getAvailableTokens() {
            refill();
            return tokens;
        }

        public long getNextRefillTime() {
            long now = System.currentTimeMillis();
            if (tokens >= capacity) {
                return now;
            }

            // Calculate when next token will be available
            long msPerToken = 1000 / refillRate;
            return (now + msPerToken) / 1000; // Return Unix timestamp in seconds
        }

        public long getLastAccessTime() {
            return lastAccessTime;
        }
    }

    public static class RateLimitResult {
        private final boolean allowed;
        private final long limit;
        private final long remaining;
        private final long resetTime;

        public RateLimitResult(boolean allowed, long limit, long remaining, long resetTime) {
            this.allowed = allowed;
            this.limit = limit;
            this.remaining = remaining;
            this.resetTime = resetTime;
        }

        public boolean isAllowed() {
            return allowed;
        }

        public long getLimit() {
            return limit;
        }

        public long getRemaining() {
            return remaining;
        }

        public long getResetTime() {
            return resetTime;
        }

        public long getRetryAfter() {
            if (allowed) {
                return 0;
            }
            long now = System.currentTimeMillis() / 1000;
            return Math.max(1, resetTime - now);
        }
    }
}
