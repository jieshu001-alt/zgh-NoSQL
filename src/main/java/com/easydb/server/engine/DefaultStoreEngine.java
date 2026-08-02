package com.easydb.server.engine;

import com.easydb.common.constants.Constants;
import com.easydb.server.engine.disk.CacheGovernor;
import com.easydb.server.engine.disk.WalManager;
import com.easydb.server.engine.lsm.LSMTree;
import com.easydb.server.engine.mem.LruCache;

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
    private final LruCache readCache = new LruCache(10000); // 最多缓存10000条
    private final CacheGovernor cacheGovernor;                // 缓存后台治理
    
    // 复制回调接口
    public interface ReplicationCallback {
        void onWrite(String command);
    }
    
    private volatile ReplicationCallback replicationCallback;

    private DefaultStoreEngine() {
        this.walManager = new WalManager();
        replayWal();
        this.cacheGovernor = new CacheGovernor(readCache, lsmTree);
        this.cacheGovernor.start();
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
        
        // 更新缓存
        readCache.put(key, value);
        
        // 触发实时复制
        replicate(Constants.COMMAND_SET + " " + key + " " + value);
        
        return Constants.OK_RESPONSE;
    }

    @Override
    public String get(String key) {
        // 先查 LRU 缓存
        String cached = readCache.get(key);
        if (cached != null) {
            return cached;
        }
        
        // 缓存未命中，查 LSM-Tree
        String value = lsmTree.get(key);
        if (value != null) {
            readCache.put(key, value);
        }
        return value;
    }

    @Override
    public String del(String key) {
        walManager.write(Constants.COMMAND_DEL, key, null);
        boolean existed = lsmTree.contains(key);
        lsmTree.delete(key);
        
        // 从缓存中移除
        readCache.remove(key);
        
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
        cacheGovernor.shutdown();
        lsmTree.close();
        walManager.close();
    }

    @Override
    public void batchSet(Map<String, String> entries) {
        walManager.batchWrite(Constants.COMMAND_MSET, entries);
        
        StringBuilder cmdBuilder = new StringBuilder(Constants.COMMAND_MSET);
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            lsmTree.put(entry.getKey(), entry.getValue());
            readCache.put(entry.getKey(), entry.getValue());
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
            readCache.remove(key);
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

    // ================ List operations ================
    
    @Override
    public String lpush(String key, String value) {
        String existing = get(key);
        List<String> list;
        if (existing == null || existing.isEmpty()) {
            list = new ArrayList<>();
        } else {
            list = com.alibaba.fastjson.JSON.parseObject(existing, List.class);
            if (list == null) {
                list = new ArrayList<>();
            }
        }
        list.add(0, value);
        String result = com.alibaba.fastjson.JSON.toJSONString(list);
        set(key, result);
        return Constants.OK_RESPONSE;
    }

    @Override
    public String rpush(String key, String value) {
        String existing = get(key);
        List<String> list;
        if (existing == null || existing.isEmpty()) {
            list = new ArrayList<>();
        } else {
            list = com.alibaba.fastjson.JSON.parseObject(existing, List.class);
            if (list == null) {
                list = new ArrayList<>();
            }
        }
        list.add(value);
        String result = com.alibaba.fastjson.JSON.toJSONString(list);
        set(key, result);
        return Constants.OK_RESPONSE;
    }

    @Override
    public String lpop(String key) {
        String existing = get(key);
        if (existing == null || existing.isEmpty()) {
            return Constants.NULL_VALUE;
        }
        List<String> list = com.alibaba.fastjson.JSON.parseObject(existing, List.class);
        if (list == null || list.isEmpty()) {
            return Constants.NULL_VALUE;
        }
        String value = list.remove(0);
        String result = list.isEmpty() ? "" : com.alibaba.fastjson.JSON.toJSONString(list);
        set(key, result);
        return value;
    }

    @Override
    public String rpop(String key) {
        String existing = get(key);
        if (existing == null || existing.isEmpty()) {
            return Constants.NULL_VALUE;
        }
        List<String> list = com.alibaba.fastjson.JSON.parseObject(existing, List.class);
        if (list == null || list.isEmpty()) {
            return Constants.NULL_VALUE;
        }
        String value = list.remove(list.size() - 1);
        String result = list.isEmpty() ? "" : com.alibaba.fastjson.JSON.toJSONString(list);
        set(key, result);
        return value;
    }

    @Override
    public String llen(String key) {
        String existing = get(key);
        if (existing == null || existing.isEmpty()) {
            return "0";
        }
        List<String> list = com.alibaba.fastjson.JSON.parseObject(existing, List.class);
        return list == null ? "0" : String.valueOf(list.size());
    }

    // ================ Set operations ================
    
    @Override
    public String sadd(String key, String value) {
        String existing = get(key);
        Set<String> set;
        if (existing == null || existing.isEmpty()) {
            set = new java.util.HashSet<>();
        } else {
            set = com.alibaba.fastjson.JSON.parseObject(existing, Set.class);
            if (set == null) {
                set = new java.util.HashSet<>();
            }
        }
        boolean added = set.add(value);
        String result = com.alibaba.fastjson.JSON.toJSONString(set);
        set(key, result);
        return added ? "1" : "0";
    }

    @Override
    public String smembers(String key) {
        String existing = get(key);
        if (existing == null || existing.isEmpty()) {
            return "[]";
        }
        Set<String> set = com.alibaba.fastjson.JSON.parseObject(existing, Set.class);
        return set == null ? "[]" : com.alibaba.fastjson.JSON.toJSONString(set);
    }

    @Override
    public String srem(String key, String value) {
        String existing = get(key);
        if (existing == null || existing.isEmpty()) {
            return "0";
        }
        Set<String> set = com.alibaba.fastjson.JSON.parseObject(existing, Set.class);
        if (set == null) {
            return "0";
        }
        boolean removed = set.remove(value);
        String result = set.isEmpty() ? "" : com.alibaba.fastjson.JSON.toJSONString(set);
        set(key, result);
        return removed ? "1" : "0";
    }

    // ================ Hash operations ================
    
    @Override
    public String hset(String key, String field, String value) {
        String existing = get(key);
        Map<String, String> map;
        if (existing == null || existing.isEmpty()) {
            map = new java.util.HashMap<>();
        } else {
            map = com.alibaba.fastjson.JSON.parseObject(existing, Map.class);
            if (map == null) {
                map = new java.util.HashMap<>();
            }
        }
        String oldValue = map.put(field, value);
        String result = com.alibaba.fastjson.JSON.toJSONString(map);
        set(key, result);
        return oldValue == null ? "1" : "0";
    }

    @Override
    public String hget(String key, String field) {
        String existing = get(key);
        if (existing == null || existing.isEmpty()) {
            return Constants.NULL_VALUE;
        }
        Map<String, String> map = com.alibaba.fastjson.JSON.parseObject(existing, Map.class);
        return map == null ? Constants.NULL_VALUE : (map.get(field) != null ? map.get(field) : Constants.NULL_VALUE);
    }

    @Override
    public String hdel(String key, String field) {
        String existing = get(key);
        if (existing == null || existing.isEmpty()) {
            return "0";
        }
        Map<String, String> map = com.alibaba.fastjson.JSON.parseObject(existing, Map.class);
        if (map == null) {
            return "0";
        }
        String removed = map.remove(field);
        String result = map.isEmpty() ? "" : com.alibaba.fastjson.JSON.toJSONString(map);
        set(key, result);
        return removed != null ? "1" : "0";
    }

    @Override
    public String hgetall(String key) {
        String existing = get(key);
        if (existing == null || existing.isEmpty()) {
            return "{}";
        }
        Map<String, String> map = com.alibaba.fastjson.JSON.parseObject(existing, Map.class);
        return map == null ? "{}" : com.alibaba.fastjson.JSON.toJSONString(map);
    }

    // ================ Collection operations ================
    
    @Override
    public String collectionSet(String collection, String key, String value) {
        if (!collections.contains(collection)) {
            return Constants.ERROR_PREFIX + "Collection not found: " + collection;
        }
        String fullKey = collection + Constants.COLLECTION_SEPARATOR + key;
        return set(fullKey, value);
    }

    @Override
    public String collectionGet(String collection, String key) {
        if (!collections.contains(collection)) {
            return null;
        }
        String fullKey = collection + Constants.COLLECTION_SEPARATOR + key;
        return get(fullKey);
    }

    @Override
    public String collectionDel(String collection, String key) {
        if (!collections.contains(collection)) {
            return Constants.NULL_VALUE;
        }
        String fullKey = collection + Constants.COLLECTION_SEPARATOR + key;
        return del(fullKey);
    }

    @Override
    public List<String> collectionKeys(String collection) {
        return keysInCollection(collection);
    }
}