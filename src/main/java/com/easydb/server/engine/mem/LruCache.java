package com.easydb.server.engine.mem;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LRU (Least Recently Used) 读缓存，基于LinkedHashMap access-order淘汰。
 * 扩展：accessCount（访问频次）和 lastAccessTime（最后访问时间）用于缓存治理。
 */
public class LruCache {

    private final LinkedHashMap<String, String> cache;
    private final ConcurrentHashMap<String, Integer> accessCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastAccessTime = new ConcurrentHashMap<>();

    public LruCache(int maxSize) {
        this.cache = new LinkedHashMap<String, String>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                if (size() > maxSize) {
                    // 清理被淘汰 key 的访问统计
                    accessCount.remove(eldest.getKey());
                    lastAccessTime.remove(eldest.getKey());
                    return true;
                }
                return false;
            }
        };
    }

    public synchronized String get(String key) {
        String value = cache.get(key);
        if (value != null) {
            accessCount.merge(key, 1, Integer::sum);
            lastAccessTime.put(key, System.currentTimeMillis());
        }
        return value;
    }

    public synchronized void put(String key, String value) {
        if (value != null) {
            cache.put(key, value);
            accessCount.putIfAbsent(key, 0);
            lastAccessTime.putIfAbsent(key, System.currentTimeMillis());
        }
    }

    public synchronized void remove(String key) {
        cache.remove(key);
        accessCount.remove(key);
        lastAccessTime.remove(key);
    }

    public void clear() {
        cache.clear();
        accessCount.clear();
        lastAccessTime.clear();
    }

    public int size() {
        return cache.size();
    }

    public boolean contains(String key) {
        return cache.containsKey(key);
    }

    // ========== 缓存治理相关方法 ==========

    public synchronized Set<Map.Entry<String, String>> getAllEntries() {
        return Set.copyOf(cache.entrySet());
    }

    public int getAccessCount(String key) {
        return accessCount.getOrDefault(key, 0);
    }

    public long getLastAccessTime(String key) {
        return lastAccessTime.getOrDefault(key, 0L);
    }

    public void removeWithoutStats(String key) {
        cache.remove(key);
        accessCount.remove(key);
        lastAccessTime.remove(key);
    }
}
