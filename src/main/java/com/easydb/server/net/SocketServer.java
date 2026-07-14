package com.easydb.server.net;

import com.easydb.server.bootstrap.ServerBootstrap;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SocketServer {

    private final int port;
    private final ExecutorService threadPool;
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    public SocketServer(int port) {
        this.port = port;
        this.threadPool = Executors.newCachedThreadPool();
    }

    public void setServerBootstrap(ServerBootstrap bootstrap) {
        ClientHandler.setServerBootstrap(bootstrap);
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        System.out.println("Socket server started on port " + port);
        
        new Thread(() -> {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    threadPool.submit(new ClientHandler(socket));
                } catch (IOException e) {
                    if (!running) {
                        break;
                    }
                }
            }
        }).start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            // ignore
        }
        threadPool.shutdown();
    }
}
