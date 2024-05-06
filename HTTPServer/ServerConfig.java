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

    // Phase 5: Observability defaults
    private static final boolean DEFAULT_METRICS_ENABLED = true;
    private static final String DEFAULT_LOGGING_FORMAT = "json";
    private static final String DEFAULT_LOGGING_LEVEL = "INFO";
    private static final boolean DEFAULT_TRACING_ENABLED = false;

    // Phase 5: Health checks defaults
    private static final boolean DEFAULT_HEALTH_CHECKS_ENABLED = true;
    private static final int DEFAULT_HEALTH_DISK_MIN_MB = 100;

    // Phase 5: Request body defaults
    private static final int DEFAULT_REQUEST_BODY_MAX_SIZE_MB = 10;
    private static final boolean DEFAULT_REQUEST_BODY_JSON_ENABLED = true;
    private static final boolean DEFAULT_REQUEST_BODY_FORM_ENABLED = true;
    private static final boolean DEFAULT_REQUEST_BODY_MULTIPART_ENABLED = true;

    // Phase 5: Rate limiting defaults
    private static final boolean DEFAULT_RATE_LIMIT_ENABLED = true;
    private static final int DEFAULT_RATE_LIMIT_REQUESTS_PER_SECOND = 100;
    private static final int DEFAULT_RATE_LIMIT_BURST_SIZE = 20;

    // Phase 6: Authentication defaults
    private static final boolean DEFAULT_APIKEY_ENABLED = true;

    // Phase 6: Virtual hosts defaults
    private static final boolean DEFAULT_VHOSTS_ENABLED = false;

    // Phase 6: Routing defaults
    private static final boolean DEFAULT_ROUTING_ENABLED = false;

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

    // Phase 5: Observability fields
    private boolean metricsEnabled;
    private String loggingFormat;
    private String loggingLevel;
    private boolean tracingEnabled;
    private String tracingOtlpEndpoint;

    // Phase 5: Health checks fields
    private boolean healthChecksEnabled;
    private int healthDiskMinMb;

    // Phase 5: Request body fields
    private int requestBodyMaxSizeMb;
    private boolean requestBodyJsonEnabled;
    private boolean requestBodyFormEnabled;
    private boolean requestBodyMultipartEnabled;

    // Phase 5: Rate limiting fields
    private boolean rateLimitEnabled;
    private int rateLimitRequestsPerSecond;
    private int rateLimitBurstSize;
    private String rateLimitWhitelistIps;

    // Phase 6: Authentication fields
    private boolean apiKeyEnabled;
    private String apiKeys;

    // Phase 6: Virtual hosts fields
    private boolean virtualHostsEnabled;
    private String virtualHostsConfig;
    private String virtualHostsDefault;

    // Phase 6: Routing fields
    private boolean routingEnabled;
    private String redirectsConfig;
    private String rewritesConfig;
    private String errorPagesConfig;

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

        // Phase 5: Observability configuration
        metricsEnabled = getBooleanConfig("metrics.enabled", "METRICS_ENABLED", DEFAULT_METRICS_ENABLED);
        loggingFormat = getStringConfig("logging.format", "LOGGING_FORMAT", DEFAULT_LOGGING_FORMAT);
        loggingLevel = getStringConfig("logging.level", "LOGGING_LEVEL", DEFAULT_LOGGING_LEVEL);
        tracingEnabled = getBooleanConfig("tracing.enabled", "TRACING_ENABLED", DEFAULT_TRACING_ENABLED);
        tracingOtlpEndpoint = getStringConfig("tracing.otlp.endpoint", "TRACING_OTLP_ENDPOINT", null);

        // Phase 5: Health checks configuration
        healthChecksEnabled = getBooleanConfig("health.checks.enabled", "HEALTH_CHECKS_ENABLED", DEFAULT_HEALTH_CHECKS_ENABLED);
        healthDiskMinMb = getIntConfig("health.checks.disk.min.mb", "HEALTH_CHECKS_DISK_MIN_MB", DEFAULT_HEALTH_DISK_MIN_MB);

        // Phase 5: Request body configuration
        requestBodyMaxSizeMb = getIntConfig("request.body.max.size.mb", "REQUEST_BODY_MAX_SIZE_MB", DEFAULT_REQUEST_BODY_MAX_SIZE_MB);
        requestBodyJsonEnabled = getBooleanConfig("request.body.json.enabled", "REQUEST_BODY_JSON_ENABLED", DEFAULT_REQUEST_BODY_JSON_ENABLED);
        requestBodyFormEnabled = getBooleanConfig("request.body.form.enabled", "REQUEST_BODY_FORM_ENABLED", DEFAULT_REQUEST_BODY_FORM_ENABLED);
        requestBodyMultipartEnabled = getBooleanConfig("request.body.multipart.enabled", "REQUEST_BODY_MULTIPART_ENABLED", DEFAULT_REQUEST_BODY_MULTIPART_ENABLED);

        // Phase 5: Rate limiting configuration
        rateLimitEnabled = getBooleanConfig("rate.limit.enabled", "RATE_LIMIT_ENABLED", DEFAULT_RATE_LIMIT_ENABLED);
        rateLimitRequestsPerSecond = getIntConfig("rate.limit.requests.per.second", "RATE_LIMIT_REQUESTS_PER_SECOND", DEFAULT_RATE_LIMIT_REQUESTS_PER_SECOND);
        rateLimitBurstSize = getIntConfig("rate.limit.burst.size", "RATE_LIMIT_BURST_SIZE", DEFAULT_RATE_LIMIT_BURST_SIZE);
        rateLimitWhitelistIps = getStringConfig("rate.limit.whitelist.ips", "RATE_LIMIT_WHITELIST_IPS", "127.0.0.1,::1");

        // Phase 6: Authentication configuration
        apiKeyEnabled = getBooleanConfig("auth.apikey.enabled", "AUTH_APIKEY_ENABLED", DEFAULT_APIKEY_ENABLED);
        apiKeys = getStringConfig("auth.apikeys", "AUTH_APIKEYS", null);

        // Phase 6: Virtual hosts configuration
        virtualHostsEnabled = getBooleanConfig("vhosts.enabled", "VHOSTS_ENABLED", DEFAULT_VHOSTS_ENABLED);
        virtualHostsConfig = getStringConfig("vhosts.config", "VHOSTS_CONFIG", null);
        virtualHostsDefault = getStringConfig("vhosts.default", "VHOSTS_DEFAULT", null);

        // Phase 6: Routing configuration
        routingEnabled = getBooleanConfig("routing.enabled", "ROUTING_ENABLED", DEFAULT_ROUTING_ENABLED);
        redirectsConfig = getStringConfig("routing.redirects", "ROUTING_REDIRECTS", null);
        rewritesConfig = getStringConfig("routing.rewrites", "ROUTING_REWRITES", null);
        errorPagesConfig = getStringConfig("routing.error.pages", "ROUTING_ERROR_PAGES", null);

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

    // Phase 5: Observability getters
    public boolean isMetricsEnabled() {
        return metricsEnabled;
    }

    public String getLoggingFormat() {
        return loggingFormat;
    }

    public String getLoggingLevel() {
        return loggingLevel;
    }

    public boolean isTracingEnabled() {
        return tracingEnabled;
    }

    public String getTracingOtlpEndpoint() {
        return tracingOtlpEndpoint;
    }

    public boolean isJsonLogging() {
        return "json".equalsIgnoreCase(loggingFormat);
    }

    // Phase 5: Health checks getters
    public boolean isHealthChecksEnabled() {
        return healthChecksEnabled;
    }

    public int getHealthDiskMinMb() {
        return healthDiskMinMb;
    }

    // Phase 5: Request body getters
    public int getRequestBodyMaxSizeMb() {
        return requestBodyMaxSizeMb;
    }

    public long getRequestBodyMaxSizeBytes() {
        return (long) requestBodyMaxSizeMb * 1024 * 1024;
    }

    public boolean isRequestBodyJsonEnabled() {
        return requestBodyJsonEnabled;
    }

    public boolean isRequestBodyFormEnabled() {
        return requestBodyFormEnabled;
    }

    public boolean isRequestBodyMultipartEnabled() {
        return requestBodyMultipartEnabled;
    }

    // Phase 5: Rate limiting getters
    public boolean isRateLimitEnabled() {
        return rateLimitEnabled;
    }

    public int getRateLimitRequestsPerSecond() {
        return rateLimitRequestsPerSecond;
    }

    public int getRateLimitBurstSize() {
        return rateLimitBurstSize;
    }

    public String getRateLimitWhitelistIps() {
        return rateLimitWhitelistIps;
    }

    // Phase 6: Authentication getters



    public boolean isApiKeyEnabled() {
        return apiKeyEnabled;
    }

    public String getApiKeys() {
        return apiKeys;
    }

    // Phase 6: Virtual hosts getters
    public boolean isVirtualHostsEnabled() {
        return virtualHostsEnabled;
    }

    public String getVirtualHostsConfig() {
        return virtualHostsConfig;
    }

    public String getVirtualHostsDefault() {
        return virtualHostsDefault;
    }

    // Phase 6: Routing getters
    public boolean isRoutingEnabled() {
        return routingEnabled;
    }

    public String getRedirectsConfig() {
        return redirectsConfig;
    }

    public String getRewritesConfig() {
        return rewritesConfig;
    }

    public String getErrorPagesConfig() {
        return errorPagesConfig;
    }
}
