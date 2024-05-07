package HTTPServer;

import java.io.IOException;
import java.io.Writer;

public class SecurityHeadersHandler {

    private static final String HSTS_HEADER = "Strict-Transport-Security: max-age=63072000; includeSubDomains; preload";
    private static final String CSP_HEADER = "Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";
    private static final String FRAME_OPTIONS_HEADER = "X-Frame-Options: DENY";
    private static final String CONTENT_TYPE_HEADER = "X-Content-Type-Options: nosniff";
    private static final String REFERRER_POLICY_HEADER = "Referrer-Policy: strict-origin-when-cross-origin";
    private static final String PERMISSIONS_POLICY_HEADER = "Permissions-Policy: geolocation=(), microphone=(), camera=()";

    public void addSecurityHeaders(Writer writer, boolean isTls) throws IOException {
        if (isTls) {
            writer.write(HSTS_HEADER + "\r\n");
        }

        writer.write(CSP_HEADER + "\r\n");
        writer.write(FRAME_OPTIONS_HEADER + "\r\n");
        writer.write(CONTENT_TYPE_HEADER + "\r\n");
        writer.write(REFERRER_POLICY_HEADER + "\r\n");
        writer.write(PERMISSIONS_POLICY_HEADER + "\r\n");
    }
}
