package HTTPServer;

import java.io.IOException;

public class BrotliCompressionHandler {

    private static final int DEFAULT_QUALITY = 4;
    private static final boolean BROTLI_AVAILABLE = checkBrotliAvailability();

    private static boolean checkBrotliAvailability() {
        try {
            Class.forName("com.aayushatharva.brotli4j.Brotli4jLoader");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public boolean isBrotliAvailable() {
        return BROTLI_AVAILABLE;
    }

    public byte[] compress(byte[] data) throws IOException {
        if (!BROTLI_AVAILABLE) {
            throw new IOException("Brotli compression not available - add brotli4j dependency");
        }

        try {
            Class<?> loaderClass = Class.forName("com.aayushatharva.brotli4j.Brotli4jLoader");
            loaderClass.getMethod("ensureAvailability").invoke(null);

            Class<?> encoderClass = Class.forName("com.aayushatharva.brotli4j.encoder.Encoder");
            Class<?> parametersClass = Class.forName("com.aayushatharva.brotli4j.encoder.Encoder$Parameters");
            Object params = parametersClass.getConstructor().newInstance();
            params = parametersClass.getMethod("setQuality", int.class).invoke(params, DEFAULT_QUALITY);

            return (byte[]) encoderClass.getMethod("compress", byte[].class, parametersClass)
                .invoke(null, data, params);
        } catch (Exception e) {
            throw new IOException("Brotli compression failed", e);
        }
    }

    public boolean shouldUseBrotli(String acceptEncoding) {
        return BROTLI_AVAILABLE && acceptEncoding != null &&
               acceptEncoding.toLowerCase().contains("br");
    }

    public boolean preferBrotli(String acceptEncoding) {
        if (!BROTLI_AVAILABLE || acceptEncoding == null) {
            return false;
        }

        String lower = acceptEncoding.toLowerCase();
        boolean hasBrotli = lower.contains("br");
        boolean hasGzip = lower.contains("gzip");

        if (!hasBrotli) {
            return false;
        }

        if (!hasGzip) {
            return true;
        }

        int brPos = lower.indexOf("br");
        int gzipPos = lower.indexOf("gzip");
        return brPos < gzipPos;
    }
}
