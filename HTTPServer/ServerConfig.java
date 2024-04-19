package HTTPServer;

import java.io.*;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Manages server configuration from properties file and environment variables.
 * Environment variables take precedence over properties file values.
 */
public class ServerConfig {

    private static final Logger logger = Logger.getLogger(ServerConfig.class.getName());
    private static final String DEFAULT_CONFIG_FILE = "config.properties";

    // Default configuration values
    private static final int DEFAULT_PORT = 8080;
    private static final int DEFAULT_THREAD_POOL_SIZE = 20;
    private static final int DEFAULT_REQUEST_QUEUE_LIMIT = 100;
    private static final int DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 30;
    private static final boolean DEFAULT_TLS_ENABLED = false;
    private static final boolean DEFAULT_COMPRESSION_ENABLED = true;
    private static final boolean DEFAULT_CACHE_ENABLED = true;
    private static final boolean DEFAULT_HTTP_REDIRECT = true;
    private static final boolean DEFAULT_HSTS_ENABLED = true;
    private static final int DEFAULT_HSTS_MAX_AGE = 31536000; // 1 year

    private Properties properties;

    // Configuration fields
    private int defaultPort;
    private int threadPoolSize;
    private int requestQueueLimit;
    private int shutdownTimeoutSeconds;
    private boolean tlsEnabled;
    private String keystorePath;
    private String keystorePassword;
    private String keyPassword;
    private boolean compressionEnabled;
    private boolean cacheEnabled;
    private boolean httpToHttpsRedirect;
    private boolean hstsEnabled;
    private int hstsMaxAge;
    private boolean hstsIncludeSubdomains;

    public ServerConfig() {
        this(DEFAULT_CONFIG_FILE);
    }

    public ServerConfig(String configFilePath) {
        properties = new Properties();
        loadConfiguration(configFilePath);
    }

    private void loadConfiguration(String configFilePath) {
        // Try to load from file
        File configFile = new File(configFilePath);
        if (configFile.exists()) {
            try (FileInputStream fis = new FileInputStream(configFile)) {
                properties.load(fis);
                logger.info("Configuration loaded from: " + configFilePath);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to load config file: " + configFilePath, e);
            }
        } else {
            logger.info("Config file not found: " + configFilePath + ", using defaults and environment variables");
        }

        // Load configuration values (environment variables override file values)
        defaultPort = getIntConfig("server.port", "SERVER_PORT", DEFAULT_PORT);
        threadPoolSize = getIntConfig("thread.pool.size", "THREAD_POOL_SIZE", DEFAULT_THREAD_POOL_SIZE);
        requestQueueLimit = getIntConfig("request.queue.limit", "REQUEST_QUEUE_LIMIT", DEFAULT_REQUEST_QUEUE_LIMIT);
        shutdownTimeoutSeconds = getIntConfig("shutdown.timeout.seconds", "SHUTDOWN_TIMEOUT_SECONDS", DEFAULT_SHUTDOWN_TIMEOUT_SECONDS);

        // TLS Configuration
        tlsEnabled = getBooleanConfig("tls.enabled", "TLS_ENABLED", DEFAULT_TLS_ENABLED);
        keystorePath = getStringConfig("tls.keystore.path", "TLS_KEYSTORE_PATH", null);
        keystorePassword = getStringConfig("tls.keystore.password", "TLS_KEYSTORE_PASSWORD", null);
        keyPassword = getStringConfig("tls.key.password", "TLS_KEY_PASSWORD", keystorePassword);

        // HTTP to HTTPS redirect and HSTS
        httpToHttpsRedirect = getBooleanConfig("tls.http.redirect", "TLS_HTTP_REDIRECT", DEFAULT_HTTP_REDIRECT);
        hstsEnabled = getBooleanConfig("tls.hsts.enabled", "TLS_HSTS_ENABLED", DEFAULT_HSTS_ENABLED);
        hstsMaxAge = getIntConfig("tls.hsts.max.age", "TLS_HSTS_MAX_AGE", DEFAULT_HSTS_MAX_AGE);
        hstsIncludeSubdomains = getBooleanConfig("tls.hsts.include.subdomains", "TLS_HSTS_INCLUDE_SUBDOMAINS", true);

        // Feature flags
        compressionEnabled = getBooleanConfig("compression.enabled", "COMPRESSION_ENABLED", DEFAULT_COMPRESSION_ENABLED);
        cacheEnabled = getBooleanConfig("cache.enabled", "CACHE_ENABLED", DEFAULT_CACHE_ENABLED);

        validateConfiguration();
    }

    private void validateConfiguration() {
        if (tlsEnabled && (keystorePath == null || keystorePath.isEmpty())) {
            logger.warning("TLS is enabled but keystore path is not configured. TLS will be disabled.");
            tlsEnabled = false;
        }

        if (tlsEnabled && (keystorePassword == null || keystorePassword.isEmpty())) {
            logger.warning("TLS is enabled but keystore password is not configured. TLS will be disabled.");
            tlsEnabled = false;
        }

        if (tlsEnabled && keystorePath != null) {
            File keystoreFile = new File(keystorePath);
            if (!keystoreFile.exists()) {
                logger.warning("Keystore file not found: " + keystorePath + ". TLS will be disabled.");
                tlsEnabled = false;
            }
        }
    }

    private String getStringConfig(String propertyKey, String envKey, String defaultValue) {
        // Environment variable takes precedence
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isEmpty()) {
            return envValue;
        }

        // Fall back to properties file
        return properties.getProperty(propertyKey, defaultValue);
    }

    private int getIntConfig(String propertyKey, String envKey, int defaultValue) {
        String value = getStringConfig(propertyKey, envKey, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.warning("Invalid integer value for " + propertyKey + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    private boolean getBooleanConfig(String propertyKey, String envKey, boolean defaultValue) {
        String value = getStringConfig(propertyKey, envKey, String.valueOf(defaultValue));
        return Boolean.parseBoolean(value);
    }

    // Getters
    public int getDefaultPort() {
        return defaultPort;
    }

    public int getThreadPoolSize() {
        return threadPoolSize;
    }

    public int getRequestQueueLimit() {
        return requestQueueLimit;
    }

    public int getShutdownTimeoutSeconds() {
        return shutdownTimeoutSeconds;
    }

    public boolean isTlsEnabled() {
        return tlsEnabled;
    }

    public String getKeystorePath() {
        return keystorePath;
    }

    public String getKeystorePassword() {
        return keystorePassword;
    }

    public String getKeyPassword() {
        return keyPassword;
    }

    public boolean isCompressionEnabled() {
        return compressionEnabled;
    }

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    public boolean isHttpToHttpsRedirect() {
        return httpToHttpsRedirect;
    }

    public boolean isHstsEnabled() {
        return hstsEnabled;
    }

    public int getHstsMaxAge() {
        return hstsMaxAge;
    }

    public boolean isHstsIncludeSubdomains() {
        return hstsIncludeSubdomains;
    }

    public String getHstsHeader() {
        if (!hstsEnabled) {
            return null;
        }

        StringBuilder header = new StringBuilder("max-age=");
        header.append(hstsMaxAge);

        if (hstsIncludeSubdomains) {
            header.append("; includeSubDomains");
        }

        return header.toString();
    }
}
