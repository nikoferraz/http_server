package HTTPServer;

public class JsonUtil {

    public static String toJson(String message) {
        return "{\"message\":\"" + escapeJson(message) + "\"}";
    }

    public static String worldToJson(int id, int randomNumber) {
        return "{\"id\":" + id + ",\"randomNumber\":" + randomNumber + "}";
    }

    public static String worldsToJson(int[][] worlds) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < worlds.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\":").append(worlds[i][0]).append(",\"randomNumber\":").append(worlds[i][1]).append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    public static String escapeJson(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 32 || c > 126) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    public static String escapeHtmlAttribute(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }
}
