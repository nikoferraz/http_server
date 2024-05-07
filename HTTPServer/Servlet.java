package HTTPServer;

import javax.net.ssl.SSLServerSocket;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.net.SocketTimeoutException;

public class Servlet extends Thread{

    private ExecutorService threadPool;
    File webroot;
    int port = 0;
    private boolean run = true;
    private int servletNumber;
    private FileHandler fileHandler;
    private ServerSocket mainSocket = null;
    private Logger auditLog = Logger.getLogger("requests");
    private Logger errorLog = Logger.getLogger("errors");
    private ServerConfig config;
    private TLSManager tlsManager;
    private boolean useTLS;
    private RateLimiter rateLimiter;


    public Servlet(File webroot, int port, int servletNumber) {
        this(webroot, port, servletNumber, new ServerConfig());
    }

    public Servlet(File webroot, int port, int servletNumber, ServerConfig config) {
        this.webroot = webroot;
        this.servletNumber = servletNumber;
        this.port = port;
        this.config = config;
        // Create thread pool with bounded queue to prevent OOM under load
        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(config.getRequestQueueLimit());
        this.threadPool = new ThreadPoolExecutor(
            config.getThreadPoolSize(),
            config.getThreadPoolSize(),
            0L, TimeUnit.MILLISECONDS,
            queue,
            new ThreadPoolExecutor.AbortPolicy()
        );
        this.useTLS = config.isTlsEnabled();

        // Initialize TLS if enabled
        if (useTLS) {
            tlsManager = new TLSManager(config);
            try {
                tlsManager.initialize();
            } catch (Exception e) {
                errorLog.log(Level.SEVERE, "Failed to initialize TLS, falling back to HTTP", e);
                useTLS = false;
            }
        }

        // Phase 5: Initialize rate limiter
        if (config.isRateLimitEnabled()) {
            rateLimiter = new RateLimiter(
                config.getRateLimitRequestsPerSecond(),
                config.getRateLimitBurstSize()
            );

            // Add whitelisted IPs
            String whitelistStr = config.getRateLimitWhitelistIps();
            if (whitelistStr != null && !whitelistStr.isEmpty()) {
                String[] ips = whitelistStr.split(",");
                for (String ip : ips) {
                    rateLimiter.addToWhitelist(ip.trim());
                }
            }
        }
    }

    public void interrupt(){
        System.out.println("Terminating servlet: " + servletNumber);
        run = false;
        gracefulShutdown();
    }

    private void gracefulShutdown() {
        System.out.println("Initiating graceful shutdown for servlet " + servletNumber);

        // Step 1: Stop accepting new connections
        run = false;

        // Step 2: Shutdown thread pool gracefully
        threadPool.shutdown();

        try {
            // Step 3: Wait for active requests to complete
            int timeoutSeconds = config.getShutdownTimeoutSeconds();
            System.out.println("Waiting up to " + timeoutSeconds + " seconds for active requests to complete...");

            boolean terminated = threadPool.awaitTermination(timeoutSeconds, TimeUnit.SECONDS);

            if (!terminated) {
                // Step 4: Force shutdown if timeout exceeded
                System.out.println("Timeout exceeded, forcing shutdown...");
                threadPool.shutdownNow();

                // Wait a bit more for forced shutdown
                threadPool.awaitTermination(5, TimeUnit.SECONDS);
            } else {
                System.out.println("All active requests completed successfully");
            }
        } catch (InterruptedException e) {
            System.err.println("Shutdown interrupted, forcing immediate shutdown");
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Step 5: Shutdown rate limiter
        if (rateLimiter != null) {
            rateLimiter.shutdown();
        }

        // Step 6: Close server socket if still open
        if (mainSocket != null && !mainSocket.isClosed()) {
            try {
                mainSocket.close();
            } catch (IOException e) {
                errorLog.log(Level.WARNING, "Error closing server socket", e);
            }
        }

        System.out.println("Servlet " + servletNumber + " shutdown complete");
    }

    @Override
    public void run() {
        setAuditLogHandler();
        System.out.println("The root folder is: " + webroot.toString() + "\r\n");

        String protocol = useTLS ? "HTTPS" : "HTTP";
        System.out.println("Starting " + protocol + " server on port " + port + " ...\r\n");

        try {
            // Create server socket (TLS or plain)
            if (useTLS && tlsManager != null) {
                mainSocket = tlsManager.createSSLServerSocket(port, config.getRequestQueueLimit());
                System.out.println("TLS/SSL enabled with secure cipher suites");
            } else {
                mainSocket = new ServerSocket(port, config.getRequestQueueLimit());
            }

            // Add socket timeout to periodically check run flag
            mainSocket.setSoTimeout(1000); // 1 second timeout

            do{
                System.out.print("\r\n");
                try {
                    Socket socket = mainSocket.accept();
                    try {
                        ProcessRequest newRequest = new ProcessRequest(webroot, socket, auditLog, config);
                        // Phase 5: Set rate limiter if enabled
                        if (rateLimiter != null) {
                            newRequest.setRateLimiter(rateLimiter);
                        }
                        threadPool.submit(newRequest);
                    } catch (RejectedExecutionException e) {
                        // Thread pool is shutdown, close socket and exit loop
                        socket.close();
                        break;
                    }
                } catch (SocketTimeoutException e) {
                    // Timeout occurred, loop will check run flag and continue
                    continue;
                }
            }while(run);

        } catch (IOException exception){
            errorLog.log(Level.SEVERE, "Couldn't start server", exception);
        } finally {
            if (mainSocket != null && !mainSocket.isClosed()) {
                try {
                    mainSocket.close();
                } catch (IOException e) {
                    errorLog.log(Level.WARNING, "Error closing server socket", e);
                }
            }
        }
    }

    private void setAuditLogHandler() {
        File logFile = new File(webroot.toString() + "/logs/server_log.log");
        try{
            fileHandler = new FileHandler(logFile.toString());
            auditLog.addHandler(fileHandler);
            SimpleFormatter formatter = new SimpleFormatter();
            fileHandler.setFormatter(formatter);
        }catch (Exception exception){
            System.out.println("The audit log file was not found.");
            System.out.println("The logger will write to the console only.");
        }
    }
    public boolean isRunning(){
        return run;
    }
    void getStatus(){
        System.out.println("Servlet " + servletNumber + " status is: " + (run ? "running" : "not running"));
        System.out.println("  Root directory: " + webroot.toString());
        if(run){
            System.out.println("  Listening on port: " + port + " (" + (useTLS ? "HTTPS" : "HTTP") + ")");
        } else{
            System.out.println("  Previously assigned port: " + port);
        }
    }

    public boolean isTLSEnabled() {
        return useTLS;
    }
}

