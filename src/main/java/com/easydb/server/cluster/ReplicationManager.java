package com.easydb.server.cluster;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ReplicationManager - 主从复制管理器
 * 
 * 负责将 Master 节点的 WAL 操作日志同步复制到所有 Slave 节点，
 * 保障副本数据一致性。Slave 收到 WAL 日志后先写入本地 WAL，
 * 再执行命令，确保 Slave 崩溃恢复后数据与 Master 保持一致。
 */
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
        System.out.println("[ReplicationManager] Started (WAL sync mode)");
    }

    /**
     * 复制 WAL 操作日志到所有 Slave 节点
     * 格式：REPLICATE <WAL原始命令>
     * Slave 收到后先写本地 WAL 再执行命令，保证数据一致性
     */
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
                
                // 发送 REPLICATE 命令（WAL 日志同步）
                writer.println("REPLICATE " + command);
                String response = reader.readLine();
                
            } catch (IOException e) {
                slave.markDead();
            }
        });
    }

    /**
     * 从 Master 转发命令（用于 Slave 代理 Master 的写请求）
     */
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
        
        System.out.println("[ReplicationManager] Stopped");
    }
}
