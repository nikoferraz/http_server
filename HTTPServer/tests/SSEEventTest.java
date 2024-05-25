package HTTPServer.tests;

import HTTPServer.*;
import java.nio.charset.StandardCharsets;

/**
 * Unit tests for SSEEvent formatting.
 * Validates SSE event format according to W3C specification.
 */
public class SSEEventTest {

    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) {
        System.out.println("Running SSEEventTest...\n");

        testBasicEvent();
        testEventWithType();
        testEventWithId();
        testEventWithRetry();
        testEventWithAllFields();
        testMultilineData();
        testKeepaliveComment();
        testSpecialCharactersEscaping();
        testEmptyDataThrowsException();

        printResults();
    }

    private static void testBasicEvent() {
        SSEEvent event = new SSEEvent("Hello, World!");
        String formatted = new String(event.toBytes(), StandardCharsets.UTF_8);

        assertTrue("Basic event contains data", formatted.contains("data: Hello, World!"));
        assertTrue("Basic event ends with double newline", formatted.endsWith("\n\n"));
        assertTrue("Default event type is message", !formatted.contains("event:"));

        passTest("testBasicEvent");
    }

    private static void testEventWithType() {
        SSEEvent event = new SSEEvent("Connection established", "connect");
        String formatted = new String(event.toBytes(), StandardCharsets.UTF_8);

        assertTrue("Event type is included", formatted.contains("event: connect\n"));
        assertTrue("Data is included", formatted.contains("data: Connection established\n"));

        passTest("testEventWithType");
    }

    private static void testEventWithId() {
        SSEEvent event = new SSEEvent("Update", null, "123");
        String formatted = new String(event.toBytes(), StandardCharsets.UTF_8);

        assertTrue("Event ID is included", formatted.contains("id: 123\n"));
        assertTrue("Data is included", formatted.contains("data: Update\n"));

        passTest("testEventWithId");
    }

    private static void testEventWithRetry() {
        SSEEvent event = new SSEEvent("Retry me", null, null, 5000);
        String formatted = new String(event.toBytes(), StandardCharsets.UTF_8);

        assertTrue("Retry value is included", formatted.contains("retry: 5000\n"));
        assertTrue("Data is included", formatted.contains("data: Retry me\n"));

        passTest("testEventWithRetry");
    }

    private static void testEventWithAllFields() {
        SSEEvent event = new SSEEvent("Complete event", "update", "456", 3000);
        String formatted = new String(event.toBytes(), StandardCharsets.UTF_8);

        assertTrue("Event type present", formatted.contains("event: update\n"));
        assertTrue("ID present", formatted.contains("id: 456\n"));
        assertTrue("Retry present", formatted.contains("retry: 3000\n"));
        assertTrue("Data present", formatted.contains("data: Complete event\n"));

        passTest("testEventWithAllFields");
    }

    private static void testMultilineData() {
        String multilineData = "Line 1\nLine 2\nLine 3";
        SSEEvent event = new SSEEvent(multilineData);
        String formatted = new String(event.toBytes(), StandardCharsets.UTF_8);

        assertTrue("First line formatted", formatted.contains("data: Line 1\n"));
        assertTrue("Second line formatted", formatted.contains("data: Line 2\n"));
        assertTrue("Third line formatted", formatted.contains("data: Line 3\n"));

        passTest("testMultilineData");
    }

    private static void testKeepaliveComment() {
        byte[] keepalive = SSEEvent.keepaliveComment();
        String formatted = new String(keepalive, StandardCharsets.UTF_8);

        assertTrue("Keepalive starts with colon", formatted.startsWith(":"));
        assertTrue("Keepalive is single line", formatted.equals(":\n"));

        passTest("testKeepaliveComment");
    }

    private static void testSpecialCharactersEscaping() {
        String dataWithSpecialChars = "{\"name\":\"John\\\"s\",\"value\":123}";
        SSEEvent event = new SSEEvent(dataWithSpecialChars);
        String formatted = new String(event.toBytes(), StandardCharsets.UTF_8);

        assertTrue("Data contains special characters", formatted.contains("data: {\"name\":\"John\\\"s\",\"value\":123}"));

        passTest("testSpecialCharactersEscaping");
    }

    private static void testEmptyDataThrowsException() {
        try {
            new SSEEvent("");
            failTest("testEmptyDataThrowsException - should throw exception");
        } catch (IllegalArgumentException e) {
            passTest("testEmptyDataThrowsException");
        }
    }

    private static void assertTrue(String message, boolean condition) {
        if (!condition) {
            System.out.println("  FAIL: " + message);
            testsFailed++;
        }
    }

    private static void passTest(String testName) {
        System.out.println("PASS: " + testName);
        testsPassed++;
    }

    private static void failTest(String testName) {
        System.out.println("FAIL: " + testName);
        testsFailed++;
    }

    private static void printResults() {
        System.out.println("\n" + "=".repeat(50));
        System.out.println("Tests passed: " + testsPassed);
        System.out.println("Tests failed: " + testsFailed);
        System.out.println("Total tests: " + (testsPassed + testsFailed));
        System.out.println("=".repeat(50));
    }
}
