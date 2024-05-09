package HTTPServer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class AuthenticationManager {

    private final ServerConfig config;
    private final Map<String, String> basicAuthCredentials;
    private final Map<String, String> apiKeys;
    private boolean apiKeyEnabled;

    public static class AuthResult {
        private final boolean authenticated;
        private final String username;
        private final String method;

        public AuthResult(boolean authenticated, String username, String method) {
            this.authenticated = authenticated;
            this.username = username;
            this.method = method;
        }

        public boolean isAuthenticated() {
            return authenticated;
        }

        public String getUsername() {
            return username;
        }

        public String getMethod() {
            return method;
        }
    }

    public AuthenticationManager(ServerConfig config) {
        this.config = config;
        this.basicAuthCredentials = new HashMap<>();
        this.apiKeys = new HashMap<>();
        initializeBasicAuth();
        initializeApiKeys();
    }

    private void initializeBasicAuth() {
        basicAuthCredentials.put("admin", "password");
    }

    private void initializeApiKeys() {
        apiKeyEnabled = config.isApiKeyEnabled();
        String apiKeysConfig = config.getApiKeys();

        if (apiKeysConfig != null && !apiKeysConfig.isEmpty()) {
            String[] keyPairs = apiKeysConfig.split(",");
            for (String keyPair : keyPairs) {
                String[] parts = keyPair.trim().split(":");
                if (parts.length == 2) {
                    String name = parts[0].trim();
                    String key = parts[1].trim();
                    apiKeys.put(key, name);
                }
            }
        }
    }

    public AuthResult authenticate(Map<String, String> headers) {
        String authHeader = headers.get("authorization");
        String apiKeyHeader = headers.get("x-api-key");

        if (apiKeyHeader != null && apiKeyEnabled) {
            AuthResult result = validateApiKey(apiKeyHeader);
            if (result.isAuthenticated()) {
                return result;
            }
        }

        if (authHeader != null && authHeader.startsWith("Basic ")) {
            return validateBasicAuth(authHeader);
        }

        return new AuthResult(false, null, null);
    }

    public AuthResult validateBasicAuth(String authHeader) {
        try {
            String encodedCredentials = authHeader.substring(6);
            String decodedCredentials = new String(
                Base64.getDecoder().decode(encodedCredentials),
                StandardCharsets.UTF_8
            );

            int colonIndex = decodedCredentials.indexOf(':');
            if (colonIndex <= 0) {
                return new AuthResult(false, null, null);
            }

            String username = decodedCredentials.substring(0, colonIndex);
            String password = decodedCredentials.substring(colonIndex + 1);

            String validPassword = basicAuthCredentials.get(username);
            if (validPassword != null && validPassword.equals(password)) {
                return new AuthResult(true, username, "Basic");
            }

            return new AuthResult(false, null, null);
        } catch (IllegalArgumentException e) {
            return new AuthResult(false, null, null);
        }
    }

    private AuthResult validateApiKey(String apiKey) {
        String name = apiKeys.get(apiKey);
        if (name != null) {
            return new AuthResult(true, name, "APIKey");
        }
        return new AuthResult(false, null, null);
    }

    public boolean validateBasicAuthCredentials(String username, String password) {
        String validPassword = basicAuthCredentials.get(username);
        return validPassword != null && validPassword.equals(password);
    }

    public boolean requiresAuthentication(String path) {
        if (path.startsWith("/health/")) {
            return false;
        }
        if (path.equals("/metrics")) {
            return false;
        }
        if (path.equals("/auth/login")) {
            return false;
        }
        return true;
    }

    public boolean isApiKeyEnabled() {
        return apiKeyEnabled;
    }
}
