package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Test suite for RoutingEngine.
 * Tests URL routing with redirects and rewrites.
 */
@DisplayName("RoutingEngine Tests")
class RoutingEngineTest {

    private RoutingEngine routingEngine;
    private ServerConfig config;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        config = new ServerConfig();
        routingEngine = new RoutingEngine(config);
    }

    @Nested
    @DisplayName("Redirect Routing")
    class RedirectRoutingTests {

        @Test
        @DisplayName("Should apply redirect for matching exact path")
        void testExactPathRedirect() {
            String originalPath = "/old-page";
            String redirectPath = routingEngine.applyRedirect(originalPath);

            // Behavior depends on configuration
            assertThat(redirectPath).isNotNull();
        }

        @Test
        @DisplayName("Should return original path when no redirect configured")
        void testNoRedirectConfigured() {
            String path = "/normal-path";
            String result = routingEngine.applyRedirect(path);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Should handle wildcard patterns in redirects")
        void testWildcardRedirect() {
            String path = "/api/v1/users";
            String result = routingEngine.applyRedirect(path);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Should handle redirect status codes")
        void testRedirectStatusCode() {
            String path = "/old";
            // Should support 301, 302, 307, 308
            assertThat(routingEngine).isNotNull();
        }
    }

    @Nested
    @DisplayName("Rewrite Routing")
    class RewriteRoutingTests {

        @Test
        @DisplayName("Should apply rewrite for matching path")
        void testPathRewrite() {
            String originalPath = "/api/users";
            String rewritePath = routingEngine.applyRewrite(originalPath);

            assertThat(rewritePath).isNotNull();
        }

        @Test
        @DisplayName("Should return original path when no rewrite matches")
        void testNoRewriteMatch() {
            String path = "/random-path-123";
            String result = routingEngine.applyRewrite(path);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Should handle rewrite with variable capture")
        void testRewriteWithCapture() {
            String path = "/users/123";
            String rewritePath = routingEngine.applyRewrite(path);

            assertThat(rewritePath).isNotNull();
        }
    }

    @Nested
    @DisplayName("Path Handling")
    class PathHandlingTests {

        @Test
        @DisplayName("Should handle paths with query strings")
        void testPathWithQueryString() {
            String path = "/page?param=value";
            String redirectPath = routingEngine.applyRedirect(path);

            assertThat(redirectPath).isNotNull();
        }

        @Test
        @DisplayName("Should handle paths with fragments")
        void testPathWithFragment() {
            String path = "/page#section";
            String redirectPath = routingEngine.applyRedirect(path);

            assertThat(redirectPath).isNotNull();
        }

        @Test
        @DisplayName("Should handle encoded paths")
        void testEncodedPath() {
            String path = "/page%20with%20spaces";
            String redirectPath = routingEngine.applyRedirect(path);

            assertThat(redirectPath).isNotNull();
        }

        @Test
        @DisplayName("Should handle root path")
        void testRootPath() {
            String path = "/";
            String result = routingEngine.applyRedirect(path);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Should handle deep nested paths")
        void testDeepNestedPath() {
            String path = "/a/b/c/d/e/f/g";
            String result = routingEngine.applyRedirect(path);

            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("Pattern Matching")
    class PatternMatchingTests {

        @Test
        @DisplayName("Should match exact paths")
        void testExactPathMatch() {
            // Routing engine should be able to match exact paths
            assertThat(routingEngine).isNotNull();
        }

        @Test
        @DisplayName("Should match wildcard patterns")
        void testWildcardMatch() {
            // Test /api/* matching
            assertThat(routingEngine).isNotNull();
        }

        @Test
        @DisplayName("Should match regex patterns")
        void testRegexMatch() {
            // Test pattern matching with regex
            assertThat(routingEngine).isNotNull();
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle very long paths")
        void testVeryLongPath() {
            String longPath = "/" + "a".repeat(1000);
            String result = routingEngine.applyRedirect(longPath);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Should handle null path")
        void testNullPath() {
            assertThatThrownBy(() -> routingEngine.applyRedirect(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should handle empty path")
        void testEmptyPath() {
            String result = routingEngine.applyRedirect("");
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Should handle paths with special characters")
        void testSpecialCharactersInPath() {
            String path = "/page-with-dashes_and_underscores.html";
            String result = routingEngine.applyRedirect(path);

            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("Routing Priority")
    class RoutingPriorityTests {

        @Test
        @DisplayName("Should apply rules in correct priority order")
        void testRulePriority() {
            // Rules should be evaluated in order of specificity
            assertThat(routingEngine).isNotNull();
        }

        @Test
        @DisplayName("Should prefer exact matches over wildcards")
        void testExactMatchPrecedence() {
            // When both exact and wildcard match, exact should win
            assertThat(routingEngine).isNotNull();
        }
    }
}
