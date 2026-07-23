package com.easydb.server.engine;

import com.easydb.common.constants.Constants;
import com.easydb.server.engine.disk.WalManager;
import com.easydb.server.engine.lsm.LSMTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultStoreEngine implements StoreEngine {

    private static volatile DefaultStoreEngine instance;
    
    private final LSMTree lsmTree = new LSMTree();
    private final WalManager walManager;
    private final Set<String> collections = ConcurrentHashMap.newKeySet();
    
    // 复制回调接口
    public interface ReplicationCallback {
        void onWrite(String command);
    }
    
    private volatile ReplicationCallback replicationCallback;

    private DefaultStoreEngine() {
        this.walManager = new WalManager();
        replayWal();
    }

    public static DefaultStoreEngine getInstance() {
        if (instance == null) {
            synchronized (DefaultStoreEngine.class) {
                if (instance == null) {
                    instance = new DefaultStoreEngine();
                }
            }
        }
        return instance;
    }

    /**
     * 设置复制回调
     */
    public void setReplicationCallback(ReplicationCallback callback) {
        this.replicationCallback = callback;
    }

    private void replayWal() {
        List<String[]> records = walManager.replay();
        for (String[] record : records) {
            if (record.length == 0) {
                continue;
            }
            
            String command = record[0].toUpperCase();
            
            switch (command) {
                case Constants.COMMAND_SET:
                    if (record.length >= 3) {
                        lsmTree.put(record[1], record[2]);
                    }
                    break;
                case Constants.COMMAND_DEL:
                    if (record.length >= 2) {
                        lsmTree.delete(record[1]);
                    }
                    break;
                case Constants.COMMAND_MSET:
                    if (record.length >= 4 && (record.length - 1) % 2 == 0) {
                        for (int i = 1; i < record.length; i += 2) {
                            String key = record[i];
                            String value = record[i + 1];
                            lsmTree.put(key, value);
                        }
                    }
                    break;
                case Constants.COMMAND_MDEL:
                    if (record.length >= 2) {
                        for (int i = 1; i < record.length; i++) {
                            lsmTree.delete(record[i]);
                        }
                    }
                    break;
                case Constants.COMMAND_CREATE:
                    if (record.length >= 2) {
                        collections.add(record[1]);
                    }
                    break;
                case Constants.COMMAND_DROP:
                    if (record.length >= 2) {
                        collections.remove(record[1]);
                    }
                    break;
            }
        }
        walManager.clear();
    }

    private void replicate(String command) {
        if (replicationCallback != null) {
            replicationCallback.onWrite(command);
        }
    }

    @Override
    public String set(String key, String value) {
        walManager.write(Constants.COMMAND_SET, key, value);
        lsmTree.put(key, value);
        
        // 触发实时复制
        replicate(Constants.COMMAND_SET + " " + key + " " + value);
        
        return Constants.OK_RESPONSE;
    }

    @Override
    public String get(String key) {
        return lsmTree.get(key);
    }

    @Override
    public String del(String key) {
        walManager.write(Constants.COMMAND_DEL, key, null);
        boolean existed = lsmTree.contains(key);
        lsmTree.delete(key);
        
        // 触发实时复制
        replicate(Constants.COMMAND_DEL + " " + key);
        
        return existed ? Constants.OK_RESPONSE : Constants.NULL_VALUE;
    }

    @Override
    public List<String> keys(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            pattern = "*";
        }
        
        if (pattern.equals("*")) {
            return lsmTree.getKeys();
        }
        
        if (pattern.endsWith("*") && !pattern.substring(0, pattern.length() - 1).contains("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return lsmTree.getKeysWithPrefix(prefix);
        }
        
        return lsmTree.getKeys();
    }

    @Override
    public boolean exists(String key) {
        return lsmTree.contains(key);
    }

    @Override
    public int size() {
        return lsmTree.getKeys().size();
    }

    @Override
    public void shutdown() {
        lsmTree.close();
        walManager.close();
    }

    @Override
    public void batchSet(Map<String, String> entries) {
        walManager.batchWrite(Constants.COMMAND_MSET, entries);
        
        StringBuilder cmdBuilder = new StringBuilder(Constants.COMMAND_MSET);
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            lsmTree.put(entry.getKey(), entry.getValue());
            cmdBuilder.append(" ").append(entry.getKey()).append(" ").append(entry.getValue());
        }
        
        // 触发实时复制
        replicate(cmdBuilder.toString());
    }

    @Override
    public List<String> batchGet(List<String> keys) {
        List<String> results = new ArrayList<>();
        for (String key : keys) {
            String value = lsmTree.get(key);
            results.add(value);
        }
        return results;
    }

    @Override
    public void batchDel(List<String> keys) {
        walManager.batchWriteDel(Constants.COMMAND_MDEL, keys);
        
        StringBuilder cmdBuilder = new StringBuilder(Constants.COMMAND_MDEL);
        for (String key : keys) {
            lsmTree.delete(key);
            cmdBuilder.append(" ").append(key);
        }
        
        // 触发实时复制
        replicate(cmdBuilder.toString());
    }

    @Override
    public void createCollection(String name) {
        if (!isValidCollectionName(name)) {
            throw new IllegalArgumentException("Invalid collection name: " + name);
        }
        walManager.write(Constants.COMMAND_CREATE, name, null);
        collections.add(name);
        
        // 触发实时复制
        replicate(Constants.COMMAND_CREATE + " " + name);
    }

    @Override
    public String dropCollection(String name) {
        if (!collections.contains(name)) {
            return Constants.ERROR_PREFIX + "Collection not found: " + name;
        }
        
        List<String> keysInCollection = keysInCollection(name);
        if (!keysInCollection.isEmpty()) {
            return Constants.ERROR_PREFIX + "Collection is not empty: " + name;
        }
        
        walManager.write(Constants.COMMAND_DROP, name, null);
        collections.remove(name);
        
        // 触发实时复制
        replicate(Constants.COMMAND_DROP + " " + name);
        
        return Constants.OK_RESPONSE;
    }

    @Override
    public List<String> listCollections() {
        return new ArrayList<>(collections);
    }

    @Override
    public List<String> keysInCollection(String collectionName) {
        String prefix = collectionName + Constants.COLLECTION_SEPARATOR;
        List<String> allKeys = lsmTree.getKeysWithPrefix(prefix);
        List<String> result = new ArrayList<>();
        for (String key : allKeys) {
            if (key.startsWith(prefix)) {
                result.add(key.substring(prefix.length()));
            }
        }
        return result;
    }

    private boolean isValidCollectionName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (name.length() > Constants.COLLECTION_NAME_MAX_LENGTH) {
            return false;
        }
        return java.util.regex.Pattern.matches("^[a-zA-Z0-9_]+$", name);
    }
}