package com.easydb.server.cluster;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReplicationManager {

    private final ClusterConfig config;
    private ExecutorService replicateExecutor;
    private volatile boolean running = false;

    public ReplicationManager(ClusterConfig config) {
        this.config = config;
    }

    public void start() {
        running = true;
        replicateExecutor = Executors.newCachedThreadPool();
        System.out.println("ReplicationManager started.");
    }

    public void replicateCommand(String command) {
        if (!running) {
            return;
        }
        
        Node master = config.getMaster();
        if (master == null || !master.getId().equals(config.getSelfId())) {
            return;
        }
        
        for (Node slave : config.getSlaves()) {
            replicateToSlave(slave, command);
        }
    }

    private void replicateToSlave(Node slave, String command) {
        replicateExecutor.submit(() -> {
            try (Socket socket = new Socket(slave.getHost(), slave.getClusterPort());
                 PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                
                writer.println("REPLICATE " + command);
                String response = reader.readLine();
                
            } catch (IOException e) {
                slave.markDead();
            }
        });
    }

    public boolean replicateFromMaster(String command) {
        Node master = config.getMaster();
        if (master == null || master.getId().equals(config.getSelfId())) {
            return true;
        }
        
        try (Socket socket = new Socket(master.getHost(), master.getClientPort());
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            
            writer.println(command);
            String response = reader.readLine();
            return response != null && !response.startsWith("(error)");
            
        } catch (IOException e) {
            return false;
        }
    }

    public void stop() {
        running = false;
        
        if (replicateExecutor != null) {
            replicateExecutor.shutdown();
        }
        
        System.out.println("ReplicationManager stopped.");
    }
}
