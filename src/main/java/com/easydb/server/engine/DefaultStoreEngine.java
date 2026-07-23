package com.easydb.server.engine;

import com.easydb.common.constants.Constants;
import com.easydb.common.utils.Serializer;
import com.easydb.server.engine.disk.Compactor;
import com.easydb.server.engine.disk.DataFileManager;
import com.easydb.server.engine.disk.WalManager;
import com.easydb.server.engine.index.Trie;
import com.easydb.server.engine.mem.ConcurrentHashStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class DefaultStoreEngine implements StoreEngine {

    private static volatile DefaultStoreEngine instance;
    
    private final ConcurrentHashStore memoryStore = new ConcurrentHashStore();
    private final WalManager walManager;
    private final DataFileManager dataFileManager;
    private final Compactor compactor;
    private final ScheduledExecutorService flushExecutor;
    private final Set<String> collections = ConcurrentHashMap.newKeySet();
    private final Trie keyIndex = new Trie();

    private DefaultStoreEngine() {
        this.walManager = new WalManager();
        this.compactor = new Compactor();
        this.dataFileManager = new DataFileManager();
        this.dataFileManager.setCompactor(compactor);
        this.flushExecutor = Executors.newScheduledThreadPool(1);
        
        replayWal();
        startPeriodicFlush();
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

    private void replayWal() {
        List<String[]> records = walManager.replay();
        for (String[] record : records) {
            String command = record[0].toUpperCase();
            String key = record[1];
            String value = record[2];
            
            if (Constants.COMMAND_SET.equals(command)) {
                memoryStore.put(key, value);
                keyIndex.insert(key);
                try {
                    dataFileManager.write(key, value);
                } catch (IOException e) {
                    // ignore
                }
            } else if (Constants.COMMAND_DEL.equals(command)) {
                memoryStore.remove(key);
                keyIndex.delete(key);
            }
        }
        walManager.clear();
    }

    private void startPeriodicFlush() {
        flushExecutor.scheduleAtFixedRate(() -> {
            // Periodic flush is handled asynchronously
        }, 10, 10, TimeUnit.SECONDS);
    }

    @Override
    public String set(String key, String value) {
        walManager.write(Constants.COMMAND_SET, key, value);
        memoryStore.put(key, value);
        keyIndex.insert(key);
        
        flushToDataFile(key, value);
        
        return Constants.OK_RESPONSE;
    }

    private void flushToDataFile(String key, String value) {
        flushExecutor.execute(() -> {
            try {
                dataFileManager.write(key, value);
            } catch (IOException e) {
                // ignore
            }
        });
    }

    @Override
    public String get(String key) {
        Object value = memoryStore.get(key);
        if (value != null) {
            return Serializer.serialize(value);
        }
        
        String diskValue = dataFileManager.scanByKey(key);
        if (diskValue != null) {
            memoryStore.put(key, diskValue);
            return diskValue;
        }
        
        return null;
    }

    @Override
    public String del(String key) {
        walManager.write(Constants.COMMAND_DEL, key, null);
        Object value = memoryStore.remove(key);
        keyIndex.delete(key);
        return value != null ? Constants.OK_RESPONSE : Constants.NULL_VALUE;
    }

    @Override
    public List<String> keys(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            pattern = "*";
        }
        
        if (pattern.equals("*")) {
            return keyIndex.searchByPrefix("");
        }
        
        if (pattern.endsWith("*") && !pattern.substring(0, pattern.length() - 1).contains("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return keyIndex.searchByPrefix(prefix);
        }
        
        return memoryStore.keys(pattern);
    }

    @Override
    public boolean exists(String key) {
        return memoryStore.containsKey(key);
    }

    @Override
    public int size() {
        return memoryStore.size();
    }

    @Override
    public void shutdown() {
        flushExecutor.shutdown();
        compactor.shutdown();
        walManager.close();
        dataFileManager.close();
    }

    @Override
    public void batchSet(Map<String, String> entries) {
        walManager.batchWrite(Constants.COMMAND_SET, entries);
        
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            memoryStore.put(entry.getKey(), entry.getValue());
            keyIndex.insert(entry.getKey());
        }
        
        flushBatchToDataFile(entries);
    }

    @Override
    public List<String> batchGet(List<String> keys) {
        List<String> results = new ArrayList<>();
        for (String key : keys) {
            Object value = memoryStore.get(key);
            if (value != null) {
                results.add(Serializer.serialize(value));
            } else {
                String diskValue = dataFileManager.scanByKey(key);
                if (diskValue != null) {
                    memoryStore.put(key, diskValue);
                    results.add(diskValue);
                } else {
                    results.add(null);
                }
            }
        }
        return results;
    }

    @Override
    public void batchDel(List<String> keys) {
        walManager.batchWriteDel(Constants.COMMAND_DEL, keys);
        
        for (String key : keys) {
            memoryStore.remove(key);
            keyIndex.delete(key);
        }
    }

    private void flushBatchToDataFile(Map<String, String> entries) {
        flushExecutor.execute(() -> {
            try {
                for (Map.Entry<String, String> entry : entries.entrySet()) {
                    dataFileManager.write(entry.getKey(), entry.getValue());
                }
            } catch (IOException e) {
                // ignore
            }
        });
    }

    @Override
    public void createCollection(String name) {
        if (!isValidCollectionName(name)) {
            throw new IllegalArgumentException("Invalid collection name: " + name);
        }
        collections.add(name);
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
        
        collections.remove(name);
        return Constants.OK_RESPONSE;
    }

    @Override
    public List<String> listCollections() {
        return new ArrayList<>(collections);
    }

    @Override
    public List<String> keysInCollection(String collectionName) {
        String prefix = collectionName + Constants.COLLECTION_SEPARATOR;
        List<String> allKeys = keyIndex.searchByPrefix(prefix);
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
        return Pattern.matches("^[a-zA-Z0-9_]+$", name);
    }
}
