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
}
