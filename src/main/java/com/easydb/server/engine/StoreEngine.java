package com.easydb.server.engine;

import java.util.List;
import java.util.Map;

public interface StoreEngine {

    String set(String key, String value);
    
    String get(String key);
    
    String del(String key);
    
    List<String> keys(String pattern);
    
    boolean exists(String key);
    
    int size();
    
    void shutdown();
    
    void batchSet(Map<String, String> entries);
    
    List<String> batchGet(List<String> keys);
    
    void batchDel(List<String> keys);
    
    void createCollection(String name);
    
    String dropCollection(String name);
    
    List<String> listCollections();
    
    List<String> keysInCollection(String collectionName);
    
    // 集合级读写操作
    String collectionSet(String collection, String key, String value);
    
    String collectionGet(String collection, String key);
    
    String collectionDel(String collection, String key);
    
    List<String> collectionKeys(String collection);
    
    // List operations
    String lpush(String key, String value);
    
    String rpush(String key, String value);
    
    String lpop(String key);
    
    String rpop(String key);
    
    String llen(String key);
    
    // Set operations
    String sadd(String key, String value);
    
    String smembers(String key);
    
    String srem(String key, String value);
    
    // Hash operations
    String hset(String key, String field, String value);
    
    String hget(String key, String field);
    
    String hdel(String key, String field);
    
    String hgetall(String key);
}
