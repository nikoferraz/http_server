import HTTPServer.ServerConfig;

public class test_threshold {
    public static void main(String[] args) {
        ServerConfig config = new ServerConfig();
        
        long threshold = config.getZeroCopyThreshold();
        System.out.println("Zero-Copy Threshold: " + threshold + " bytes (" + (threshold / (1024*1024)) + "MB)");
        
        // Test different file sizes
        long[] fileSizes = {
            5_242_880,        // 5MB - should use buffered
            10_485_760,       // 10MB - should use zero-copy
            50_331_648,       // 48MB - should use zero-copy
        };
        
        System.out.println("\nFile Size Determination:");
        for (long size : fileSizes) {
            boolean useZeroCopy = size >= threshold;
            String method = useZeroCopy ? "ZERO-COPY" : "BUFFERED";
            System.out.printf("  %10d bytes (%5.1f MB) -> %s%n", size, size/(1024.0*1024.0), method);
        }
    }
}
