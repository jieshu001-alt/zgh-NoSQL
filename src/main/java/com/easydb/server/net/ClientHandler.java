package com.easydb.server.net;

import com.easydb.common.constants.Constants;
import com.easydb.server.bootstrap.ServerBootstrap;
import com.easydb.server.cluster.ClusterConfig;
import com.easydb.server.cluster.Node;
import com.easydb.server.cluster.ReplicationManager;
import com.easydb.server.engine.DefaultStoreEngine;
import com.easydb.server.engine.StoreEngine;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClientHandler implements Runnable {

    private final Socket socket;
    private final StoreEngine storeEngine;
    private static volatile ServerBootstrap serverBootstrap;

    public ClientHandler(Socket socket) {
        this.socket = socket;
        this.storeEngine = DefaultStoreEngine.getInstance();
    }

    public static void setServerBootstrap(ServerBootstrap bootstrap) {
        serverBootstrap = bootstrap;
    }

    @Override
    public void run() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), Constants.ENCODING));
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), Constants.ENCODING), true)) {
            
            String line;
            while ((line = reader.readLine()) != null) {
                String response = processRequest(line);
                writer.write(response);
                writer.flush();
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

    private String processRequest(String line) {
        String[] parts = RequestDecoder.decode(line);
        if (parts == null || parts.length == 0) {
            return RequestDecoder.buildErrorResponse("Invalid command");
        }
        
        String command = parts[0].toUpperCase();
        
        switch (command) {
            case Constants.COMMAND_SET:
                return handleSet(parts);
            case Constants.COMMAND_GET:
                return handleGet(parts);
            case Constants.COMMAND_DEL:
                return handleDel(parts);
            case Constants.COMMAND_KEYS:
                return handleKeys(parts);
            case Constants.COMMAND_EXISTS:
                return handleExists(parts);
            case Constants.COMMAND_MSET:
                return handleMSet(parts);
            case Constants.COMMAND_MGET:
                return handleMGet(parts);
            case Constants.COMMAND_MDEL:
                return handleMDel(parts);
            case Constants.COMMAND_CREATE:
                return handleCreate(parts);
            case Constants.COMMAND_DROP:
                return handleDrop(parts);
            case Constants.COMMAND_COLLECTIONS:
                return handleCollections(parts);
            default:
                return RequestDecoder.buildErrorResponse("Unknown command: " + command);
        }
    }

    private String handleSet(String[] parts) {
        if (parts.length < 3) {
            return RequestDecoder.buildErrorResponse("Usage: SET key value");
        }
        
        String key = parts[1];
        StringBuilder valueBuilder = new StringBuilder();
        for (int i = 2; i < parts.length; i++) {
            if (i > 2) {
                valueBuilder.append(" ");
            }
            valueBuilder.append(parts[i]);
        }
        
        String command = "SET " + key + " " + valueBuilder.toString();
        
        if (!checkAndReplicate(command)) {
            return Constants.ERROR_PREFIX + "Failed to replicate to master" + Constants.LINE_SEPARATOR;
        }
        
        storeEngine.set(key, valueBuilder.toString());
        
        replicateToSlaves(command);
        
        return Constants.OK_RESPONSE + Constants.LINE_SEPARATOR;
    }

    private String handleGet(String[] parts) {
        if (parts.length < 2) {
            return RequestDecoder.buildErrorResponse("Usage: GET key");
        }
        
        String value = storeEngine.get(parts[1]);
        return RequestDecoder.buildResponse(value);
    }

    private String handleDel(String[] parts) {
        if (parts.length < 2) {
            return RequestDecoder.buildErrorResponse("Usage: DEL key");
        }
        
        String key = parts[1];
        String command = "DEL " + key;
        
        if (!checkAndReplicate(command)) {
            return Constants.ERROR_PREFIX + "Failed to replicate to master" + Constants.LINE_SEPARATOR;
        }
        
        String result = storeEngine.del(key);
        
        replicateToSlaves(command);
        
        if (Constants.OK_RESPONSE.equals(result)) {
            return Constants.OK_RESPONSE + Constants.LINE_SEPARATOR;
        }
        return RequestDecoder.buildResponse(null);
    }

    private String handleKeys(String[] parts) {
        String pattern = "*";
        if (parts.length >= 2) {
            pattern = parts[1];
        }
        
        List<String> keys = storeEngine.keys(pattern);
        return RequestDecoder.buildKeysResponse(keys);
    }

    private String handleExists(String[] parts) {
        if (parts.length < 2) {
            return RequestDecoder.buildErrorResponse("Usage: EXISTS key");
        }
        
        boolean exists = storeEngine.exists(parts[1]);
        return exists ? "1" + Constants.LINE_SEPARATOR : "0" + Constants.LINE_SEPARATOR;
    }

    private String handleMSet(String[] parts) {
        if (parts.length < 4 || (parts.length - 1) % 2 != 0) {
            return RequestDecoder.buildErrorResponse("Usage: MSET key1 value1 key2 value2 ...");
        }
        
        Map<String, String> entries = new HashMap<>();
        StringBuilder commandBuilder = new StringBuilder("MSET");
        for (int i = 1; i < parts.length; i += 2) {
            String key = parts[i];
            String value = parts[i + 1];
            entries.put(key, value);
            commandBuilder.append(" ").append(key).append(" ").append(value);
        }
        
        String command = commandBuilder.toString();
        
        if (!checkAndReplicate(command)) {
            return Constants.ERROR_PREFIX + "Failed to replicate to master" + Constants.LINE_SEPARATOR;
        }
        
        storeEngine.batchSet(entries);
        
        replicateToSlaves(command);
        
        return Constants.OK_RESPONSE + Constants.LINE_SEPARATOR;
    }

    private String handleMGet(String[] parts) {
        if (parts.length < 2) {
            return RequestDecoder.buildErrorResponse("Usage: MGET key1 key2 ...");
        }
        
        List<String> keys = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            keys.add(parts[i]);
        }
        
        List<String> values = storeEngine.batchGet(keys);
        return RequestDecoder.buildMultiResponse(values);
    }

    private String handleMDel(String[] parts) {
        if (parts.length < 2) {
            return RequestDecoder.buildErrorResponse("Usage: MDEL key1 key2 ...");
        }
        
        List<String> keys = new ArrayList<>();
        StringBuilder commandBuilder = new StringBuilder("MDEL");
        for (int i = 1; i < parts.length; i++) {
            keys.add(parts[i]);
            commandBuilder.append(" ").append(parts[i]);
        }
        
        String command = commandBuilder.toString();
        
        if (!checkAndReplicate(command)) {
            return Constants.ERROR_PREFIX + "Failed to replicate to master" + Constants.LINE_SEPARATOR;
        }
        
        storeEngine.batchDel(keys);
        
        replicateToSlaves(command);
        
        return Constants.OK_RESPONSE + Constants.LINE_SEPARATOR;
    }

    private boolean checkAndReplicate(String command) {
        if (serverBootstrap == null || !serverBootstrap.isClusterEnabled()) {
            return true;
        }
        
        ClusterConfig config = serverBootstrap.getClusterConfig();
        if (config == null) {
            return true;
        }
        
        Node master = config.getMaster();
        Node self = config.getSelfNode();
        
        if (master == null) {
            return true;
        }
        
        if (self != null && self.getId().equals(master.getId())) {
            return true;
        }
        
        ReplicationManager replicationManager = serverBootstrap.getReplicationManager();
        if (replicationManager != null) {
            return replicationManager.replicateFromMaster(command);
        }
        
        return true;
    }

    private void replicateToSlaves(String command) {
        if (serverBootstrap == null || !serverBootstrap.isClusterEnabled()) {
            return;
        }
        
        ReplicationManager replicationManager = serverBootstrap.getReplicationManager();
        if (replicationManager != null) {
            replicationManager.replicateCommand(command);
        }
    }

    private String handleCreate(String[] parts) {
        if (parts.length < 2) {
            return RequestDecoder.buildErrorResponse("Usage: CREATE collection");
        }
        
        String collectionName = parts[1];
        String command = "CREATE " + collectionName;
        
        if (!checkAndReplicate(command)) {
            return Constants.ERROR_PREFIX + "Failed to replicate to master" + Constants.LINE_SEPARATOR;
        }
        
        try {
            storeEngine.createCollection(collectionName);
            
            replicateToSlaves(command);
            
            return Constants.OK_RESPONSE + Constants.LINE_SEPARATOR;
        } catch (IllegalArgumentException e) {
            return RequestDecoder.buildErrorResponse(e.getMessage());
        }
    }

    private String handleDrop(String[] parts) {
        if (parts.length < 2) {
            return RequestDecoder.buildErrorResponse("Usage: DROP collection");
        }
        
        String collectionName = parts[1];
        String command = "DROP " + collectionName;
        
        if (!checkAndReplicate(command)) {
            return Constants.ERROR_PREFIX + "Failed to replicate to master" + Constants.LINE_SEPARATOR;
        }
        
        String result = storeEngine.dropCollection(collectionName);
        
        if (!result.startsWith(Constants.ERROR_PREFIX)) {
            replicateToSlaves(command);
        }
        
        if (result.startsWith(Constants.ERROR_PREFIX)) {
            return result + Constants.LINE_SEPARATOR;
        }
        return result + Constants.LINE_SEPARATOR;
    }

    private String handleCollections(String[] parts) {
        List<String> collections = storeEngine.listCollections();
        return RequestDecoder.buildKeysResponse(collections);
    }
}
