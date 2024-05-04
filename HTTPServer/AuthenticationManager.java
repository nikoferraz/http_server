package HTTPServer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AuthenticationManager {

    private final ServerConfig config;
    private final Map<String, String> basicAuthCredentials;
    private final Map<String, String> apiKeys;
    private final ConcurrentHashMap<String, TokenInfo> activeTokens;

    private boolean jwtEnabled;
    private boolean apiKeyEnabled;
    private String jwtSecret;
    private long jwtExpirationMs;

    private static class TokenInfo {
        String username;
        long expirationTime;

        TokenInfo(String username, long expirationTime) {
            this.username = username;
            this.expirationTime = expirationTime;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expirationTime;
        }
    }

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
        this.activeTokens = new ConcurrentHashMap<>();

        initializeBasicAuth();
        initializeJWT();
        initializeApiKeys();
    }

    private void initializeBasicAuth() {
        basicAuthCredentials.put("admin", "password");
    }

    private void initializeJWT() {
        jwtEnabled = config.isJwtEnabled();
        jwtSecret = config.getJwtSecret();
        jwtExpirationMs = config.getJwtExpirationMinutes() * 60 * 1000L;
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

        if (authHeader != null && authHeader.startsWith("Bearer ") && jwtEnabled) {
            String token = authHeader.substring(7);
            AuthResult result = validateJWT(token);
            if (result.isAuthenticated()) {
                return result;
            }
        }

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

    private AuthResult validateBasicAuth(String authHeader) {
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

    private AuthResult validateJWT(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return new AuthResult(false, null, null);
            }

            String headerEncoded = parts[0];
            String payloadEncoded = parts[1];
            String signatureEncoded = parts[2];

            String expectedSignature = generateSignature(headerEncoded, payloadEncoded);
            if (!signatureEncoded.equals(expectedSignature)) {
                return new AuthResult(false, null, null);
            }

            String payloadJson = new String(
                Base64.getUrlDecoder().decode(payloadEncoded),
                StandardCharsets.UTF_8
            );

            String username = extractJsonValue(payloadJson, "sub");
            String expStr = extractJsonValue(payloadJson, "exp");

            if (username == null || expStr == null) {
                return new AuthResult(false, null, null);
            }

            long exp = Long.parseLong(expStr);
            long currentTime = System.currentTimeMillis() / 1000;

            if (currentTime > exp) {
                return new AuthResult(false, null, null);
            }

            return new AuthResult(true, username, "Bearer");
        } catch (Exception e) {
            return new AuthResult(false, null, null);
        }
    }

    public String generateJWT(String username) {
        if (!jwtEnabled) {
            return null;
        }

        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        long exp = (System.currentTimeMillis() + jwtExpirationMs) / 1000;
        String payload = String.format("{\"sub\":\"%s\",\"exp\":%d,\"iat\":%d}",
            username, exp, System.currentTimeMillis() / 1000);

        String headerEncoded = base64UrlEncode(header.getBytes(StandardCharsets.UTF_8));
        String payloadEncoded = base64UrlEncode(payload.getBytes(StandardCharsets.UTF_8));

        String signature = generateSignature(headerEncoded, payloadEncoded);

        return headerEncoded + "." + payloadEncoded + "." + signature;
    }

    private String generateSignature(String headerEncoded, String payloadEncoded) {
        try {
            String data = headerEncoded + "." + payloadEncoded;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            String dataWithSecret = data + jwtSecret;
            byte[] hash = digest.digest(dataWithSecret.getBytes(StandardCharsets.UTF_8));

            return base64UrlEncode(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String base64UrlEncode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex == -1) {
            return null;
        }

        int colonIndex = json.indexOf(":", keyIndex);
        if (colonIndex == -1) {
            return null;
        }

        int startIndex = colonIndex + 1;
        while (startIndex < json.length() &&
               (json.charAt(startIndex) == ' ' || json.charAt(startIndex) == '"')) {
            startIndex++;
        }

        int endIndex = startIndex;
        while (endIndex < json.length() &&
               json.charAt(endIndex) != ',' &&
               json.charAt(endIndex) != '}' &&
               json.charAt(endIndex) != '"') {
            endIndex++;
        }

        if (startIndex >= json.length() || endIndex > json.length()) {
            return null;
        }

        return json.substring(startIndex, endIndex).trim();
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

    public void cleanupExpiredTokens() {
        activeTokens.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    public boolean isJwtEnabled() {
        return jwtEnabled;
    }

    public boolean isApiKeyEnabled() {
        return apiKeyEnabled;
    }
}
