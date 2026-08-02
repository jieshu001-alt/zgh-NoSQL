package com.easydb.server.cluster;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

public class RoleElector {

    private final ClusterConfig config;
    private volatile boolean electionInProgress = false;

    public RoleElector(ClusterConfig config) {
        this.config = config;
    }

    public void startElection() {
        if (electionInProgress) {
            return;
        }
        
        electionInProgress = true;
        
        new Thread(() -> {
            try {
                // 随机延迟，避免多个节点同时发起选举
                long delay = ClusterConfig.ELECTION_TIMEOUT_MIN + 
                    (long) (Math.random() * (ClusterConfig.ELECTION_TIMEOUT_MAX - ClusterConfig.ELECTION_TIMEOUT_MIN));
                Thread.sleep(delay);
                
                // 检查是否已经有 master
                if (config.getMaster() != null && config.getMaster().isAlive()) {
                    electionInProgress = false;
                    return;
                }
                
                Node self = config.getSelfNode();
                if (self == null) {
                    electionInProgress = false;
                    return;
                }
                
                // 增加任期号
                long newTerm = config.incrementTerm();
                System.out.println("[RoleElector] Node " + config.getSelfId() + " starting election for term " + newTerm);
                
                // 设置自己为候选者
                self.setRole(NodeRole.CANDIDATE);
                self.updateHeartbeat();
                
                // 请求投票
                int votes = requestVotes(newTerm);
                
                // 计算多数派
                int majority = (config.getAliveNodeCount() + 1) / 2;
                
                if (votes >= majority) {
                    // 赢得选举
                    becomeMaster(newTerm);
                } else {
                    // 选举失败，转为从节点
                    self.setRole(NodeRole.SLAVE);
                    System.out.println("[RoleElector] Node " + config.getSelfId() + " election failed, got " + votes + " votes, majority is " + majority);
                }
                
                electionInProgress = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                electionInProgress = false;
            }
        }).start();
    }

    /**
     * 请求投票
     */
    private int requestVotes(long term) {
        AtomicInteger votes = new AtomicInteger(1); // 自己投自己一票
        
        for (Node node : config.getNodes()) {
            if (!node.getId().equals(config.getSelfId()) && node.isAlive()) {
                if (sendVoteRequest(node, term)) {
                    votes.incrementAndGet();
                }
            }
        }
        
        return votes.get();
    }

    /**
     * 发送投票请求
     */
    private boolean sendVoteRequest(Node node, long term) {
        try (Socket socket = new Socket(node.getHost(), node.getClusterPort());
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            
            writer.println("ELECT " + config.getSelfId() + " " + term);
            String response = reader.readLine();
            
            return response != null && response.startsWith("VOTE");
            
        } catch (IOException e) {
            node.markDead();
            return false;
        }
    }

    /**
     * 成为 master
     */
    private void becomeMaster(long term) {
        Node self = config.getSelfNode();
        if (self == null) {
            return;
        }
        
        self.setRole(NodeRole.MASTER);
        self.updateHeartbeat();
        
        System.out.println("[RoleElector] Node " + config.getSelfId() + " became MASTER for term " + term);
        
        // 通知其他节点
        for (Node node : config.getNodes()) {
            if (!node.getId().equals(config.getSelfId())) {
                sendMasterAnnouncement(node, term);
            }
        }
    }

    /**
     * 发送 master 公告
     */
    private void sendMasterAnnouncement(Node node, long term) {
        try (Socket socket = new Socket(node.getHost(), node.getClusterPort());
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            
            writer.println("ACK " + config.getSelfId() + " MASTER " + term);
            reader.readLine();
            
        } catch (IOException e) {
            node.markDead();
        }
    }

    /**
     * 处理投票请求
     */
    public boolean handleVoteRequest(String candidateId, long term) {
        Node candidate = config.getNode(candidateId);
        if (candidate == null) {
            return false;
        }
        
        Node currentMaster = config.getMaster();
        
        // 如果收到更高任期的请求，更新自己的任期
        if (term > config.getCurrentTerm()) {
            config.setCurrentTerm(term);
            config.resetVote();
            
            // 如果当前有 master，使其失效
            if (currentMaster != null) {
                currentMaster.setRole(NodeRole.SLAVE);
            }
        }
        
        // 如果任期相同且还没有投票，或者收到更高任期的请求
        if (term >= config.getCurrentTerm()) {
            if (config.getVotedFor() == null || config.getVotedFor().equals(candidateId)) {
                config.setVotedFor(candidateId);
                System.out.println("[RoleElector] Node " + config.getSelfId() + " voted for " + candidateId + " in term " + term);
                return true;
            }
        }
        
        return false;
    }

    /**
     * 处理 master 公告
     */
    public void handleMasterAnnouncement(String masterId, long term) {
        if (term >= config.getCurrentTerm()) {
            boolean wasMaster = config.isSelfMaster();
            
            config.setCurrentTerm(term);
            config.resetVote();
            
            // 更新 master 角色
            Node master = config.getNode(masterId);
            if (master != null) {
                master.setRole(NodeRole.MASTER);
                master.updateHeartbeat();
            }
            
            // 设置自己为从节点（如果不是 master）
            Node self = config.getSelfNode();
            if (self != null && !self.getId().equals(masterId)) {
                self.setRole(NodeRole.SLAVE);
            }
            
            System.out.println("[RoleElector] Node " + config.getSelfId() + " acknowledged " + masterId + " as MASTER for term " + term);
        }
    }

    public boolean isElectionInProgress() {
        return electionInProgress;
    }
}