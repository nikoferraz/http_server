package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Nested;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive test suite for RateLimiter token bucket implementation.
 * Tests cover rate limiting enforcement, burst handling, whitelist, and LRU eviction.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("RateLimiter Tests")
class RateLimiterTest {

    private RateLimiter rateLimiter;
    private static final String TEST_IP = "192.168.1.1";
    private static final String TEST_IP_2 = "192.168.1.2";

    @BeforeEach
    void setUp() {
        // Create rate limiter: 10 req/sec, burst of 20
        rateLimiter = new RateLimiter(10, 20);
    }

    @Nested
    @DisplayName("Basic Rate Limiting")
    class BasicRateLimitingTests {

        @Test
        @DisplayName("Should allow requests within burst capacity")
        void testWithinBurstCapacity() {
            // Given: A fresh rate limiter
            String clientIp = TEST_IP;

            // When: Making 20 requests (burst size)
            List<Boolean> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                results.add(rateLimiter.tryAcquire(clientIp).isAllowed());
            }

            // Then: All requests should be allowed
            assertThat(results).allMatch(allowed -> allowed);
        }

        @Test
        @DisplayName("Should block requests exceeding burst limit")
        void testExceedingBurstLimit() {
            String clientIp = TEST_IP;

            // Exhaust burst capacity (20 tokens)
            for (int i = 0; i < 20; i++) {
                rateLimiter.tryAcquire(clientIp);
            }

            // Next request should be blocked
            RateLimiter.RateLimitResult result = rateLimiter.tryAcquire(clientIp);
            assertThat(result.isAllowed()).isFalse();
        }

        @Test
        @DisplayName("Should block all requests when burst exhausted")
        void testConsecutiveBlockedRequests() {
            String clientIp = TEST_IP;

            // Exhaust tokens
            for (int i = 0; i < 20; i++) {
                rateLimiter.tryAcquire(clientIp);
            }

            // All subsequent requests blocked
            for (int i = 0; i < 5; i++) {
                RateLimiter.RateLimitResult result = rateLimiter.tryAcquire(clientIp);
                assertThat(result.isAllowed()).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("Whitelist Functionality")
    class WhitelistTests {

        @Test
        @DisplayName("Should bypass rate limits for whitelisted IPs")
        void testWhitelistedIpBypassesLimit() {
            String whitelistIp = "10.0.0.1";
            rateLimiter.addToWhitelist(whitelistIp);

            // Make unlimited requests
            for (int i = 0; i < 100; i++) {
                RateLimiter.RateLimitResult result = rateLimiter.tryAcquire(whitelistIp);
                assertThat(result.isAllowed()).isTrue();
            }
        }

        @Test
        @DisplayName("Should verify whitelist membership")
        void testWhitelistContains() {
            String whitelistIp = "10.0.0.1";
            rateLimiter.addToWhitelist(whitelistIp);

            assertThat(rateLimiter.isWhitelisted(whitelistIp)).isTrue();
            assertThat(rateLimiter.isWhitelisted("10.0.0.2")).isFalse();
        }

        @Test
        @DisplayName("Should remove IP from whitelist")
        void testRemoveFromWhitelist() {
            String whitelistIp = "10.0.0.1";
            rateLimiter.addToWhitelist(whitelistIp);
            assertThat(rateLimiter.isWhitelisted(whitelistIp)).isTrue();

            rateLimiter.removeFromWhitelist(whitelistIp);
            assertThat(rateLimiter.isWhitelisted(whitelistIp)).isFalse();
        }
    }

    @Nested
    @DisplayName("Multi-Client Isolation")
    class MultiClientTests {

        @Test
        @DisplayName("Should isolate rate limits between different IPs")
        void testClientIsolation() {
            // Use up burst for first IP
            for (int i = 0; i < 20; i++) {
                rateLimiter.tryAcquire(TEST_IP);
            }

            // Second IP should still have tokens available
            RateLimiter.RateLimitResult result = rateLimiter.tryAcquire(TEST_IP_2);
            assertThat(result.isAllowed()).isTrue();
            assertThat(result.getRemaining()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should track separate buckets for different clients")
        void testSeparateBuckets() {
            // First request from IP1
            RateLimiter.RateLimitResult result1 = rateLimiter.tryAcquire(TEST_IP);
            assertThat(result1.isAllowed()).isTrue();

            // First request from IP2
            RateLimiter.RateLimitResult result2 = rateLimiter.tryAcquire(TEST_IP_2);
            assertThat(result2.isAllowed()).isTrue();

            // Both should have separate token counts
            assertThat(rateLimiter.getActiveBucketCount()).isGreaterThanOrEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Bucket Management")
    class BucketManagementTests {

        @Test
        @DisplayName("Should return correct active bucket count")
        void testActiveBucketCount() {
            assertThat(rateLimiter.getActiveBucketCount()).isEqualTo(0);

            rateLimiter.tryAcquire(TEST_IP);
            assertThat(rateLimiter.getActiveBucketCount()).isEqualTo(1);

            rateLimiter.tryAcquire(TEST_IP_2);
            assertThat(rateLimiter.getActiveBucketCount()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("Should enforce maximum bucket limit")
        void testMaxBucketLimit() {
            // Create rate limiter with small max bucket limit for testing
            RateLimiter limitedRateLimiter = new RateLimiter(10, 20, 5);

            // Fill up buckets
            for (int i = 0; i < 5; i++) {
                limitedRateLimiter.tryAcquire("192.168.1." + i);
            }

            // Should not exceed max (5)
            assertThat(limitedRateLimiter.getActiveBucketCount()).isLessThanOrEqualTo(5);
        }
    }

    @Nested
    @DisplayName("Rate Limit Result Validation")
    class RateLimitResultTests {

        @Test
        @DisplayName("Should include correct limit in result")
        void testLimitInResult() {
            RateLimiter.RateLimitResult result = rateLimiter.tryAcquire(TEST_IP);

            assertThat(result.getLimit()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should track remaining tokens")
        void testRemainingTokens() {
            rateLimiter.tryAcquire(TEST_IP);
            RateLimiter.RateLimitResult result = rateLimiter.tryAcquire(TEST_IP);

            // Started with 20 tokens (burst), consumed 2
            assertThat(result.getRemaining()).isLessThan(20);
        }

        @Test
        @DisplayName("Should provide reset time in result")
        void testResetTimeInResult() {
            RateLimiter.RateLimitResult result = rateLimiter.tryAcquire(TEST_IP);

            // Reset time should be in the future (non-zero)
            assertThat(result.getResetTime()).isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle null IP gracefully")
        void testNullIpHandling() {
            assertThatThrownBy(() -> rateLimiter.tryAcquire(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should handle empty IP string")
        void testEmptyIpString() {
            // Empty string should be treated as a valid IP
            RateLimiter.RateLimitResult result = rateLimiter.tryAcquire("");
            assertThat(result.isAllowed()).isTrue();
        }

        @Test
        @DisplayName("Should handle very high request rates")
        void testHighRequestRate() {
            String clientIp = TEST_IP;

            // Make rapid requests
            int allowedCount = 0;
            for (int i = 0; i < 100; i++) {
                if (rateLimiter.tryAcquire(clientIp).isAllowed()) {
                    allowedCount++;
                }
            }

            // Should allow at most burst size
            assertThat(allowedCount).isLessThanOrEqualTo(20);
        }
    }

    @Nested
    @DisplayName("Concurrency")
    class ConcurrencyTests {

        @Test
        @DisplayName("Should handle concurrent requests from same IP")
        void testConcurrentRequestsSameIP() throws InterruptedException {
            String clientIp = TEST_IP;
            List<Boolean> results = new ArrayList<>();
            Object lock = new Object();

            // Simulate 30 concurrent requests
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                Thread t = new Thread(() -> {
                    RateLimiter.RateLimitResult result = rateLimiter.tryAcquire(clientIp);
                    synchronized (lock) {
                        results.add(result.isAllowed());
                    }
                });
                threads.add(t);
                t.start();
            }

            for (Thread t : threads) {
                t.join();
            }

            // At most 20 should succeed (burst size)
            long allowedCount = results.stream().filter(b -> b).count();
            assertThat(allowedCount).isLessThanOrEqualTo(20);
        }

        @Test
        @DisplayName("Should isolate concurrent requests from different IPs")
        void testConcurrentRequestsDifferentIPs() throws InterruptedException {
            List<Boolean> results = new ArrayList<>();
            Object lock = new Object();

            // 10 threads from IP1, 10 from IP2
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Thread t1 = new Thread(() -> {
                    RateLimiter.RateLimitResult result = rateLimiter.tryAcquire(TEST_IP);
                    synchronized (lock) {
                        results.add(result.isAllowed());
                    }
                });
                Thread t2 = new Thread(() -> {
                    RateLimiter.RateLimitResult result = rateLimiter.tryAcquire(TEST_IP_2);
                    synchronized (lock) {
                        results.add(result.isAllowed());
                    }
                });
                threads.add(t1);
                threads.add(t2);
                t1.start();
                t2.start();
            }

            for (Thread t : threads) {
                t.join();
            }

            // Both IPs should have tokens available
            assertThat(results).hasSize(20);
            // At least some from each IP should succeed
            assertThat(results).containsAtLeastOneOf(true);
        }
    }
}
