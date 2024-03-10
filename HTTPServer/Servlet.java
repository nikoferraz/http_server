package HTTPServer;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.net.SocketTimeoutException;

public class Servlet extends Thread{

    private static final int THREAD_POOL_SIZE = 20;
    private static final int REQUEST_QUEUE_LIMIT = 100;
    private static final long REQUEST_TIMEOUT_MINUTES = 5;
    private ExecutorService thread_pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    File webroot;
    int port = 0;
    private Boolean run = true;
    private int servlet_number;
    private FileHandler fileHandler;
    private ServerSocket mainSocket = null;
    private Logger auditLog = Logger.getLogger("requests");
    private Logger errorLog = Logger.getLogger("errors");


    public Servlet(File webroot, int port, int servlet_number) {
        this.webroot = webroot;
        this.servlet_number = servlet_number;
        this.port = port;
    }

    public void interrupt(){
        System.out.println("Terminating servlet: " + servlet_number);
        run = false;
        thread_pool.shutdownNow();
    }

    @Override
    public void run() {
        setAuditLogHandler();
        System.out.println("The root folder is: " + webroot.toString() + "\r\n");
        System.out.println("Starting server on port " + port + " ...\r\n");
        try (ServerSocket mainSocket = new ServerSocket(port, REQUEST_QUEUE_LIMIT)) {
            // Fix #4: Add socket timeout to periodically check run flag
            mainSocket.setSoTimeout(1000); // 1 second timeout
            do{
                System.out.print("\r\n");
                try {
                    Socket socket = mainSocket.accept();
                    try {
                        Runnable new_request = new ProcessRequest(webroot, socket, auditLog);
                        thread_pool.submit(new_request);
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
            errorLog.log(Level.SEVERE, "Couldn't start servers ", exception);
        }
        return;
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
        System.out.println("Servlet " + servlet_number + " status is: " + (run ? "running" : "not running"));
        System.out.println("  Root directory: " + webroot.toString());
        if(run){
            System.out.println("  Listening on port: " + port);
        } else{
            System.out.println("  Previously assigned port: " + port);
        }
    }
}

