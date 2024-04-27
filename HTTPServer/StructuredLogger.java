package HTTPServer;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.logging.Logger;

public class StructuredLogger {

    private final Logger logger;
    private final boolean jsonFormat;
    private final String level;

    public StructuredLogger(Logger logger, boolean jsonFormat, String level) {
        this.logger = logger;
        this.jsonFormat = jsonFormat;
        this.level = level;
    }

    public String generateRequestId() {
        return UUID.randomUUID().toString();
    }

    public void logRequest(String requestId, String remoteIp, String method, String path,
                          int statusCode, long durationMs, long responseSize) {
        if (jsonFormat) {
            logJsonRequest(requestId, remoteIp, method, path, statusCode, durationMs, responseSize);
        } else {
            logPlainRequest(requestId, remoteIp, method, path, statusCode, durationMs, responseSize);
        }
    }

    private void logJsonRequest(String requestId, String remoteIp, String method, String path,
                               int statusCode, long durationMs, long responseSize) {
        String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT);

        StringBuilder json = new StringBuilder("{");
        json.append("\"timestamp\":\"").append(timestamp).append("\",");
        json.append("\"level\":\"INFO\",");
        json.append("\"message\":\"HTTP request\",");
        json.append("\"requestId\":\"").append(requestId).append("\",");
        json.append("\"remoteIP\":\"").append(escapeJson(remoteIp)).append("\",");
        json.append("\"method\":\"").append(escapeJson(method)).append("\",");
        json.append("\"path\":\"").append(escapeJson(path)).append("\",");
        json.append("\"statusCode\":").append(statusCode).append(",");
        json.append("\"duration\":").append(durationMs).append(",");
        json.append("\"responseSize\":").append(responseSize);
        json.append("}");

        logger.info(json.toString());
    }

    private void logPlainRequest(String requestId, String remoteIp, String method, String path,
                                int statusCode, long durationMs, long responseSize) {
        String message = String.format("%s %s %s - %d - %dms - %d bytes [%s]",
            remoteIp, method, path, statusCode, durationMs, responseSize, requestId);
        logger.info(message);
    }

    public void logError(String requestId, String message, Throwable throwable) {
        if (jsonFormat) {
            logJsonError(requestId, message, throwable);
        } else {
            logPlainError(requestId, message, throwable);
        }
    }

    private void logJsonError(String requestId, String message, Throwable throwable) {
        String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT);

        StringBuilder json = new StringBuilder("{");
        json.append("\"timestamp\":\"").append(timestamp).append("\",");
        json.append("\"level\":\"ERROR\",");
        json.append("\"message\":\"").append(escapeJson(message)).append("\",");
        json.append("\"requestId\":\"").append(requestId).append("\"");

        if (throwable != null) {
            json.append(",\"exception\":\"").append(escapeJson(throwable.getClass().getName())).append("\"");
            json.append(",\"exceptionMessage\":\"").append(escapeJson(throwable.getMessage())).append("\"");
        }

        json.append("}");

        logger.severe(json.toString());
    }

    private void logPlainError(String requestId, String message, Throwable throwable) {
        String logMessage = String.format("[%s] %s", requestId, message);
        if (throwable != null) {
            logMessage += " - " + throwable.getClass().getName() + ": " + throwable.getMessage();
        }
        logger.severe(logMessage);
    }

    public void logInfo(String requestId, String message) {
        if (jsonFormat) {
            logJsonInfo(requestId, message);
        } else {
            logPlainInfo(requestId, message);
        }
    }

    private void logJsonInfo(String requestId, String message) {
        String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT);

        StringBuilder json = new StringBuilder("{");
        json.append("\"timestamp\":\"").append(timestamp).append("\",");
        json.append("\"level\":\"INFO\",");
        json.append("\"message\":\"").append(escapeJson(message)).append("\"");

        if (requestId != null && !requestId.isEmpty()) {
            json.append(",\"requestId\":\"").append(requestId).append("\"");
        }

        json.append("}");

        logger.info(json.toString());
    }

    private void logPlainInfo(String requestId, String message) {
        if (requestId != null && !requestId.isEmpty()) {
            logger.info(String.format("[%s] %s", requestId, message));
        } else {
            logger.info(message);
        }
    }

    public void logDebug(String requestId, String message) {
        if (!shouldLog("DEBUG")) {
            return;
        }

        if (jsonFormat) {
            logJsonDebug(requestId, message);
        } else {
            logPlainDebug(requestId, message);
        }
    }

    private void logJsonDebug(String requestId, String message) {
        String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT);

        StringBuilder json = new StringBuilder("{");
        json.append("\"timestamp\":\"").append(timestamp).append("\",");
        json.append("\"level\":\"DEBUG\",");
        json.append("\"message\":\"").append(escapeJson(message)).append("\"");

        if (requestId != null && !requestId.isEmpty()) {
            json.append(",\"requestId\":\"").append(requestId).append("\"");
        }

        json.append("}");

        logger.fine(json.toString());
    }

    private void logPlainDebug(String requestId, String message) {
        if (requestId != null && !requestId.isEmpty()) {
            logger.fine(String.format("[%s] %s", requestId, message));
        } else {
            logger.fine(message);
        }
    }

    private boolean shouldLog(String logLevel) {
        // Simple level comparison
        int currentLevel = getLevelValue(this.level);
        int requestedLevel = getLevelValue(logLevel);

        return requestedLevel >= currentLevel;
    }

    private int getLevelValue(String level) {
        switch (level.toUpperCase()) {
            case "DEBUG":
                return 0;
            case "INFO":
                return 1;
            case "WARN":
                return 2;
            case "ERROR":
                return 3;
            default:
                return 1; // Default to INFO
        }
    }

    private String escapeJson(String str) {
        if (str == null) {
            return "";
        }

        return str
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
