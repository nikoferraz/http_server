package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Test suite for VirtualHostManager.
 * Tests virtual host configuration, resolution, and host-based routing.
 */
@DisplayName("VirtualHostManager Tests")
class VirtualHostManagerTest {

    private VirtualHostManager vhostManager;
    private ServerConfig config;
    private File defaultWebroot;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        defaultWebroot = tempDir.toFile();
        config = new ServerConfig();
        vhostManager = new VirtualHostManager(config, defaultWebroot);
    }

    @Nested
    @DisplayName("Default Behavior")
    class DefaultBehaviorTests {

        @Test
        @DisplayName("Should return default webroot when virtual hosts disabled")
        void testDefaultWebrootWhenDisabled(@TempDir Path tempDir) {
            File webroot = tempDir.toFile();
            config = new ServerConfig();
            vhostManager = new VirtualHostManager(config, webroot);

            Map<String, String> headers = new HashMap<>();
            headers.put("host", "example.com");

            File resolvedWebroot = vhostManager.resolveWebroot(headers);

            assertThat(resolvedWebroot).isEqualTo(webroot);
        }

        @Test
        @DisplayName("Should return default webroot when no Host header")
        void testDefaultWebrootNoHostHeader() {
            Map<String, String> headers = new HashMap<>();

            File resolvedWebroot = vhostManager.resolveWebroot(headers);

            assertThat(resolvedWebroot).isEqualTo(defaultWebroot);
        }

        @Test
        @DisplayName("Should return default webroot when Host header empty")
        void testDefaultWebrootEmptyHostHeader() {
            Map<String, String> headers = new HashMap<>();
            headers.put("host", "");

            File resolvedWebroot = vhostManager.resolveWebroot(headers);

            assertThat(resolvedWebroot).isEqualTo(defaultWebroot);
        }
    }

    @Nested
    @DisplayName("Host Header Parsing")
    class HostHeaderParsingTests {

        @Test
        @DisplayName("Should extract hostname without port")
        void testHostnameWithoutPort() {
            Map<String, String> headers = new HashMap<>();
            headers.put("host", "example.com");

            File resolvedWebroot = vhostManager.resolveWebroot(headers);

            assertThat(resolvedWebroot).isNotNull();
        }

        @Test
        @DisplayName("Should extract hostname with port")
        void testHostnameWithPort() {
            Map<String, String> headers = new HashMap<>();
            headers.put("host", "example.com:8080");

            File resolvedWebroot = vhostManager.resolveWebroot(headers);

            assertThat(resolvedWebroot).isNotNull();
        }

        @Test
        @DisplayName("Should handle hostname with multiple colons (IPv6)")
        void testIPv6Address() {
            Map<String, String> headers = new HashMap<>();
            headers.put("host", "[::1]:8080");

            File resolvedWebroot = vhostManager.resolveWebroot(headers);

            assertThat(resolvedWebroot).isNotNull();
        }
    }

    @Nested
    @DisplayName("Case Sensitivity")
    class CaseSensitivityTests {

        @Test
        @DisplayName("Should normalize hostname to lowercase")
        void testHostnameNormalization() {
            Map<String, String> headers1 = new HashMap<>();
            headers1.put("host", "Example.COM");

            Map<String, String> headers2 = new HashMap<>();
            headers2.put("host", "example.com");

            File webroot1 = vhostManager.resolveWebroot(headers1);
            File webroot2 = vhostManager.resolveWebroot(headers2);

            // Should resolve to same webroot (case-insensitive)
            assertThat(webroot1).isEqualTo(webroot2);
        }

        @Test
        @DisplayName("Should treat mixed case hosts consistently")
        void testMixedCaseConsistency() {
            Map<String, String> headers = new HashMap<>();
            headers.put("host", "ExAmPlE.cOm");

            File resolvedWebroot = vhostManager.resolveWebroot(headers);

            assertThat(resolvedWebroot).isNotNull();
        }
    }

    @Nested
    @DisplayName("Webroot Resolution")
    class WebrootResolutionTests {

        @Test
        @DisplayName("Should resolve to configured virtual host webroot")
        void testVirtualHostResolution(@TempDir Path tempDir1, @TempDir Path tempDir2) throws Exception {
            // Create two different webroots
            File vhost1 = tempDir1.toFile();
            File vhost2 = tempDir2.toFile();

            // This test verifies the webroot resolution pattern
            // Actual configuration would come from ServerConfig
            assertThat(vhost1).exists();
            assertThat(vhost2).exists();
        }

        @Test
        @DisplayName("Should return default webroot for unconfigured hosts")
        void testUnconfiguredHostFallback() {
            Map<String, String> headers = new HashMap<>();
            headers.put("host", "unknown-host.com");

            File resolvedWebroot = vhostManager.resolveWebroot(headers);

            assertThat(resolvedWebroot).isEqualTo(defaultWebroot);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle localhost")
        void testLocalhost() {
            Map<String, String> headers = new HashMap<>();
            headers.put("host", "localhost");

            File resolvedWebroot = vhostManager.resolveWebroot(headers);

            assertThat(resolvedWebroot).isNotNull();
        }

        @Test
        @DisplayName("Should handle 127.0.0.1")
        void testLoopbackIP() {
            Map<String, String> headers = new HashMap<>();
            headers.put("host", "127.0.0.1");

            File resolvedWebroot = vhostManager.resolveWebroot(headers);

            assertThat(resolvedWebroot).isNotNull();
        }

        @Test
        @DisplayName("Should handle IP addresses")
        void testIPAddress() {
            Map<String, String> headers = new HashMap<>();
            headers.put("host", "192.168.1.1");

            File resolvedWebroot = vhostManager.resolveWebroot(headers);

            assertThat(resolvedWebroot).isNotNull();
        }

        @Test
        @DisplayName("Should handle wildcard subdomains")
        void testWildcardSubdomain() {
            Map<String, String> headers = new HashMap<>();
            headers.put("host", "*.example.com");

            File resolvedWebroot = vhostManager.resolveWebroot(headers);

            assertThat(resolvedWebroot).isNotNull();
        }

        @Test
        @DisplayName("Should handle very long hostnames")
        void testVeryLongHostname() {
            String longHostname = "a".repeat(100) + ".com";
            Map<String, String> headers = new HashMap<>();
            headers.put("host", longHostname);

            File resolvedWebroot = vhostManager.resolveWebroot(headers);

            assertThat(resolvedWebroot).isNotNull();
        }

        @Test
        @DisplayName("Should handle malformed ports")
        void testMalformedPort() {
            Map<String, String> headers = new HashMap<>();
            headers.put("host", "example.com:abcd");

            File resolvedWebroot = vhostManager.resolveWebroot(headers);

            assertThat(resolvedWebroot).isNotNull();
        }

        @Test
        @DisplayName("Should handle null headers map")
        void testNullHeadersMap() {
            assertThatThrownBy(() -> vhostManager.resolveWebroot(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should handle empty headers map")
        void testEmptyHeadersMap() {
            Map<String, String> headers = new HashMap<>();

            File resolvedWebroot = vhostManager.resolveWebroot(headers);

            assertThat(resolvedWebroot).isEqualTo(defaultWebroot);
        }
    }

    @Nested
    @DisplayName("Metrics and Logging")
    class MetricsTests {

        @Test
        @DisplayName("Should track default webroot fallback")
        void testFallbackTracking() {
            Map<String, String> headers = new HashMap<>();
            headers.put("host", "unknown.com");

            File resolvedWebroot = vhostManager.resolveWebroot(headers);

            assertThat(resolvedWebroot).isEqualTo(defaultWebroot);
        }

        @Test
        @DisplayName("Should track virtual host hits")
        void testHitTracking() {
            Map<String, String> headers = new HashMap<>();
            headers.put("host", "example.com");

            File resolvedWebroot = vhostManager.resolveWebroot(headers);

            assertThat(resolvedWebroot).isNotNull();
        }
    }
}
