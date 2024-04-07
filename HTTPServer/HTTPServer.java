/*
Nick Ferraz (nferraz)
Prof: Dr. Kamyar Dezhgosha
CSC 583B: (Java) Network Programming
Assignment: Final Project -- HTTPServer.
 */
package HTTPServer;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Scanner;

class HTTPServer {

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;
    private int servletCount = 0;
    private ArrayList<Servlet> servlets = new ArrayList<Servlet>(1);
    private boolean interactiveMode = false;
    private Scanner scanner; // Fix #6: Single Scanner instance for entire program

    HTTPServer(){
        this.scanner = new Scanner(System.in);
    }

    public static void main(String[] args) {
        HTTPServer server = new HTTPServer();
        if(args.length > 0){
            server.handleArgs(args);
            return;
        }
        server.interactiveMode = true;
        String input = "";
        System.out.println("Enter a command or type 'help' for list of available commands.");
        while(!input.equals("exit")){

            input = server.scanner.nextLine();
            try {
                server.handleInput(input);
            } catch (IOException e) {
                System.out.println("Exiting server");
                break;
            }

        }
        server.scanner.close(); // Fix #6: Close scanner when done
    }

    private void handleArgs(String[] args) {
        if(args.length % 2 != 0){
            usage("argnum");
            return;
        }
        int length = args.length;
        int port;
        File webroot;
        for(int i = 0; i < length; i+=2){
            try{
                webroot = getWebroot(args[i]);
                port = Integer.parseInt(args[i+1]);
                if (port < MIN_PORT || port > MAX_PORT) {
                    usage("portrange");
                }
                createServlet(webroot, port, true);
            } catch (NumberFormatException exception){
                usage("porttype");
            } catch (IOException exception){
                System.err.println(exception);
            }
        }
    }

    private static void usage(String type) {
        switch(type){
            case "argnum":
                System.out.println("ERROR: incorrect number of arguments.");
                System.out.println("Usage: webserver_root_1 port_number_for_1 webserver_root_2 port_number_for_2 ... webserver_root_n port_number_for_n.");
                System.out.println("Example: C:\\Users\\username\\site_webroot_folder_1 80 C:\\Users\\username\\site_webroot_folder_2 81");
                break;
            case "porttype":
                System.out.println("ERROR: incorrect type for port. Expecting integer.");
                break;
            case "portrange":
                System.out.println("ERROR: port range must be between 1 and 65535.");
                break;
        }
    }

    private void createServlet(File webroot, int port, boolean start) throws IOException {
        port = getAvailablePort(port);
        Servlet servlet = new Servlet(webroot, port, servletCount);
        servlets.add(servlet);
        if(start){
            servlet.start();
        }
        servletCount += 1;
    }

    private void stopServlet(int servletNumber){
        try{
            servlets.get(servletNumber).interrupt();
        }catch(IndexOutOfBoundsException exception){
            System.out.println("ERROR: Index is out of bounds. There are " + servlets.size() + " servlets.");
        }
    }
    private void stopServlet(){
        servlets.get(servletCount - 1).interrupt();
    }
    private void restartServlet(int servletNumber){
        try{
            Servlet servlet = servlets.get(servletNumber);
            if(!servlet.isRunning()){
                //Check if port is still available otherwise get a new port.
                int newPort = getAvailablePort(servlet.port);
                servlet = new Servlet(servlet.webroot, newPort, servletNumber);
                servlet.start();
                servlets.set(servletNumber, servlet);
            } else{
                System.out.println("Servlet " +servletNumber + " is already running.");
            }
        } catch(IndexOutOfBoundsException exception){
            System.err.println(exception);
        } catch(IOException exception){
            System.err.println("Error restarting servlet: " + exception.getMessage());
        }

    }

    private void handleInput(String input) throws IOException {
        if(input.equals("")){return;}
        int servletNumber= 0;
        int port = 0;
        File webroot = null;
        boolean start = true;
        String[] params = input.split(" ");
        int paramLength = params.length;
        //Parse the input.
        // Fix #6: Use shared scanner instance instead of creating new ones
        if(input.equals("exit")){
            throw new IOException("Server shutting down");
        } else if(input.equals("help")){
            listCommands();
        } else if(params[0].equals("stop") ){
            if(paramLength == 1) {
                stopServlet();
            }else if(paramLength == 2){
                servletNumber = Integer.parseInt(params[1]);
                stopServlet(servletNumber);
            }
        }else if(params[0].equals("restart") && paramLength == 2) {
            servletNumber = Integer.parseInt(params[1]);
            restartServlet(servletNumber);
        }else if(params[0].equals("create")){
            if(paramLength == 1){
                System.out.println("Please enter the web root directory for the new servlet: ");
                webroot = getWebroot(scanner.nextLine());
                System.out.println("Please enter the port number for the new servlet: ");
                port = Integer.parseInt(scanner.nextLine());
            } else if(paramLength == 2){
                webroot = getWebroot(params[1]);
                port = getAvailablePort(80);
            } else if(paramLength == 3){
                webroot = getWebroot(params[1]);
                port = Integer.parseInt(params[2]);
            } else if(paramLength == 4){
                webroot = getWebroot(params[1]);
                port = Integer.parseInt(params[2]);
                start = Boolean.parseBoolean(params[3]);
            }
            createServlet(webroot, port, start);
            return;
        }else if(input.equals("list servlets")){
            listServlets();
        }
        else{
            System.out.println("Invalid command. Type help for list of commands and usage.");
        }
    }

    private void listServlets() {
        for(Servlet servlet: servlets){
            servlet.getStatus();
        }
    }

    //Check if port is available otherwise get the next available starting from 80.
    private int getAvailablePort(int port) throws IOException {
        int originalPort = port;
        if (port < MIN_PORT || port > MAX_PORT) {
            if(!interactiveMode){
                throw new IllegalArgumentException("Port number is out of range");
            }
            usage("portrange");
            port = 80;
        }
        ServerSocket socket;
        for (int p = port; p <= MAX_PORT; p++) {
            try {
                socket = new ServerSocket(p);
                socket.close();
                return p;
            } catch (IOException ex) {
                continue;
            }
        }
        if( port > 0 && port < 65536 && (originalPort != port)){
            System.out.println("Port " + originalPort + " was not available.");
            System.out.println("This servlet will be assigned port " + port);
        }
        return 0;
    }
    private File getWebroot(String path) throws IOException {
        File webroot;
        webroot = new File(path);
        boolean isDirectory = webroot.isDirectory();
        if(!isDirectory && !interactiveMode){
            throw new IOException("Error: invalid directory - " + path);
        }
        // Fix #6: Use shared scanner instance instead of creating new one
        while(!isDirectory && interactiveMode){
            System.out.println("The path provided does not point to a valid directory. Enter a valid path: ");
            path = scanner.nextLine();
            webroot = new File(path);
            isDirectory = webroot.isDirectory();
        }
        return webroot;
    }
    private static void listCommands() {
        System.out.println("exit    Exists the program.");
        System.out.println("stop [servlet_n|_]   If empty stops the last servlet to be created.");
        System.out.println("    Example:    stop 1  --- will stop servlet 1.");
        System.out.println("restart [servlet_n]  A pseudo-restart using the same parameters as the defunct servlet n.");
        System.out.println("    ");
        System.out.println("list servlets   Will print out the status, port number, and root directory of all servlets.");
        System.out.println("create   Crates a new servlet. Parameters: File web_root, int port, boolean start.");
        System.out.println("    If passed with fewer than 3 parameters will ask for parameters interactively.");
        System.out.println("    To create a servlet but not start it right away pass all three parameters with the last one false");
        System.out.println("    Example 1:    create C:\\Users\\john\\webroot 80 false");
        System.out.println("    Example 2:    create C:\\Users\\jane\\webroot 90 [This starts the servlet right away.");
        System.out.println("");
    }
}