package HTTPServer.tests;

import HTTPServer.JsonUtil;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class XSSProtectionTest {
    @Test
    public void testScriptTagEscaping() {
        String input = "<script>alert('XSS')</script>";
        String escaped = JsonUtil.escapeHtml(input);
        assertFalse(escaped.contains("<script>"));
        assertTrue(escaped.contains("&lt;script&gt;"));
    }

    @Test
    public void testImageOnError() {
        String input = "<img src=x onerror=alert('XSS')>";
        String escaped = JsonUtil.escapeHtml(input);
        assertFalse(escaped.contains("onerror="));
        assertTrue(escaped.contains("&lt;"));
    }

    @Test
    public void testJavascriptProtocol() {
        String input = "javascript:alert('XSS')";
        String escaped = JsonUtil.escapeHtml(input);
        assertFalse(escaped.contains("<"));
    }

    @Test
    public void testSvgOnload() {
        String input = "<svg onload=alert('XSS')>";
        String escaped = JsonUtil.escapeHtml(input);
        assertFalse(escaped.contains("<svg"));
        assertTrue(escaped.contains("&lt;svg"));
    }

    @Test
    public void testNullInput() {
        String escaped = JsonUtil.escapeHtml(null);
        assertEquals("", escaped);
    }
}
