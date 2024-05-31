package HTTPServer;

import java.io.IOException;
import java.io.Writer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;
import java.util.logging.Level;

public class TechEmpowerHandler {
    private static final Logger logger = Logger.getLogger(TechEmpowerHandler.class.getName());
    private static final Random random = new Random();

    private static final String QUERY_WORLD = "SELECT id, randomNumber FROM World WHERE id = ?";
    private static final String UPDATE_WORLD = "UPDATE World SET randomNumber = ? WHERE id = ?";
    private static final String QUERY_FORTUNE = "SELECT id, message FROM Fortune ORDER BY id";

    public static void handlePlaintext(Writer writer, String version, boolean keepAlive) throws IOException {
        String responseBody = "Hello, World!";
        int contentLength = responseBody.length();

        writer.write(version + " 200 OK\r\n");
        writer.write("Content-Type: text/plain; charset=UTF-8\r\n");
        writer.write("Content-Length: " + contentLength + "\r\n");
        writer.write("Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n");
        writer.write("Server: TechEmpower\r\n");
        writer.write("\r\n");
        writer.write(responseBody);
        writer.flush();
    }

    public static void handleJson(Writer writer, String version, boolean keepAlive) throws IOException {
        String responseBody = JsonUtil.toJson("Hello, World!");
        int contentLength = responseBody.length();

        writer.write(version + " 200 OK\r\n");
        writer.write("Content-Type: application/json\r\n");
        writer.write("Content-Length: " + contentLength + "\r\n");
        writer.write("Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n");
        writer.write("Server: TechEmpower\r\n");
        writer.write("\r\n");
        writer.write(responseBody);
        writer.flush();
    }

    public static void handleSingleQuery(Writer writer, String version, boolean keepAlive) throws IOException {
        Connection conn = null;
        try {
            conn = DatabaseConnectionPool.getInstance().getConnection();
            int worldId = random.nextInt(10000) + 1;

            try (PreparedStatement stmt = conn.prepareStatement(QUERY_WORLD)) {
                stmt.setInt(1, worldId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        int id = rs.getInt("id");
                        int randomNumber = rs.getInt("randomNumber");

                        String responseBody = JsonUtil.worldToJson(id, randomNumber);
                        int contentLength = responseBody.length();

                        writer.write(version + " 200 OK\r\n");
                        writer.write("Content-Type: application/json\r\n");
                        writer.write("Content-Length: " + contentLength + "\r\n");
                        writer.write("Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n");
                        writer.write("Server: TechEmpower\r\n");
                        writer.write("\r\n");
                        writer.write(responseBody);
                        writer.flush();
                    }
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Database error in single query", e);
            sendError(writer, version, 500, "Internal Server Error", keepAlive);
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    logger.log(Level.WARNING, "Error closing connection", e);
                }
            }
        }
    }

    public static void handleMultipleQueries(Writer writer, String version, int queries, boolean keepAlive)
            throws IOException {
        if (queries < 1) queries = 1;
        if (queries > 500) queries = 500;

        Connection conn = null;
        try {
            conn = DatabaseConnectionPool.getInstance().getConnection();
            int[][] worlds = new int[queries][2];

            try (PreparedStatement stmt = conn.prepareStatement(QUERY_WORLD)) {
                for (int i = 0; i < queries; i++) {
                    int worldId = random.nextInt(10000) + 1;
                    stmt.setInt(1, worldId);

                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            worlds[i][0] = rs.getInt("id");
                            worlds[i][1] = rs.getInt("randomNumber");
                        }
                    }
                }
            }

            String responseBody = JsonUtil.worldsToJson(worlds);
            int contentLength = responseBody.length();

            writer.write(version + " 200 OK\r\n");
            writer.write("Content-Type: application/json\r\n");
            writer.write("Content-Length: " + contentLength + "\r\n");
            writer.write("Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n");
            writer.write("Server: TechEmpower\r\n");
            writer.write("\r\n");
            writer.write(responseBody);
            writer.flush();

        } catch (SQLException e) {
            logger.log(Level.WARNING, "Database error in multiple queries", e);
            sendError(writer, version, 500, "Internal Server Error", keepAlive);
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    logger.log(Level.WARNING, "Error closing connection", e);
                }
            }
        }
    }

    public static void handleUpdates(Writer writer, String version, int queries, boolean keepAlive)
            throws IOException {
        if (queries < 1) queries = 1;
        if (queries > 500) queries = 500;

        Connection conn = null;
        try {
            conn = DatabaseConnectionPool.getInstance().getConnection();
            conn.setAutoCommit(false);
            int[][] worlds = new int[queries][2];

            try (PreparedStatement selectStmt = conn.prepareStatement(QUERY_WORLD);
                 PreparedStatement updateStmt = conn.prepareStatement(UPDATE_WORLD)) {

                for (int i = 0; i < queries; i++) {
                    int worldId = random.nextInt(10000) + 1;
                    selectStmt.setInt(1, worldId);

                    try (ResultSet rs = selectStmt.executeQuery()) {
                        if (rs.next()) {
                            worlds[i][0] = rs.getInt("id");
                            int newRandomNumber = random.nextInt(10000) + 1;
                            worlds[i][1] = newRandomNumber;

                            updateStmt.setInt(1, newRandomNumber);
                            updateStmt.setInt(2, worldId);
                            updateStmt.addBatch();
                        }
                    }
                }

                updateStmt.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }

            String responseBody = JsonUtil.worldsToJson(worlds);
            int contentLength = responseBody.length();

            writer.write(version + " 200 OK\r\n");
            writer.write("Content-Type: application/json\r\n");
            writer.write("Content-Length: " + contentLength + "\r\n");
            writer.write("Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n");
            writer.write("Server: TechEmpower\r\n");
            writer.write("\r\n");
            writer.write(responseBody);
            writer.flush();

        } catch (SQLException e) {
            logger.log(Level.WARNING, "Database error in updates", e);
            sendError(writer, version, 500, "Internal Server Error", keepAlive);
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    logger.log(Level.WARNING, "Error closing connection", e);
                }
            }
        }
    }

    public static void handleFortunes(Writer writer, String version, boolean keepAlive) throws IOException {
        Connection conn = null;
        try {
            conn = DatabaseConnectionPool.getInstance().getConnection();
            Map<Integer, String> fortunes = new HashMap<>();

            try (PreparedStatement stmt = conn.prepareStatement(QUERY_FORTUNE);
                 ResultSet rs = stmt.executeQuery()) {

                while (rs.next()) {
                    fortunes.put(rs.getInt("id"), rs.getString("message"));
                }
            }

            fortunes.put(0, "Additional fortune added at request time.");

            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html>\r\n");
            html.append("<html>\r\n");
            html.append("<head>\r\n");
            html.append("<title>Fortunes</title>\r\n");
            html.append("</head>\r\n");
            html.append("<body>\r\n");
            html.append("<table>\r\n");
            html.append("<tr><th>id</th><th>message</th></tr>\r\n");

            fortunes.entrySet().stream()
                    .sorted((a, b) -> a.getValue().compareTo(b.getValue()))
                    .forEach(entry -> {
                        html.append("<tr><td>").append(entry.getKey()).append("</td>");
                        html.append("<td>").append(JsonUtil.escapeHtmlAttribute(entry.getValue())).append("</td></tr>\r\n");
                    });

            html.append("</table>\r\n");
            html.append("</body>\r\n");
            html.append("</html>\r\n");

            String responseBody = html.toString();
            int contentLength = responseBody.getBytes("UTF-8").length;

            writer.write(version + " 200 OK\r\n");
            writer.write("Content-Type: text/html; charset=utf-8\r\n");
            writer.write("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\n");
            writer.write("Content-Length: " + contentLength + "\r\n");
            writer.write("Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n");
            writer.write("Server: TechEmpower\r\n");
            writer.write("\r\n");
            writer.write(responseBody);
            writer.flush();

        } catch (SQLException e) {
            logger.log(Level.WARNING, "Database error in fortunes", e);
            sendError(writer, version, 500, "Internal Server Error", keepAlive);
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    logger.log(Level.WARNING, "Error closing connection", e);
                }
            }
        }
    }

    private static void sendError(Writer writer, String version, int statusCode, String statusText, boolean keepAlive)
            throws IOException {
        String body = statusText;
        writer.write(version + " " + statusCode + " " + statusText + "\r\n");
        writer.write("Content-Type: text/plain\r\n");
        writer.write("Content-Length: " + body.length() + "\r\n");
        writer.write("Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n");
        writer.write("\r\n");
        writer.write(body);
        writer.flush();
    }

    public static int extractQueryCount(String queryString) {
        if (queryString == null || queryString.isEmpty()) {
            return 1;
        }

        String[] params = queryString.split("&");
        for (String param : params) {
            if (param.startsWith("queries=")) {
                try {
                    return Integer.parseInt(param.substring(8));
                } catch (NumberFormatException e) {
                    return 1;
                }
            }
        }

        return 1;
    }
}
