package com.easydb.server.cluster;

public class Node {

    private String id;
    private String host;
    private int clientPort;
    private int clusterPort;
    private NodeRole role;
    private volatile long lastHeartbeat;
    private volatile boolean alive;

    public Node(String id, String host, int clientPort, int clusterPort) {
        this.id = id;
        this.host = host;
        this.clientPort = clientPort;
        this.clusterPort = clusterPort;
        this.role = NodeRole.SLAVE;
        this.lastHeartbeat = System.currentTimeMillis();
        this.alive = true;
    }

    public String getId() {
        return id;
    }

    public String getHost() {
        return host;
    }

    public int getClientPort() {
        return clientPort;
    }

    public int getClusterPort() {
        return clusterPort;
    }

    public NodeRole getRole() {
        return role;
    }

    public void setRole(NodeRole role) {
        this.role = role;
    }

    public long getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void updateHeartbeat() {
        this.lastHeartbeat = System.currentTimeMillis();
        this.alive = true;
    }

    public boolean isAlive() {
        return alive && (System.currentTimeMillis() - lastHeartbeat) < ClusterConfig.HEARTBEAT_TIMEOUT;
    }

    public void markDead() {
        this.alive = false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Node node = (Node) o;
        return id.equals(node.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Node{" +
                "id='" + id + '\'' +
                ", host='" + host + '\'' +
                ", clientPort=" + clientPort +
                ", clusterPort=" + clusterPort +
                ", role=" + role +
                ", alive=" + alive +
                '}';
    }
}
