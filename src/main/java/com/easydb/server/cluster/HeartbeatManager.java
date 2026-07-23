package com.easydb.server.cluster;

import com.easydb.common.constants.Constants;
import com.easydb.server.engine.DefaultStoreEngine;
import com.easydb.server.engine.StoreEngine;
import com.easydb.server.net.RequestDecoder;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HeartbeatManager {

    private final ClusterConfig config;
    private final RoleElector roleElector;
    private ServerSocket clusterServerSocket;
    private ScheduledExecutorService heartbeatExecutor;
    private ScheduledExecutorService monitorExecutor;
    private volatile boolean running = false;

    public HeartbeatManager(ClusterConfig config) {
        this.config = config;
        this.roleElector = new RoleElector(config);
    }

    public void start() throws IOException {
        running = true;
        
        startClusterServer();
        startHeartbeatSender();
        startMonitor();
        
        System.out.println("HeartbeatManager started on cluster port " + config.getSelfClusterPort());
    }

    private void startClusterServer() throws IOException {
        clusterServerSocket = new ServerSocket(config.getSelfClusterPort());
        
        new Thread(() -> {
            while (running) {
                try {
                    Socket socket = clusterServerSocket.accept();
                    handleClusterMessage(socket);
                } catch (IOException e) {
                    if (!running) {
                        break;
                    }
                }
            }
        }).start();
    }

    private void handleClusterMessage(Socket socket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true)) {
            
            String line;
            while ((line = reader.readLine()) != null) {
                processClusterMessage(line, writer);
            }
        } catch (IOException e) {
            // ignore
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    private void processClusterMessage(String message, PrintWriter writer) {
        if (message == null || message.isEmpty()) {
            return;
        }
        
        String[] parts = message.split("\\s+");
        if (parts.length < 2) {
            return;
        }
        
        String command = parts[0].toUpperCase();
        
        switch (command) {
            case "HEARTBEAT":
                handleHeartbeat(parts);
                writer.println("OK");
                break;
            case "PING":
                writer.println("PONG " + config.getSelfId() + " " + config.getSelfNode().getRole() + " " + config.getCurrentTerm());
                break;
            case "JOIN":
                handleJoin(parts, writer);
                break;
            case "ELECT":
                handleElect(parts, writer);
                break;
            case "ACK":
                handleAck(parts);
                break;
            case "REPLICATE":
                handleReplicate(message, writer);
                break;
            case "SNAPSHOT":
                handleSnapshotRequest(writer);
                break;
            default:
                writer.println("UNKNOWN");
        }
    }

    private void handleHeartbeat(String[] parts) {
        if (parts.length >= 4) {
            String nodeId = parts[1];
            String role = parts[2];
            long term = Long.parseLong(parts[3]);
            
            Node node = config.getNode(nodeId);
            if (node != null) {
                node.updateHeartbeat();
                node.setRole(NodeRole.valueOf(role));
                
                // 更新任期
                if (term > config.getCurrentTerm()) {
                    config.setCurrentTerm(term);
                    config.resetVote();
                }
            }
        } else if (parts.length >= 3) {
            // 兼容旧格式
            String nodeId = parts[1];
            String role = parts[2];
            
            Node node = config.getNode(nodeId);
            if (node != null) {
                node.updateHeartbeat();
                node.setRole(NodeRole.valueOf(role));
            }
        }
    }

    private void handleJoin(String[] parts, PrintWriter writer) {
        if (parts.length >= 5) {
            String nodeId = parts[1];
            String host = parts[2];
            int clientPort = Integer.parseInt(parts[3]);
            int clusterPort = Integer.parseInt(parts[4]);
            
            Node newNode = new Node(nodeId, host, clientPort, clusterPort);
            config.addNode(newNode);
            
            Node master = config.getMaster();
            if (master != null) {
                writer.println("MASTER " + master.getId() + " " + master.getHost() + " " + master.getClientPort() + " " + config.getCurrentTerm());
            } else {
                writer.println("NO_MASTER");
            }
        }
    }

    private void handleElect(String[] parts, PrintWriter writer) {
        if (parts.length >= 3) {
            String candidateId = parts[1];
            long term = Long.parseLong(parts[2]);
            
            if (roleElector.handleVoteRequest(candidateId, term)) {
                writer.println("VOTE " + config.getSelfId());
            } else {
                writer.println("DECLINE");
            }
        } else if (parts.length >= 2) {
            // 兼容旧格式
            String candidateId = parts[1];
            Node candidate = config.getNode(candidateId);
            if (candidate != null) {
                Node currentMaster = config.getMaster();
                if (currentMaster == null || !currentMaster.isAlive()) {
                    writer.println("VOTE " + config.getSelfId());
                } else {
                    writer.println("DECLINE");
                }
            }
        }
    }

    private void handleAck(String[] parts) {
        if (parts.length >= 4) {
            String newMasterId = parts[1];
            String role = parts[2];
            long term = Long.parseLong(parts[3]);
            
            roleElector.handleMasterAnnouncement(newMasterId, term);
        } else if (parts.length >= 3) {
            // 兼容旧格式
            String newMasterId = parts[1];
            String role = parts[2];
            
            Node node = config.getNode(newMasterId);
            if (node != null) {
                node.setRole(NodeRole.valueOf(role));
                node.updateHeartbeat();
            }
            
            Node self = config.getSelfNode();
            if (self != null && !self.getId().equals(newMasterId)) {
                self.setRole(NodeRole.SLAVE);
            }
        }
    }

    private void handleReplicate(String message, PrintWriter writer) {
        int idx = message.indexOf(" ");
        if (idx > 0) {
            String command = message.substring(idx + 1);
            executeCommand(command);
            writer.println("OK");
        }
    }

    /**
     * 处理快照请求
     */
    private void handleSnapshotRequest(PrintWriter writer) {
        StoreEngine engine = DefaultStoreEngine.getInstance();
        List<String> keys = engine.keys("*");
        
        writer.println("SNAPSHOT_START " + keys.size());
        
        for (String key : keys) {
            String value = engine.get(key);
            if (value != null) {
                writer.println(key + " " + value);
            }
        }
        
        writer.println("SNAPSHOT_END");
        writer.flush();
    }

    private void executeCommand(String command) {
        String[] parts = RequestDecoder.decode(command);
        if (parts == null || parts.length == 0) {
            return;
        }

        String cmd = parts[0].toUpperCase();
        StoreEngine engine = DefaultStoreEngine.getInstance();

        try {
            switch (cmd) {
                case Constants.COMMAND_SET:
                    if (parts.length >= 3) {
                        String key = parts[1];
                        StringBuilder valueBuilder = new StringBuilder();
                        for (int i = 2; i < parts.length; i++) {
                            if (i > 2) {
                                valueBuilder.append(" ");
                            }
                            valueBuilder.append(parts[i]);
                        }
                        engine.set(key, valueBuilder.toString());
                    }
                    break;
                case Constants.COMMAND_DEL:
                    if (parts.length >= 2) {
                        engine.del(parts[1]);
                    }
                    break;
                case Constants.COMMAND_MSET:
                    if (parts.length >= 4 && (parts.length - 1) % 2 == 0) {
                        Map<String, String> entries = new HashMap<>();
                        for (int i = 1; i < parts.length; i += 2) {
                            entries.put(parts[i], parts[i + 1]);
                        }
                        engine.batchSet(entries);
                    }
                    break;
                case Constants.COMMAND_MDEL:
                    if (parts.length >= 2) {
                        List<String> keys = new ArrayList<>();
                        for (int i = 1; i < parts.length; i++) {
                            keys.add(parts[i]);
                        }
                        engine.batchDel(keys);
                    }
                    break;
                case Constants.COMMAND_CREATE:
                    if (parts.length >= 2) {
                        engine.createCollection(parts[1]);
                    }
                    break;
                case Constants.COMMAND_DROP:
                    if (parts.length >= 2) {
                        engine.dropCollection(parts[1]);
                    }
                    break;
            }
        } catch (Exception e) {
            System.err.println("[HeartbeatManager] Failed to execute command: " + e.getMessage());
        }
    }

    private void startHeartbeatSender() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            sendHeartbeats();
        }, 0, ClusterConfig.HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private void sendHeartbeats() {
        Node self = config.getSelfNode();
        if (self == null) {
            return;
        }
        
        for (Node node : config.getNodes()) {
            if (!node.getId().equals(config.getSelfId()) && node.isAlive()) {
                sendHeartbeatToNode(node);
            }
        }
    }

    private void sendHeartbeatToNode(Node node) {
        try (Socket socket = new Socket(node.getHost(), node.getClusterPort());
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            
            writer.println("HEARTBEAT " + config.getSelfId() + " " + config.getSelfNode().getRole() + " " + config.getCurrentTerm());
            reader.readLine();
            
        } catch (IOException e) {
            node.markDead();
        }
    }

    private void startMonitor() {
        monitorExecutor = Executors.newSingleThreadScheduledExecutor();
        monitorExecutor.scheduleAtFixedRate(() -> {
            checkMasterStatus();
        }, 0, ClusterConfig.HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private void checkMasterStatus() {
        Node master = config.getMaster();
        if (master == null || !master.isAlive()) {
            roleElector.startElection();
        }
    }

    public void stop() {
        running = false;
        
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdown();
        }
        
        if (monitorExecutor != null) {
            monitorExecutor.shutdown();
        }
        
        try {
            if (clusterServerSocket != null) {
                clusterServerSocket.close();
            }
        } catch (IOException e) {
            // ignore
        }
        
        System.out.println("HeartbeatManager stopped.");
    }

    public boolean joinCluster(String host, int clusterPort) {
        try (Socket socket = new Socket(host, clusterPort);
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            
            writer.println("JOIN " + config.getSelfId() + " " + config.getSelfHost() + " " + config.getSelfClientPort() + " " + config.getSelfClusterPort());
            String response = reader.readLine();
            
            if (response != null && response.startsWith("MASTER")) {
                String[] parts = response.split("\\s+");
                if (parts.length >= 5) {
                    String masterId = parts[1];
                    String masterHost = parts[2];
                    int masterClientPort = Integer.parseInt(parts[3]);
                    long term = Long.parseLong(parts[4]);
                    
                    Node masterNode = new Node(masterId, masterHost, masterClientPort, clusterPort);
                    masterNode.setRole(NodeRole.MASTER);
                    config.addNode(masterNode);
                    
                    // 更新任期
                    config.setCurrentTerm(term);
                    
                    // 设置自己为从节点
                    Node self = config.getSelfNode();
                    if (self != null) {
                        self.setRole(NodeRole.SLAVE);
                    }
                    
                    // 请求全量同步
                    syncFromMaster(masterHost, clusterPort);
                }
                return true;
            }
        } catch (IOException e) {
            System.err.println("[HeartbeatManager] Failed to join cluster: " + e.getMessage());
        }
        return false;
    }

    /**
     * 从 master 同步全量数据
     */
    private void syncFromMaster(String masterHost, int clusterPort) {
        System.out.println("[HeartbeatManager] Starting full sync from master " + masterHost + ":" + clusterPort);
        
        try (Socket socket = new Socket(masterHost, clusterPort);
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            
            writer.println("SNAPSHOT");
            
            String line;
            int count = 0;
            StoreEngine engine = DefaultStoreEngine.getInstance();
            
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("SNAPSHOT_START")) {
                    String[] parts = line.split("\\s+");
                    count = Integer.parseInt(parts[1]);
                    System.out.println("[HeartbeatManager] Syncing " + count + " keys");
                } else if (line.startsWith("SNAPSHOT_END")) {
                    break;
                } else {
                    int spaceIdx = line.indexOf(' ');
                    if (spaceIdx > 0) {
                        String key = line.substring(0, spaceIdx);
                        String value = line.substring(spaceIdx + 1);
                        engine.set(key, value);
                    }
                }
            }
            
            System.out.println("[HeartbeatManager] Full sync completed");
            
        } catch (IOException e) {
            System.err.println("[HeartbeatManager] Failed to sync from master: " + e.getMessage());
        }
    }
}