package com.easydb;

import com.easydb.client.cli.CliSession;
import com.easydb.client.gui.GuiClient;
import com.easydb.client.shell.ShellClient;
import com.easydb.common.constants.Constants;
import com.easydb.server.bootstrap.ServerBootstrap;

import javax.swing.*;
import java.io.IOException;

public class Launcher {

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String mode = args[0];
        
        switch (mode) {
            case "--server":
                startServer(args);
                break;
            case "--cli":
                startCli();
                break;
            case "--gui":
                startGui();
                break;
            case "--shell":
                startShell(args);
                break;
            default:
                printUsage();
        }
    }

    private static void startServer(String[] args) {
        try {
            int clientPort = Constants.DEFAULT_SOCKET_PORT;
            int httpPort = Constants.DEFAULT_HTTP_PORT;
            int clusterPort = Constants.DEFAULT_CLUSTER_PORT;
            String nodeId = "node-1";
            boolean enableCluster = false;
            String joinHost = null;
            int joinClusterPort = 0;

            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--port":
                        clientPort = Integer.parseInt(args[++i]);
                        break;
                    case "--http-port":
                        httpPort = Integer.parseInt(args[++i]);
                        break;
                    case "--cluster-port":
                        clusterPort = Integer.parseInt(args[++i]);
                        break;
                    case "--node-id":
                        nodeId = args[++i];
                        break;
                    case "--cluster":
                        enableCluster = true;
                        break;
                    case "--join":
                        String[] joinParts = args[++i].split(":");
                        joinHost = joinParts[0];
                        joinClusterPort = Integer.parseInt(joinParts[1]);
                        enableCluster = true;
                        break;
                }
            }

            ServerBootstrap bootstrap = new ServerBootstrap(clientPort, httpPort, clusterPort, nodeId, 
                                                           enableCluster, joinHost, joinClusterPort);
            bootstrap.start();
            
            Thread.currentThread().join();
        } catch (IOException e) {
            System.err.println("Failed to start server: " + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("Invalid server arguments: " + e.getMessage());
            printServerUsage();
            System.exit(1);
        }
    }

    private static void startCli() {
        CliSession cli = new CliSession();
        cli.start();
    }

    private static void startGui() {
        SwingUtilities.invokeLater(() -> {
            new GuiClient().setVisible(true);
        });
    }

    private static void startShell(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: --shell <command> [args...]");
            System.exit(1);
        }
        
        String[] shellArgs = new String[args.length - 1];
        System.arraycopy(args, 1, shellArgs, 0, shellArgs.length);
        
        ShellClient.main(shellArgs);
    }

    private static void printUsage() {
        System.out.println("Easy-DB - Distributed NoSQL Database System");
        System.out.println();
        System.out.println("Usage: java -jar easy-db.jar [option]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --server      Start the Easy-DB server");
        System.out.println("  --cli         Start interactive command-line interface");
        System.out.println("  --gui         Start GUI client (Swing)");
        System.out.println("  --shell       Execute a single command (e.g., --shell SET name zhangsan)");
        System.out.println();
        System.out.println("Server options:");
        System.out.println("  --server --port <port>              Set client socket port (default: 8092)");
        System.out.println("  --server --http-port <port>         Set HTTP port (default: 8093)");
        System.out.println("  --server --cluster                  Enable cluster mode");
        System.out.println("  --server --cluster-port <port>      Set cluster communication port (default: 8094)");
        System.out.println("  --server --node-id <id>             Set node ID (default: node-1)");
        System.out.println("  --server --join <host:port>         Join an existing cluster");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar easy-db.jar --server");
        System.out.println("  java -jar easy-db.jar --server --cluster --node-id node-1");
        System.out.println("  java -jar easy-db.jar --server --cluster --node-id node-2 --join localhost:8094");
        System.out.println("  java -jar easy-db.jar --cli");
        System.out.println("  java -jar easy-db.jar --shell GET name");
    }

    private static void printServerUsage() {
        System.out.println("Server usage:");
        System.out.println("  --server [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --port <port>              Set client socket port (default: 8092)");
        System.out.println("  --http-port <port>         Set HTTP port (default: 8093)");
        System.out.println("  --cluster                  Enable cluster mode");
        System.out.println("  --cluster-port <port>      Set cluster communication port (default: 8094)");
        System.out.println("  --node-id <id>             Set node ID (default: node-1)");
        System.out.println("  --join <host:port>         Join an existing cluster");
    }
}
