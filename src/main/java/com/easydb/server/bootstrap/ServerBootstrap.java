package com.easydb.server.bootstrap;

import com.easydb.common.constants.Constants;
import com.easydb.server.cluster.*;
import com.easydb.server.engine.DefaultStoreEngine;
import com.easydb.server.engine.StoreEngine;
import com.easydb.server.http.HttpServer;
import com.easydb.server.net.SocketServer;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ServerBootstrap {

    private SocketServer socketServer;
    private HttpServer httpServer;
    private ScheduledExecutorService monitorExecutor;
    private ClusterConfig clusterConfig;
    private HeartbeatManager heartbeatManager;
    private ReplicationManager replicationManager;
    private int clientPort;
    private int httpPort;
    private int clusterPort;
    private String nodeId;
    private boolean enableCluster;
    private String joinHost;
    private int joinClusterPort;

    public ServerBootstrap() {
        this(Constants.DEFAULT_SOCKET_PORT, Constants.DEFAULT_HTTP_PORT, Constants.DEFAULT_CLUSTER_PORT, 
             "node-1", false, null, 0);
    }

    public ServerBootstrap(int clientPort, int httpPort, int clusterPort, String nodeId) {
        this(clientPort, httpPort, clusterPort, nodeId, false, null, 0);
    }

    public ServerBootstrap(int clientPort, int httpPort, int clusterPort, String nodeId, 
                          boolean enableCluster, String joinHost, int joinClusterPort) {
        this.clientPort = clientPort;
        this.httpPort = httpPort;
        this.clusterPort = clusterPort;
        this.nodeId = nodeId;
        this.enableCluster = enableCluster;
        this.joinHost = joinHost;
        this.joinClusterPort = joinClusterPort;
    }

    public void start() throws IOException {
        socketServer = new SocketServer(clientPort);
        httpServer = new HttpServer(httpPort);
        
        socketServer.setServerBootstrap(this);
        socketServer.start();
        httpServer.start();
        
        if (enableCluster) {
            initCluster();
        }
        
        startMonitorThread();
        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stop();
        }));
        
        System.out.println("Easy-DB server started successfully!");
        System.out.println("Socket server running on port " + clientPort);
        System.out.println("HTTP server running on port " + httpPort);
        
        if (enableCluster) {
            System.out.println("Cluster enabled, running on port " + clusterPort);
            System.out.println("Node ID: " + nodeId);
        }
    }

    private void initCluster() throws IOException {
        clusterConfig = new ClusterConfig(nodeId, Constants.DEFAULT_HOST, clientPort, clusterPort);
        
        Node selfNode = new Node(nodeId, Constants.DEFAULT_HOST, clientPort, clusterPort);
        clusterConfig.addNode(selfNode);
        
        heartbeatManager = new HeartbeatManager(clusterConfig);
        replicationManager = new ReplicationManager(clusterConfig);
        
        heartbeatManager.start();
        replicationManager.start();
        
        if (joinHost != null && joinClusterPort > 0) {
            if (heartbeatManager.joinCluster(joinHost, joinClusterPort)) {
                System.out.println("Successfully joined cluster at " + joinHost + ":" + joinClusterPort);
            } else {
                selfNode.setRole(NodeRole.MASTER);
                System.out.println("No existing cluster found, becoming MASTER");
            }
        } else {
            selfNode.setRole(NodeRole.MASTER);
            System.out.println("No join host specified, becoming MASTER");
        }
    }

    private void startMonitorThread() {
        monitorExecutor = Executors.newSingleThreadScheduledExecutor();
        StoreEngine storeEngine = DefaultStoreEngine.getInstance();
        
        monitorExecutor.scheduleAtFixedRate(() -> {
            int size = storeEngine.size();
            System.out.println("[Monitor] Current key count in memory: " + size);
            
            if (enableCluster && clusterConfig != null) {
                Node master = clusterConfig.getMaster();
                Node self = clusterConfig.getSelfNode();
                System.out.println("[Cluster] Self role: " + (self != null ? self.getRole() : "UNKNOWN"));
                System.out.println("[Cluster] Current MASTER: " + (master != null ? master.getId() : "NONE"));
                System.out.println("[Cluster] Alive nodes: " + clusterConfig.getAliveNodeCount());
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    public void stop() {
        if (monitorExecutor != null) {
            monitorExecutor.shutdown();
        }
        
        if (heartbeatManager != null) {
            heartbeatManager.stop();
        }
        
        if (replicationManager != null) {
            replicationManager.stop();
        }
        
        if (socketServer != null) {
            socketServer.stop();
        }
        
        if (httpServer != null) {
            httpServer.stop();
        }
        
        DefaultStoreEngine.getInstance().shutdown();
        
        System.out.println("Easy-DB server stopped.");
    }

    public ClusterConfig getClusterConfig() {
        return clusterConfig;
    }

    public ReplicationManager getReplicationManager() {
        return replicationManager;
    }

    public boolean isClusterEnabled() {
        return enableCluster;
    }
}
