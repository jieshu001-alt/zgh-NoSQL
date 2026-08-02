package com.easydb.server.engine.disk;

import com.easydb.common.constants.Constants;
import com.easydb.server.engine.lsm.LSMTree;
import com.easydb.server.engine.mem.LruCache;

import java.util.*;

/**
 * 缓存治理器 - 仿 Redis 企业级缓存优化
 * 后台定时任务周期性对 LruCache 存量数据做治理：
 * 1. 清除重复数据（key不同但value相同）
 * 2. 淘汰冷数据（长期未访问、低访问频次）
 * 3. 清理脏缓存（业务已删除但缓存未同步）
 * 4. 清理废弃key（test_/tmp_/debug_ 前缀）
 */
public class CacheGovernor {

    private final LruCache cache;
    private final LSMTree lsmTree;
    private Thread governorThread;
    private volatile boolean running = true;

    public CacheGovernor(LruCache cache, LSMTree lsmTree) {
        this.cache = cache;
        this.lsmTree = lsmTree;
    }

    /**
     * 启动后台治理线程
     */
    public void start() {
        governorThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(Constants.CACHE_GOVERNOR_INTERVAL_SECONDS * 1000L);
                    if (!running) break;
                    executeGovernance();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "CacheGovernor-Daemon");
        governorThread.setDaemon(true);
        governorThread.start();
        System.out.println("[CacheGovernor] Started, interval=" + Constants.CACHE_GOVERNOR_INTERVAL_SECONDS + "s");
    }

    /**
     * 执行一轮治理
     */
    private void executeGovernance() {
        long startTime = System.currentTimeMillis();
        int removedDup = 0, removedCold = 0, removedDirty = 0, removedAbandoned = 0;
        int initialSize = cache.size();

        if (initialSize == 0) return;

        // 1. 清除重复数据
        removedDup = removeDuplicateEntries();

        // 2. 淘汰冷数据
        removedCold = evictColdData();

        // 3. 清理脏缓存
        removedDirty = cleanDirtyCache();

        // 4. 清理废弃 key
        removedAbandoned = cleanAbandonedKeys();

        long duration = System.currentTimeMillis() - startTime;
        int totalRemoved = removedDup + removedCold + removedDirty + removedAbandoned;
        
        if (totalRemoved > 0) {
            System.out.println("[CacheGovernor] Round done in " + duration + "ms: "
                + "initial=" + initialSize + " → final=" + cache.size()
                + " | dup=" + removedDup + " cold=" + removedCold
                + " dirty=" + removedDirty + " abandoned=" + removedAbandoned);
        }
    }

    /**
     * ① 清除重复数据：key不同但value完全相同的条目，保留访问频次最高的
     */
    private int removeDuplicateEntries() {
        int removed = 0;
        Set<Map.Entry<String, String>> entries = cache.getAllEntries();
        if (entries.isEmpty()) return 0;

        // 按 value 分组
        Map<String, List<String>> valueToKeys = new HashMap<>();
        for (Map.Entry<String, String> entry : entries) {
            String value = entry.getValue();
            valueToKeys.computeIfAbsent(value, k -> new ArrayList<>()).add(entry.getKey());
        }

        // value 相同的多个 key → 只保留访问频次最高的
        for (Map.Entry<String, List<String>> vtk : valueToKeys.entrySet()) {
            List<String> keys = vtk.getValue();
            if (keys.size() <= 1) continue;

            // 按访问频次降序排序，保留第一个
            keys.sort((a, b) -> Integer.compare(
                cache.getAccessCount(b), cache.getAccessCount(a)));

            for (int i = 1; i < keys.size(); i++) {
                cache.removeWithoutStats(keys.get(i));
                removed++;
            }
        }

        return removed;
    }

    /**
     * ② 冷数据淘汰：长期未访问 + 低访问频次
     */
    private int evictColdData() {
        int removed = 0;
        long now = System.currentTimeMillis();
        long idleThresholdMs = Constants.COLD_DATA_IDLE_SECONDS * 1000L;

        Set<Map.Entry<String, String>> entries = cache.getAllEntries();
        for (Map.Entry<String, String> entry : entries) {
            String key = entry.getKey();
            long lastAccess = cache.getLastAccessTime(key);
            int count = cache.getAccessCount(key);

            boolean isCold = (now - lastAccess > idleThresholdMs)
                          && (count < Constants.COLD_DATA_ACCESS_THRESHOLD);

            if (isCold) {
                cache.removeWithoutStats(key);
                removed++;
            }
        }

        return removed;
    }

    /**
     * ③ 脏缓存清理：缓存中有但 LSM-Tree 中没有（业务已删除，缓存未同步）
     */
    private int cleanDirtyCache() {
        int removed = 0;
        Set<Map.Entry<String, String>> entries = cache.getAllEntries();
        
        for (Map.Entry<String, String> entry : entries) {
            String key = entry.getKey();
            if (!lsmTree.contains(key)) {
                cache.removeWithoutStats(key);
                removed++;
            }
        }

        return removed;
    }

    /**
     * ④ 废弃 key 清理：匹配 test_/tmp_/debug_ 等模式的 key，且访问频次为 0
     */
    private int cleanAbandonedKeys() {
        int removed = 0;
        String[] patterns = Constants.ABANDONED_KEY_PATTERNS;

        Set<Map.Entry<String, String>> entries = cache.getAllEntries();
        for (Map.Entry<String, String> entry : entries) {
            String key = entry.getKey();
            boolean matchesPattern = false;
            for (String pattern : patterns) {
                if (key.startsWith(pattern)) {
                    matchesPattern = true;
                    break;
                }
            }
            if (matchesPattern && cache.getAccessCount(key) == 0) {
                cache.removeWithoutStats(key);
                removed++;
            }
        }

        return removed;
    }

    /**
     * 获取当前缓存统计信息
     */
    public String getStats() {
        return "CacheGovernor[ cacheSize=" + cache.size() + " ]";
    }

    public void shutdown() {
        running = false;
        if (governorThread != null) {
            governorThread.interrupt();
        }
        System.out.println("[CacheGovernor] Shutdown");
    }
}
