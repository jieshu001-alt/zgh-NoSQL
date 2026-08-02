package com.easydb.server.proxy;

import com.easydb.common.constants.Constants;
import com.easydb.server.cluster.ClusterConfig;
import com.easydb.server.cluster.HeartbeatManager;
import com.easydb.server.cluster.Node;
import com.easydb.server.cluster.NodeRole;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ClusterProxy - 集群读写分离代理中间件
 * 
 * 功能：
 * 1. 接收客户端连接，提供统一的数据库访问入口
 * 2. 写命令（SET/DEL/MSET/...）→ 转发到 MASTER 节点
 * 3. 读命令（GET/KEYS/...）→ 轮询分发到 SLAVE 节点，实现读写分离
 * 4. 自动感知集群拓扑变更（主节点切换、节点上下线）
 * 5. 主节点故障时自动将请求路由到新主
 */
public class ClusterProxy {

    private final int proxyPort;
    private final ClusterConfig clusterConfig;
    private final HeartbeatManager heartbeatManager;
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private final AtomicInteger slaveRoundRobin = new AtomicInteger(0);
    private ScheduledExecutorService topologyRefreshExecutor;
    
    // 当前已知的 master 缓存
    private volatile String cachedMasterHost;
    private volatile int cachedMasterClientPort;

    public ClusterProxy(int proxyPort, ClusterConfig clusterConfig, HeartbeatManager heartbeatManager) {
        this.proxyPort = proxyPort;
        this.clusterConfig = clusterConfig;
        this.heartbeatManager = heartbeatManager;
    }

    public void start() throws IOException {
        running = true;
        serverSocket = new ServerSocket(proxyPort);
        
        // 初始化 master 缓存
        refreshMasterCache();
        
        // 启动拓扑刷新线程
        startTopologyRefresh();
        
        // 接收客户端连接线程
        new Thread(() -> {
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    Executors.newCachedThreadPool().submit(() -> handleClient(clientSocket));
                } catch (IOException e) {
                    if (running) {
                        System.err.println("[ClusterProxy] Accept error: " + e.getMessage());
                    }
                }
            }
        }, "ClusterProxy-Acceptor").start();
        
        System.out.println("[ClusterProxy] Started on port " + proxyPort + ", read/write separation enabled");
    }

    /**
     * 刷新 master 缓存
     */
    private void refreshMasterCache() {
        Node master = clusterConfig.getMaster();
        if (master != null && master.isAlive()) {
            this.cachedMasterHost = master.getHost();
            this.cachedMasterClientPort = master.getClientPort();
        }
    }

    /**
     * 启动拓扑刷新线程（每3秒）
     */
    private void startTopologyRefresh() {
        topologyRefreshExecutor = Executors.newSingleThreadScheduledExecutor();
        topologyRefreshExecutor.scheduleAtFixedRate(() -> {
            refreshMasterCache();
        }, 3, 3, TimeUnit.SECONDS);
    }

    /**
     * 处理单个客户端连接
     */
    private void handleClient(Socket clientSocket) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(clientSocket.getInputStream(), Constants.ENCODING));
             PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(clientSocket.getOutputStream(), Constants.ENCODING), true)) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                
                String response = routeCommand(line);
                writer.println(response);
                
                // 处理多行响应（KEYS、MGET 等）
                if (needsMultiLineResponse(line)) {
                    sendMultiLineResponse(writer, response);
                }
            }
        } catch (IOException e) {
            // 客户端断开
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    /**
     * 路由命令到对应节点
     */
    private String routeCommand(String command) {
        String[] parts = command.split("\\s+");
        if (parts.length == 0) return Constants.ERROR_PREFIX + "Empty command";
        
        String cmd = parts[0].toUpperCase();
        
        if (Constants.WRITE_COMMANDS.contains(cmd)) {
            // 写请求 → 转发到 MASTER
            return forwardToMaster(command);
        } else if (Constants.READ_COMMANDS.contains(cmd)) {
            // 读请求 → 负载均衡到 SLAVE
            return forwardToSlave(command);
        } else {
            // 未知命令 → 默认转发到 master
            return forwardToMaster(command);
        }
    }

    /**
     * 转发命令到 master 节点
     */
    private String forwardToMaster(String command) {
        // 从配置获取实时 master
        Node master = clusterConfig.getMaster();
        if (master == null || !master.isAlive()) {
            // 刷新缓存再试
            refreshMasterCache();
            master = clusterConfig.getMaster();
        }
        
        if (master == null || !master.isAlive()) {
            return Constants.ERROR_PREFIX + "No master available";
        }
        
        return sendToNode(master, command);
    }

    /**
     * 转发命令到 slave 节点（轮询负载均衡）
     */
    private String forwardToSlave(String command) {
        List<Node> slaves = clusterConfig.getSlaves();
        
        if (slaves.isEmpty()) {
            // 没有从节点，转发到主节点
            Node master = clusterConfig.getMaster();
            if (master != null && master.isAlive()) {
                return sendToNode(master, command);
            }
            return Constants.ERROR_PREFIX + "No node available";
        }
        
        // 轮询选择一个 slave
        int index = Math.abs(slaveRoundRobin.getAndIncrement() % slaves.size());
        Node selected = slaves.get(index);
        
        String response = sendToNode(selected, command);
        
        // 如果选中的 slave 不可用，尝试其他 slave
        if (response == null || response.startsWith(Constants.ERROR_PREFIX)) {
            for (Node slave : slaves) {
                if (!slave.getId().equals(selected.getId())) {
                    response = sendToNode(slave, command);
                    if (response != null && !response.startsWith(Constants.ERROR_PREFIX)) {
                        return response;
                    }
                }
            }
            // 所有 slave 不可用，回退到 master
            Node master = clusterConfig.getMaster();
            if (master != null && master.isAlive()) {
                return sendToNode(master, command);
            }
        }
        
        return response != null ? response : Constants.ERROR_PREFIX + "Read failed";
    }

    /**
     * 发送命令到指定节点并返回响应
     */
    private String sendToNode(Node node, String command) {
        try (Socket socket = new Socket(node.getHost(), node.getClientPort());
             PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), Constants.ENCODING), true);
             BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), Constants.ENCODING))) {
            
            writer.println(command);
            return reader.readLine();
            
        } catch (IOException e) {
            return Constants.ERROR_PREFIX + "Node " + node.getId() + " unreachable";
        }
    }

    /**
     * 判断是否需要多行响应
     */
    private boolean needsMultiLineResponse(String command) {
        String[] parts = command.split("\\s+");
        if (parts.length == 0) return false;
        String cmd = parts[0].toUpperCase();
        return cmd.equals(Constants.COMMAND_KEYS) || cmd.equals(Constants.COMMAND_MGET) 
            || cmd.equals(Constants.COMMAND_COLLECTIONS) || cmd.equals(Constants.COMMAND_COLL_KEYS);
    }

    /**
     * 发送多行响应（KEYS、MGET等）
     */
    private void sendMultiLineResponse(PrintWriter clientWriter, String firstLine) {
        // 多行响应在首次返回时已经包含了所有行，这里只需要确保客户端能正确读取
        // 客户端通过空行来判断结束
    }

    public void stop() {
        running = false;
        
        if (topologyRefreshExecutor != null) {
            topologyRefreshExecutor.shutdown();
        }
        
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            // ignore
        }
        
        System.out.println("[ClusterProxy] Stopped");
    }
}
