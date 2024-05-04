package HTTPServer;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class RoutingEngine {

    private static final Logger logger = Logger.getLogger(RoutingEngine.class.getName());

    private final ServerConfig config;
    private final boolean enabled;
    private final List<RedirectRule> redirectRules;
    private final List<RewriteRule> rewriteRules;
    private final Map<Integer, String> errorPages;
    private final MetricsCollector metrics;

    private static class RedirectRule {
        final String fromPath;
        final String toPath;
        final int statusCode;
        final Pattern pattern;
        final boolean isPattern;

        RedirectRule(String fromPath, String toPath, int statusCode) {
            this.fromPath = fromPath;
            this.toPath = toPath;
            this.statusCode = statusCode;
            this.isPattern = fromPath.contains("*");

            if (isPattern) {
                String regex = fromPath.replace("*", ".*");
                this.pattern = Pattern.compile(regex);
            } else {
                this.pattern = null;
            }
        }

        boolean matches(String path) {
            if (isPattern) {
                return pattern.matcher(path).matches();
            }
            return path.equals(fromPath);
        }

        String apply(String path) {
            if (isPattern) {
                return pattern.matcher(path).replaceAll(toPath.replace("*", ".*"));
            }
            return toPath;
        }
    }

    private static class RewriteRule {
        final String fromPattern;
        final String toPattern;
        final Pattern pattern;
        final boolean isPattern;

        RewriteRule(String fromPattern, String toPattern) {
            this.fromPattern = fromPattern;
            this.toPattern = toPattern;
            this.isPattern = fromPattern.contains("*");

            if (isPattern) {
                String regex = fromPattern.replace("*", "(.*)");
                this.pattern = Pattern.compile(regex);
            } else {
                this.pattern = null;
            }
        }

        boolean matches(String path) {
            if (isPattern) {
                return pattern.matcher(path).matches();
            }
            return path.equals(fromPattern);
        }

        String apply(String path) {
            if (isPattern) {
                return pattern.matcher(path).replaceAll(toPattern.replace("*", "$1"));
            }
            return toPattern;
        }
    }

    public static class RoutingResult {
        private final String path;
        private final boolean isRedirect;
        private final int redirectStatusCode;
        private final String redirectLocation;
        private final boolean modified;

        private RoutingResult(String path, boolean isRedirect, int redirectStatusCode,
                             String redirectLocation, boolean modified) {
            this.path = path;
            this.isRedirect = isRedirect;
            this.redirectStatusCode = redirectStatusCode;
            this.redirectLocation = redirectLocation;
            this.modified = modified;
        }

        public static RoutingResult noChange(String path) {
            return new RoutingResult(path, false, 0, null, false);
        }

        public static RoutingResult rewrite(String newPath) {
            return new RoutingResult(newPath, false, 0, null, true);
        }

        public static RoutingResult redirect(int statusCode, String location) {
            return new RoutingResult(null, true, statusCode, location, true);
        }

        public String getPath() {
            return path;
        }

        public boolean isRedirect() {
            return isRedirect;
        }

        public int getRedirectStatusCode() {
            return redirectStatusCode;
        }

        public String getRedirectLocation() {
            return redirectLocation;
        }

        public boolean wasModified() {
            return modified;
        }
    }

    public RoutingEngine(ServerConfig config) {
        this.config = config;
        this.enabled = config.isRoutingEnabled();
        this.redirectRules = new ArrayList<>();
        this.rewriteRules = new ArrayList<>();
        this.errorPages = new HashMap<>();
        this.metrics = MetricsCollector.getInstance();

        if (enabled) {
            initializeRoutingRules();
            initializeErrorPages();
        }
    }

    private void initializeRoutingRules() {
        String redirectsConfig = config.getRedirectsConfig();
        if (redirectsConfig != null && !redirectsConfig.isEmpty()) {
            String[] redirects = redirectsConfig.split(",");
            for (String redirect : redirects) {
                String[] parts = redirect.trim().split(":");
                if (parts.length == 3) {
                    try {
                        int statusCode = Integer.parseInt(parts[0].trim());
                        String fromPath = parts[1].trim();
                        String toPath = parts[2].trim();

                        if (statusCode == 301 || statusCode == 302 || statusCode == 307 || statusCode == 308) {
                            redirectRules.add(new RedirectRule(fromPath, toPath, statusCode));
                            logger.info("Configured redirect: " + statusCode + " " + fromPath + " -> " + toPath);
                        } else {
                            logger.warning("Invalid redirect status code: " + statusCode);
                        }
                    } catch (NumberFormatException e) {
                        logger.warning("Invalid redirect configuration: " + redirect);
                    }
                }
            }
        }

        String rewritesConfig = config.getRewritesConfig();
        if (rewritesConfig != null && !rewritesConfig.isEmpty()) {
            String[] rewrites = rewritesConfig.split(",");
            for (String rewrite : rewrites) {
                String[] parts = rewrite.trim().split(":");
                if (parts.length == 2) {
                    String fromPattern = parts[0].trim();
                    String toPattern = parts[1].trim();
                    rewriteRules.add(new RewriteRule(fromPattern, toPattern));
                    logger.info("Configured rewrite: " + fromPattern + " -> " + toPattern);
                }
            }
        }

        logger.info("Routing rules initialized: " + redirectRules.size() + " redirects, " + rewriteRules.size() + " rewrites");
    }

    private void initializeErrorPages() {
        String errorPagesConfig = config.getErrorPagesConfig();
        if (errorPagesConfig != null && !errorPagesConfig.isEmpty()) {
            String[] pages = errorPagesConfig.split(",");
            for (String page : pages) {
                String[] parts = page.trim().split(":");
                if (parts.length == 2) {
                    try {
                        int statusCode = Integer.parseInt(parts[0].trim());
                        String pagePath = parts[1].trim();
                        errorPages.put(statusCode, pagePath);
                        logger.info("Configured error page: " + statusCode + " -> " + pagePath);
                    } catch (NumberFormatException e) {
                        logger.warning("Invalid error page configuration: " + page);
                    }
                }
            }
        }
    }

    public RoutingResult processRequest(String path) {
        if (!enabled) {
            return RoutingResult.noChange(path);
        }

        for (RedirectRule rule : redirectRules) {
            if (rule.matches(path)) {
                String targetPath = rule.apply(path);
                if (config.isMetricsEnabled()) {
                    metrics.incrementCounter("routing_redirects", "status=" + rule.statusCode);
                }
                logger.fine("Redirect matched: " + path + " -> " + targetPath + " (" + rule.statusCode + ")");
                return RoutingResult.redirect(rule.statusCode, targetPath);
            }
        }

        for (RewriteRule rule : rewriteRules) {
            if (rule.matches(path)) {
                String rewrittenPath = rule.apply(path);
                if (config.isMetricsEnabled()) {
                    metrics.incrementCounter("routing_rewrites");
                }
                logger.fine("Rewrite matched: " + path + " -> " + rewrittenPath);
                return RoutingResult.rewrite(rewrittenPath);
            }
        }

        return RoutingResult.noChange(path);
    }

    public void sendRedirect(Writer writer, String version, int statusCode, String location,
                            Map<String, String> headers) throws IOException {
        String statusText = getStatusText(statusCode);
        String httpVersion = version.startsWith("HTTP/1.1") ? "HTTP/1.1" : "HTTP/1.0";

        String responseBody = String.format(
            "<html><head><title>%d %s</title></head><body><h1>%d %s</h1><p>The document has moved <a href=\"%s\">here</a>.</p></body></html>",
            statusCode, statusText, statusCode, statusText, location
        );

        writer.write(httpVersion + " " + statusCode + " " + statusText + "\r\n");
        writer.write("Location: " + location + "\r\n");
        writer.write("Content-Type: text/html; charset=utf-8\r\n");
        writer.write("Content-Length: " + responseBody.length() + "\r\n");
        writer.write("Connection: close\r\n");
        writer.write("\r\n");
        writer.write(responseBody);
        writer.flush();
    }

    private String getStatusText(int statusCode) {
        switch (statusCode) {
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 307: return "Temporary Redirect";
            case 308: return "Permanent Redirect";
            default: return "Redirect";
        }
    }

    public String getCustomErrorPage(int statusCode, File webroot) {
        String errorPagePath = errorPages.get(statusCode);
        if (errorPagePath == null) {
            return null;
        }

        try {
            Path fullPath = Paths.get(webroot.getCanonicalPath(), errorPagePath).normalize();
            Path webrootPath = Paths.get(webroot.getCanonicalPath()).normalize();

            if (!fullPath.startsWith(webrootPath)) {
                logger.warning("Error page path outside webroot: " + errorPagePath);
                return null;
            }

            File errorFile = fullPath.toFile();
            if (errorFile.exists() && errorFile.canRead()) {
                byte[] content = Files.readAllBytes(errorFile.toPath());
                return new String(content, "UTF-8");
            } else {
                logger.warning("Error page file not found or not readable: " + errorPagePath);
                return null;
            }
        } catch (IOException e) {
            logger.warning("Error reading custom error page: " + e.getMessage());
            return null;
        }
    }

    public boolean hasCustomErrorPage(int statusCode) {
        return errorPages.containsKey(statusCode);
    }

    public void addRedirectRule(int statusCode, String fromPath, String toPath) {
        if (!enabled) {
            logger.warning("Cannot add redirect rule: routing is disabled");
            return;
        }
        redirectRules.add(new RedirectRule(fromPath, toPath, statusCode));
        logger.info("Added redirect rule: " + statusCode + " " + fromPath + " -> " + toPath);
    }

    public void addRewriteRule(String fromPattern, String toPattern) {
        if (!enabled) {
            logger.warning("Cannot add rewrite rule: routing is disabled");
            return;
        }
        rewriteRules.add(new RewriteRule(fromPattern, toPattern));
        logger.info("Added rewrite rule: " + fromPattern + " -> " + toPattern);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getRedirectRuleCount() {
        return redirectRules.size();
    }

    public int getRewriteRuleCount() {
        return rewriteRules.size();
    }

    public int getErrorPageCount() {
        return errorPages.size();
    }
}
