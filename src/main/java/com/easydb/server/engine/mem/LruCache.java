package com.easydb.server.engine.mem;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LRU (Least Recently Used) 读缓存
 * 使用 LinkedHashMap 的 access-order 模式实现最近最少使用淘汰策略
 */
public class LruCache {

    private final LinkedHashMap<String, String> cache;

    public LruCache(int maxSize) {
        this.cache = new LinkedHashMap<String, String>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > maxSize;
            }
        };
    }

    public String get(String key) {
        return cache.get(key);
    }

    public void put(String key, String value) {
        if (value != null) {
            cache.put(key, value);
        }
    }

    public void remove(String key) {
        cache.remove(key);
    }

    public void clear() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }

    public boolean contains(String key) {
        return cache.containsKey(key);
    }
}
