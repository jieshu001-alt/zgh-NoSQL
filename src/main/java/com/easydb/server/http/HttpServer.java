package com.easydb.server.http;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HttpServer {

    private final int port;
    private final ExecutorService threadPool;
    private final RestDispatcher dispatcher;
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    public HttpServer(int port) {
        this.port = port;
        this.threadPool = Executors.newCachedThreadPool();
        this.dispatcher = new RestDispatcher();
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        System.out.println("HTTP server started on port " + port);
        
        new Thread(() -> {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    threadPool.submit(() -> dispatcher.dispatch(socket));
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
