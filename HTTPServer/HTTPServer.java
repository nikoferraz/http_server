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

    private int servlet_count = 0;
    private ArrayList<Servlet> servlets = new ArrayList<Servlet>(1);
    private boolean interactive_mode = false;
    private Scanner scanner; // Fix #6: Single Scanner instance for entire program

    HTTPServer(){
        this.scanner = new Scanner(System.in);
    }

    public static void main(String[] args) throws IOException {
        HTTPServer server = new HTTPServer();
        if(args.length > 0){
            server.handleArgs(args);
            return;
        }
        server.interactive_mode = true;
        String input = "";
        System.out.println("Enter a command or type 'help' for list of available commands.");
        while(!input.equals("exit")){

            input = server.scanner.nextLine();
            server.handleInput(input);

        }
        server.scanner.close(); // Fix #6: Close scanner when done
        return;
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
                if (port < 1 || port > 65535) {
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
        return;
    }

    private void createServlet(File webroot, int port, Boolean start) throws IOException {
        port = getAvailablePort(port);
        Servlet servlet = new Servlet(webroot, port, servlet_count);
        servlets.add(servlet);
        if(!false){
            servlet.start();
        }
        servlet_count += 1;
        return;
    }

    private void stopServlet(int servlet_number){
        try{
            servlets.get(servlet_number).interrupt();
        }catch(IndexOutOfBoundsException exception){
            System.out.println("ERROR: Index is out of bounds. There are " + servlets.size() + " servlets.");
        }
    }
    private void stopServlet(){
        servlets.get(servlet_count - 1).interrupt();
    }
    private void restartServlet(int servlet_number){
        try{
            Servlet servlet = servlets.get(servlet_number);
            if(!servlet.isRunning()){
                //Check if port is still available otherwise get a new port.
                int new_port = getAvailablePort(servlet.port);
                servlet = new Servlet(servlet.webroot, new_port, servlet_number);
                servlet.start();
                servlets.set(servlet_number, servlet);
            } else{
                System.out.println("Servlet " +servlet_number + " is already running.");
            }
        } catch(IndexOutOfBoundsException exception){
            System.err.println(exception);
            return;
        }

    }

    private void handleInput(String input) throws IOException {
        if(input.equals("")){return;}
        int servlet_number= 0;
        int port = 0;
        File webroot = null;
        boolean start = true;
        String[] params = input.split(" ");
        int param_length = params.length;
        //Parse the input.
        // Fix #6: Use shared scanner instance instead of creating new ones
        if(input.equals("exit")){
            System.exit(0);
        } else if(input.equals("help")){
            listCommands();
        } else if(params[0].equals("stop") ){
            if(param_length == 1) {
                stopServlet();
            }else if(param_length == 2){
                servlet_number = Integer.parseInt(params[1]);
                stopServlet(servlet_number);
            }
        }else if(params[0].equals("restart") && param_length == 2) {
            servlet_number = Integer.parseInt(params[1]);
            restartServlet(servlet_number);
        }else if(params[0].equals("create")){
            if(param_length == 1){
                System.out.println("Please enter the web root directory for the new servlet: ");
                webroot = getWebroot(scanner.nextLine());
                System.out.println("Please enter the port number for the new servlet: ");
                port = Integer.parseInt(scanner.nextLine());
            } else if(param_length == 2){
                webroot = getWebroot(params[1]);
                port = getAvailablePort(80);
            } else if(param_length == 3){
                webroot = getWebroot(params[1]);
                port = Integer.parseInt(params[2]);
            } else if(param_length == 4){
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
        return;
    }

    private void listServlets() {
        for(Servlet servlet: servlets){
            servlet.getStatus();
        }
        return;
    }

    //Check if port is available otherwise get the next available starting from 80.
    private int getAvailablePort(int port) {
        int original_port = port;
        if (port < 1 || port > 65535) {
            if(!interactive_mode){
                System.out.println("ERROR: Port number is out of range.");
                System.exit(0);
            }
            usage("portrange");
            port = 80;
        }
        ServerSocket socket;
        for (int p = port; p <= 65535; p++) {
            try {
                socket = new ServerSocket(p);
                socket.close();
                return p;
            } catch (IOException ex) {
                continue;
            }
        }
        if( port > 0 && port < 65536 && (original_port != port)){
            System.out.println("Port " + original_port + " was not available.");
            System.out.println("This servlet will be assigned port " + port);
        }
        return 0;
    }
    private File getWebroot(String path){
        File webroot;
        webroot = new File(path);
        boolean isDirectory = webroot.isDirectory();
        if(!isDirectory && !interactive_mode){
            System.err.println("Error: invalid directory.");
            System.exit(0);
        }
        // Fix #6: Use shared scanner instance instead of creating new one
        while(!isDirectory && interactive_mode){
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