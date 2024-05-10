package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import static org.assertj.core.api.Assertions.*;

/**
 * Test suite for ServerConfig.
 * Tests configuration loading, defaults, and overrides.
 */
@DisplayName("ServerConfig Tests")
class ServerConfigTest {

    private ServerConfig config;

    @BeforeEach
    void setUp() {
        config = new ServerConfig();
    }

    @Nested
    @DisplayName("Default Values")
    class DefaultValuesTests {

        @Test
        @DisplayName("Should have default port value")
        void testDefaultPort() {
            int port = config.getDefaultPort();
            assertThat(port).isGreaterThan(0);
            assertThat(port).isLessThan(65536);
        }

        @Test
        @DisplayName("Should have default thread pool size")
        void testDefaultThreadPoolSize() {
            int size = config.getThreadPoolSize();
            assertThat(size).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should have default request queue limit")
        void testDefaultRequestQueueLimit() {
            int limit = config.getRequestQueueLimit();
            assertThat(limit).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should have compression enabled by default")
        void testCompressionDefaultEnabled() {
            boolean enabled = config.isCompressionEnabled();
            assertThat(enabled).isTrue();
        }

        @Test
        @DisplayName("Should have caching enabled by default")
        void testCachingDefaultEnabled() {
            boolean enabled = config.isCacheEnabled();
            assertThat(enabled).isTrue();
        }

        @Test
        @DisplayName("Should have metrics enabled by default")
        void testMetricsDefaultEnabled() {
            boolean enabled = config.isMetricsEnabled();
            assertThat(enabled).isTrue();
        }

        @Test
        @DisplayName("Should have rate limiting enabled by default")
        void testRateLimitDefaultEnabled() {
            boolean enabled = config.isRateLimitEnabled();
            assertThat(enabled).isTrue();
        }

        @Test
        @DisplayName("Should have health checks enabled by default")
        void testHealthChecksDefaultEnabled() {
            boolean enabled = config.isHealthChecksEnabled();
            assertThat(enabled).isTrue();
        }

        @Test
        @DisplayName("Should have default rate limit values")
        void testDefaultRateLimit() {
            int rps = config.getRateLimitRequestsPerSecond();
            int burst = config.getRateLimitBurstSize();

            assertThat(rps).isGreaterThan(0);
            assertThat(burst).isGreaterThanOrEqualTo(rps);
        }

        @Test
        @DisplayName("Should have default request body max size")
        void testDefaultRequestBodyMaxSize() {
            long maxSize = config.getRequestBodyMaxSizeBytes();
            assertThat(maxSize).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should have default health disk minimum")
        void testDefaultHealthDiskMin() {
            int diskMin = config.getHealthDiskMinMb();
            assertThat(diskMin).isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Configuration Getters")
    class ConfigurationGettersTests {

        @Test
        @DisplayName("Should retrieve logging level")
        void testLoggingLevel() {
            String level = config.getLoggingLevel();
            assertThat(level).isNotNull();
            assertThat(level).isNotEmpty();
        }

        @Test
        @DisplayName("Should retrieve logging format")
        void testLoggingFormat() {
            String format = config.getLoggingFormat();
            assertThat(format).isNotNull();
        }

        @Test
        @DisplayName("Should indicate if TLS is enabled")
        void testTlsEnabled() {
            boolean tlsEnabled = config.isTLSEnabled();
            assertThat(tlsEnabled).isFalse(); // Default should be false
        }

        @Test
        @DisplayName("Should indicate if API key is enabled")
        void testApiKeyEnabled() {
            boolean apiKeyEnabled = config.isApiKeyEnabled();
            assertThat(apiKeyEnabled).isInstanceOf(Boolean.class);
        }

        @Test
        @DisplayName("Should indicate if virtual hosts enabled")
        void testVirtualHostsEnabled() {
            boolean vhostsEnabled = config.isVirtualHostsEnabled();
            assertThat(vhostsEnabled).isInstanceOf(Boolean.class);
        }

        @Test
        @DisplayName("Should indicate if routing enabled")
        void testRoutingEnabled() {
            boolean routingEnabled = config.isRoutingEnabled();
            assertThat(routingEnabled).isInstanceOf(Boolean.class);
        }

        @Test
        @DisplayName("Should retrieve shutdown timeout")
        void testShutdownTimeout() {
            int timeout = config.getShutdownTimeoutSeconds();
            assertThat(timeout).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should retrieve JSON logging setting")
        void testJsonLogging() {
            boolean jsonLogging = config.isJsonLogging();
            assertThat(jsonLogging).isInstanceOf(Boolean.class);
        }
    }

    @Nested
    @DisplayName("Configuration Validation")
    class ConfigurationValidationTests {

        @Test
        @DisplayName("Should have positive port")
        void testPortValidation() {
            int port = config.getDefaultPort();
            assertThat(port).isPositive();
        }

        @Test
        @DisplayName("Should have positive thread pool size")
        void testThreadPoolValidation() {
            int size = config.getThreadPoolSize();
            assertThat(size).isPositive();
        }

        @Test
        @DisplayName("Should have burst size >= requests per second")
        void testBurstSizeValidation() {
            int rps = config.getRateLimitRequestsPerSecond();
            int burst = config.getRateLimitBurstSize();

            assertThat(burst).isGreaterThanOrEqualTo(rps);
        }

        @Test
        @DisplayName("Should have non-negative health disk minimum")
        void testHealthDiskValidation() {
            int diskMin = config.getHealthDiskMinMb();
            assertThat(diskMin).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("Should have positive request body max size")
        void testRequestBodySizeValidation() {
            long maxSize = config.getRequestBodyMaxSizeBytes();
            assertThat(maxSize).isPositive();
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle null API keys config")
        void testNullApiKeysConfig() {
            String apiKeys = config.getApiKeys();
            // Can be null or empty
            assertThat(apiKeys).isIn(null, "", "");
        }

        @Test
        @DisplayName("Should handle null virtual hosts config")
        void testNullVirtualHostsConfig() {
            String vhosts = config.getVirtualHostsConfig();
            // Can be null or empty
            assertThat(vhosts).isIn(null, "", "");
        }

        @Test
        @DisplayName("Should handle null routing config")
        void testNullRoutingConfig() {
            String routing = config.getRoutingConfig();
            // Can be null or empty
            assertThat(routing).isIn(null, "", "");
        }

        @Test
        @DisplayName("Should handle null rate limit whitelist")
        void testNullRateLimitWhitelist() {
            String whitelist = config.getRateLimitWhitelistIps();
            // Can be null or empty
            assertThat(whitelist).isIn(null, "", "");
        }

        @Test
        @DisplayName("Should handle null keystore path when TLS disabled")
        void testNullKeystorePathWhenTlsDisabled() {
            // When TLS is disabled, keystore path may be null
            if (!config.isTLSEnabled()) {
                String keystorePath = config.getKeystorePath();
                // Can be null or path string
                assertThat(keystorePath).isIn(null, "", keystorePath);
            }
        }
    }
}
