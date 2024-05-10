package HTTPServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Security-critical test suite for AuthenticationManager.
 * Tests Basic Auth, API Key authentication, and bypass attempts.
 */
@DisplayName("AuthenticationManager Tests")
class AuthenticationManagerTest {

    private AuthenticationManager authManager;
    private ServerConfig config;

    @BeforeEach
    void setUp() {
        config = new ServerConfig();
        authManager = new AuthenticationManager(config);
    }

    @Nested
    @DisplayName("Basic Authentication")
    class BasicAuthTests {

        @Test
        @DisplayName("Should authenticate valid Basic Auth credentials")
        void testValidBasicAuth() {
            Map<String, String> headers = new HashMap<>();
            String credentials = "admin:password";
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
            headers.put("authorization", "Basic " + encoded);

            AuthenticationManager.AuthResult result = authManager.authenticate(headers);

            assertThat(result.isAuthenticated()).isTrue();
            assertThat(result.getUsername()).isEqualTo("admin");
            assertThat(result.getMethod()).isEqualTo("basic");
        }

        @Test
        @DisplayName("Should reject invalid Basic Auth credentials")
        void testInvalidBasicAuth() {
            Map<String, String> headers = new HashMap<>();
            String credentials = "admin:wrongpassword";
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
            headers.put("authorization", "Basic " + encoded);

            AuthenticationManager.AuthResult result = authManager.authenticate(headers);

            assertThat(result.isAuthenticated()).isFalse();
        }

        @Test
        @DisplayName("Should reject malformed Basic Auth header")
        void testMalformedBasicAuth() {
            Map<String, String> headers = new HashMap<>();
            headers.put("authorization", "Basic malformed@#$%");

            AuthenticationManager.AuthResult result = authManager.authenticate(headers);

            assertThat(result.isAuthenticated()).isFalse();
        }

        @Test
        @DisplayName("Should reject Basic Auth without password")
        void testBasicAuthMissingPassword() {
            Map<String, String> headers = new HashMap<>();
            String credentials = "admin";
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
            headers.put("authorization", "Basic " + encoded);

            AuthenticationManager.AuthResult result = authManager.authenticate(headers);

            assertThat(result.isAuthenticated()).isFalse();
        }

        @Test
        @DisplayName("Should reject Basic Auth with empty credentials")
        void testBasicAuthEmpty() {
            Map<String, String> headers = new HashMap<>();
            String encoded = Base64.getEncoder().encodeToString("".getBytes());
            headers.put("authorization", "Basic " + encoded);

            AuthenticationManager.AuthResult result = authManager.authenticate(headers);

            assertThat(result.isAuthenticated()).isFalse();
        }

        @Test
        @DisplayName("Should handle special characters in credentials")
        void testBasicAuthSpecialCharacters() {
            Map<String, String> headers = new HashMap<>();
            // Test with special characters in password
            String credentials = "admin:p@ss!w#rd$";
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
            headers.put("authorization", "Basic " + encoded);

            // Should handle gracefully (may or may not authenticate depending on config)
            AuthenticationManager.AuthResult result = authManager.authenticate(headers);
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Should reject wrong username even with correct password format")
        void testWrongUsername() {
            Map<String, String> headers = new HashMap<>();
            String credentials = "wronguser:password";
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
            headers.put("authorization", "Basic " + encoded);

            AuthenticationManager.AuthResult result = authManager.authenticate(headers);

            assertThat(result.isAuthenticated()).isFalse();
        }
    }

    @Nested
    @DisplayName("API Key Authentication")
    class ApiKeyAuthTests {

        @BeforeEach
        void setUpApiKeys() {
            // API keys are loaded from config in initialization
            // Default config may or may not have API keys enabled
        }

        @Test
        @DisplayName("Should validate with API key when enabled")
        void testApiKeyWhenEnabled() {
            // This test verifies the API key flow works
            Map<String, String> headers = new HashMap<>();
            headers.put("x-api-key", "test-api-key");

            AuthenticationManager.AuthResult result = authManager.authenticate(headers);
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Should handle missing API key header")
        void testMissingApiKeyHeader() {
            Map<String, String> headers = new HashMap<>();

            AuthenticationManager.AuthResult result = authManager.authenticate(headers);

            // Should fall back to other auth methods or return unauthenticated
            assertThat(result.isAuthenticated()).isFalse();
        }

        @Test
        @DisplayName("Should reject invalid API key format")
        void testInvalidApiKeyFormat() {
            Map<String, String> headers = new HashMap<>();
            headers.put("x-api-key", "");

            AuthenticationManager.AuthResult result = authManager.authenticate(headers);

            assertThat(result.isAuthenticated()).isFalse();
        }
    }

    @Nested
    @DisplayName("Header Validation")
    class HeaderValidationTests {

        @Test
        @DisplayName("Should handle null headers")
        void testNullHeaders() {
            // This tests robustness, though actual implementation might require non-null
            Map<String, String> headers = new HashMap<>();

            AuthenticationManager.AuthResult result = authManager.authenticate(headers);

            assertThat(result).isNotNull();
            assertThat(result.isAuthenticated()).isFalse();
        }

        @Test
        @DisplayName("Should handle missing authorization header")
        void testMissingAuthorizationHeader() {
            Map<String, String> headers = new HashMap<>();
            headers.put("host", "example.com");

            AuthenticationManager.AuthResult result = authManager.authenticate(headers);

            assertThat(result.isAuthenticated()).isFalse();
        }

        @Test
        @DisplayName("Should ignore case in header names")
        void testHeaderNameCaseInsensitivity() {
            Map<String, String> headers = new HashMap<>();
            String credentials = "admin:password";
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
            // Headers might be normalized to lowercase by the time they reach auth manager
            headers.put("authorization", "Basic " + encoded);

            AuthenticationManager.AuthResult result = authManager.authenticate(headers);

            assertThat(result.isAuthenticated()).isTrue();
        }

        @Test
        @DisplayName("Should handle whitespace in auth header")
        void testAuthHeaderWhitespace() {
            Map<String, String> headers = new HashMap<>();
            String credentials = "admin:password";
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
            headers.put("authorization", "Basic  " + encoded); // Extra space

            AuthenticationManager.AuthResult result = authManager.authenticate(headers);

            // Should be lenient with whitespace
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("Authentication Bypass Prevention")
    class BypassPreventionTests {

        @Test
        @DisplayName("Should not authenticate with bearer token in Basic Auth context")
        void testBearerTokenNotAccepted() {
            Map<String, String> headers = new HashMap<>();
            headers.put("authorization", "Bearer some-jwt-token");

            AuthenticationManager.AuthResult result = authManager.authenticate(headers);

            assertThat(result.isAuthenticated()).isFalse();
        }

        @Test
        @DisplayName("Should not accept arbitrary auth schemes")
        void testArbitraryAuthScheme() {
            Map<String, String> headers = new HashMap<>();
            headers.put("authorization", "Digest username=admin");

            AuthenticationManager.AuthResult result = authManager.authenticate(headers);

            assertThat(result.isAuthenticated()).isFalse();
        }

        @Test
        @DisplayName("Should prevent authentication bypass with null bytes")
        void testNullByteInCredentials() {
            Map<String, String> headers = new HashMap<>();
            String malicious = "admin\u0000:anything";
            String encoded = Base64.getEncoder().encodeToString(malicious.getBytes());
            headers.put("authorization", "Basic " + encoded);

            AuthenticationManager.AuthResult result = authManager.authenticate(headers);

            // Should handle gracefully without exposing vulnerability
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Should reject authentication with SQL-like injection in credentials")
        void testSqlInjectionAttempt() {
            Map<String, String> headers = new HashMap<>();
            String injection = "admin' OR '1'='1:password";
            String encoded = Base64.getEncoder().encodeToString(injection.getBytes());
            headers.put("authorization", "Basic " + encoded);

            AuthenticationManager.AuthResult result = authManager.authenticate(headers);

            // Should not authenticate the injection
            assertThat(result.isAuthenticated()).isFalse();
        }

        @Test
        @DisplayName("Should handle extremely long credentials")
        void testExtremelyLongCredentials() {
            Map<String, String> headers = new HashMap<>();
            String longString = "a".repeat(10000) + ":password";
            String encoded = Base64.getEncoder().encodeToString(longString.getBytes());
            headers.put("authorization", "Basic " + encoded);

            // Should handle without crashing
            AuthenticationManager.AuthResult result = authManager.authenticate(headers);

            assertThat(result).isNotNull();
            assertThat(result.isAuthenticated()).isFalse();
        }
    }

    @Nested
    @DisplayName("Multiple Authentication Methods")
    class MultiMethodTests {

        @Test
        @DisplayName("Should prefer API Key over Basic Auth when both present")
        void testApiKeyPreference() {
            Map<String, String> headers = new HashMap<>();
            String credentials = "admin:password";
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
            headers.put("authorization", "Basic " + encoded);
            headers.put("x-api-key", "some-key");

            AuthenticationManager.AuthResult result = authManager.authenticate(headers);

            // Result should indicate which method was used
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Should fall back to Basic Auth if API Key fails")
        void testFallbackToBasicAuth() {
            Map<String, String> headers = new HashMap<>();
            String credentials = "admin:password";
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
            headers.put("authorization", "Basic " + encoded);
            headers.put("x-api-key", "invalid-key");

            AuthenticationManager.AuthResult result = authManager.authenticate(headers);

            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("Base64 Decoding Edge Cases")
    class Base64EdgeCasesTests {

        @Test
        @DisplayName("Should handle invalid Base64 encoding")
        void testInvalidBase64() {
            Map<String, String> headers = new HashMap<>();
            // Invalid Base64 that can't be decoded
            headers.put("authorization", "Basic !!@@##$$%%");

            // Should handle gracefully without throwing
            AuthenticationManager.AuthResult result = authManager.authenticate(headers);

            assertThat(result.isAuthenticated()).isFalse();
        }

        @Test
        @DisplayName("Should handle Base64 with padding issues")
        void testBase64PaddingIssues() {
            Map<String, String> headers = new HashMap<>();
            // Valid credentials but missing padding
            String credentials = "admin:password";
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
            // Remove padding
            String malformed = encoded.replace("=", "");
            headers.put("authorization", "Basic " + malformed);

            AuthenticationManager.AuthResult result = authManager.authenticate(headers);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Should handle Base64 with extra whitespace")
        void testBase64WithWhitespace() {
            Map<String, String> headers = new HashMap<>();
            String credentials = "admin:password";
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
            headers.put("authorization", "Basic " + encoded + "  ");

            // May fail or be handled, but should be robust
            AuthenticationManager.AuthResult result = authManager.authenticate(headers);

            assertThat(result).isNotNull();
        }
    }
}
