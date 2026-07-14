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
                long delay = ClusterConfig.ELECTION_TIMEOUT_MIN + 
                    (long) (Math.random() * (ClusterConfig.ELECTION_TIMEOUT_MAX - ClusterConfig.ELECTION_TIMEOUT_MIN));
                Thread.sleep(delay);
                
                if (config.getMaster() != null && config.getMaster().isAlive()) {
                    electionInProgress = false;
                    return;
                }
                
                Node self = config.getSelfNode();
                if (self == null) {
                    electionInProgress = false;
                    return;
                }
                
                self.setRole(NodeRole.CANDIDATE);
                
                int votes = requestVotes();
                
                if (votes >= (config.getAliveNodeCount() + 1) / 2) {
                    becomeMaster();
                } else {
                    self.setRole(NodeRole.SLAVE);
                }
                
                electionInProgress = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                electionInProgress = false;
            }
        }).start();
    }

    private int requestVotes() {
        AtomicInteger votes = new AtomicInteger(1);
        
        for (Node node : config.getNodes()) {
            if (!node.getId().equals(config.getSelfId()) && node.isAlive()) {
                if (sendVoteRequest(node)) {
                    votes.incrementAndGet();
                }
            }
        }
        
        return votes.get();
    }

    private boolean sendVoteRequest(Node node) {
        try (Socket socket = new Socket(node.getHost(), node.getClusterPort());
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            
            writer.println("ELECT " + config.getSelfId());
            String response = reader.readLine();
            
            return response != null && response.startsWith("VOTE");
            
        } catch (IOException e) {
            node.markDead();
            return false;
        }
    }

    private void becomeMaster() {
        Node self = config.getSelfNode();
        if (self == null) {
            return;
        }
        
        self.setRole(NodeRole.MASTER);
        
        for (Node node : config.getNodes()) {
            if (!node.getId().equals(config.getSelfId())) {
                sendMasterAnnouncement(node);
            }
        }
        
        System.out.println("Node " + config.getSelfId() + " became MASTER!");
    }

    private void sendMasterAnnouncement(Node node) {
        try (Socket socket = new Socket(node.getHost(), node.getClusterPort());
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            
            writer.println("ACK " + config.getSelfId() + " MASTER");
            reader.readLine();
            
        } catch (IOException e) {
            node.markDead();
        }
    }

    public boolean isElectionInProgress() {
        return electionInProgress;
    }
}
