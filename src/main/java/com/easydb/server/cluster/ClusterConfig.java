package com.easydb.server.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class ClusterConfig {

    public static final int HEARTBEAT_INTERVAL = 1000;
    public static final int HEARTBEAT_TIMEOUT = 5000;
    public static final int ELECTION_TIMEOUT_MIN = 3000;
    public static final int ELECTION_TIMEOUT_MAX = 6000;

    private String selfId;
    private String selfHost;
    private int selfClientPort;
    private int selfClusterPort;
    private List<Node> nodes = new CopyOnWriteArrayList<>();
    
    // 任期相关
    private volatile long currentTerm = 0;
    private volatile String votedFor = null;

    public ClusterConfig(String selfId, String selfHost, int selfClientPort, int selfClusterPort) {
        this.selfId = selfId;
        this.selfHost = selfHost;
        this.selfClientPort = selfClientPort;
        this.selfClusterPort = selfClusterPort;
    }

    public String getSelfId() {
        return selfId;
    }

    public String getSelfHost() {
        return selfHost;
    }

    public int getSelfClientPort() {
        return selfClientPort;
    }

    public int getSelfClusterPort() {
        return selfClusterPort;
    }

    public List<Node> getNodes() {
        return nodes;
    }

    public void addNode(Node node) {
        if (!nodes.contains(node)) {
            nodes.add(node);
        }
    }

    public void removeNode(String nodeId) {
        nodes.removeIf(node -> node.getId().equals(nodeId));
    }

    public Node getNode(String nodeId) {
        return nodes.stream().filter(node -> node.getId().equals(nodeId)).findFirst().orElse(null);
    }

    public Node getMaster() {
        return nodes.stream().filter(node -> node.getRole() == NodeRole.MASTER && node.isAlive()).findFirst().orElse(null);
    }

    public List<Node> getSlaves() {
        return nodes.stream().filter(node -> node.getRole() == NodeRole.SLAVE && node.isAlive()).collect(Collectors.toList());
    }

    public int getAliveNodeCount() {
        return (int) nodes.stream().filter(Node::isAlive).count();
    }

    public boolean isSelfMaster() {
        Node self = getNode(selfId);
        return self != null && self.getRole() == NodeRole.MASTER;
    }

    public Node getSelfNode() {
        return getNode(selfId);
    }

    // ===== 任期相关方法 =====

    public long getCurrentTerm() {
        return currentTerm;
    }

    public void setCurrentTerm(long term) {
        this.currentTerm = term;
    }

    public String getVotedFor() {
        return votedFor;
    }

    public void setVotedFor(String nodeId) {
        this.votedFor = nodeId;
    }

    /**
     * 增加任期号
     */
    public long incrementTerm() {
        return ++currentTerm;
    }

    /**
     * 重置投票状态（进入新任期时调用）
     */
    public void resetVote() {
        this.votedFor = null;
    }
}