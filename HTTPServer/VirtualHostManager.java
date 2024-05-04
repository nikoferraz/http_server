package HTTPServer;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class VirtualHostManager {

    private static final Logger logger = Logger.getLogger(VirtualHostManager.class.getName());

    private final ServerConfig config;
    private final File defaultWebroot;
    private final ConcurrentHashMap<String, File> virtualHosts;
    private final boolean enabled;
    private final MetricsCollector metrics;

    public VirtualHostManager(ServerConfig config, File defaultWebroot) {
        this.config = config;
        this.defaultWebroot = defaultWebroot;
        this.virtualHosts = new ConcurrentHashMap<>();
        this.enabled = config.isVirtualHostsEnabled();
        this.metrics = MetricsCollector.getInstance();

        if (enabled) {
            initializeVirtualHosts();
        }
    }

    private void initializeVirtualHosts() {
        String vhostsConfig = config.getVirtualHostsConfig();

        if (vhostsConfig == null || vhostsConfig.isEmpty()) {
            logger.info("Virtual hosts enabled but no hosts configured, using default webroot");
            return;
        }

        String[] hostConfigs = vhostsConfig.split(",");
        for (String hostConfig : hostConfigs) {
            String[] parts = hostConfig.trim().split(":");
            if (parts.length == 2) {
                String hostname = parts[0].trim();
                String webrootPath = parts[1].trim();

                File webroot = new File(webrootPath);
                if (webroot.exists() && webroot.isDirectory()) {
                    virtualHosts.put(normalizeHostname(hostname), webroot);
                    logger.info("Configured virtual host: " + hostname + " -> " + webrootPath);
                } else {
                    logger.warning("Virtual host webroot does not exist: " + webrootPath + " for host: " + hostname);
                }
            } else {
                logger.warning("Invalid virtual host configuration: " + hostConfig);
            }
        }

        logger.info("Virtual hosts initialized with " + virtualHosts.size() + " host(s)");
    }

    public File resolveWebroot(Map<String, String> headers) {
        if (!enabled) {
            return defaultWebroot;
        }

        String hostHeader = headers.get("host");
        if (hostHeader == null || hostHeader.isEmpty()) {
            if (config.isMetricsEnabled()) {
                metrics.incrementCounter("vhost_default_fallback", "reason=no_header");
            }
            return defaultWebroot;
        }

        String hostname = normalizeHostname(hostHeader);

        File webroot = virtualHosts.get(hostname);
        if (webroot != null) {
            if (config.isMetricsEnabled()) {
                metrics.incrementCounter("vhost_hits", "host=" + hostname);
            }
            return webroot;
        }

        if (config.isMetricsEnabled()) {
            metrics.incrementCounter("vhost_default_fallback", "reason=no_match", "host=" + hostname);
        }

        return defaultWebroot;
    }

    private String normalizeHostname(String hostname) {
        if (hostname == null) {
            return "";
        }

        hostname = hostname.toLowerCase().trim();

        int colonIndex = hostname.indexOf(':');
        if (colonIndex != -1) {
            hostname = hostname.substring(0, colonIndex);
        }

        if (hostname.startsWith("www.")) {
            hostname = hostname.substring(4);
        }

        return hostname;
    }

    public void addVirtualHost(String hostname, File webroot) {
        if (!enabled) {
            logger.warning("Cannot add virtual host: virtual hosts feature is disabled");
            return;
        }

        if (webroot == null || !webroot.exists() || !webroot.isDirectory()) {
            logger.warning("Cannot add virtual host: invalid webroot directory");
            return;
        }

        String normalizedHostname = normalizeHostname(hostname);
        virtualHosts.put(normalizedHostname, webroot);
        logger.info("Added virtual host: " + normalizedHostname + " -> " + webroot.getAbsolutePath());
    }

    public void removeVirtualHost(String hostname) {
        String normalizedHostname = normalizeHostname(hostname);
        File removed = virtualHosts.remove(normalizedHostname);
        if (removed != null) {
            logger.info("Removed virtual host: " + normalizedHostname);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getVirtualHostCount() {
        return virtualHosts.size();
    }

    public Map<String, File> getAllVirtualHosts() {
        return new ConcurrentHashMap<>(virtualHosts);
    }

    public File getDefaultWebroot() {
        return defaultWebroot;
    }

    public String getVirtualHostInfo(String hostname) {
        String normalizedHostname = normalizeHostname(hostname);
        File webroot = virtualHosts.get(normalizedHostname);

        if (webroot != null) {
            return "Virtual host '" + hostname + "' -> " + webroot.getAbsolutePath();
        } else {
            return "Virtual host '" + hostname + "' not found, would use default: " + defaultWebroot.getAbsolutePath();
        }
    }
}
