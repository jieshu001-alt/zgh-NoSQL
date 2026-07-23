package com.easydb.server.engine.lsm;

import com.easydb.common.constants.Constants;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * LSM-Tree - Log-Structured Merge Tree
 * 实现多层存储结构：MemTable -> Level 0 SSTable -> Level 1 SSTable -> ...
 */
public class LSMTree {

    private static final int MAX_LEVELS = 3;
    private static final int L0_MAX_FILES = 4;
    private static final int L1_MAX_FILES = 10;
    private static final int L2_MAX_FILES = 30;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ExecutorService flushExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService mergeExecutor = Executors.newSingleThreadExecutor();

    private volatile MemTable activeMemTable;
    private final Deque<MemTable> immutableMemTables = new ConcurrentLinkedDeque<>();

    // 各层级的 SSTable 列表
    private final List<List<SSTable>> levels = new ArrayList<>();
    
    // 文件索引计数器
    private long sstableIndex = 0;

    public LSMTree() {
        // 初始化各层级
        for (int i = 0; i < MAX_LEVELS; i++) {
            levels.add(new ArrayList<>());
        }
        
        // 创建初始 MemTable
        activeMemTable = new MemTable();
        
        // 加载已存在的 SSTable
        loadExistingSSTables();
        
        // 启动后台刷新线程
        startFlushDaemon();
        
        // 启动后台合并线程
        startMergeDaemon();
    }

    /**
     * 加载已存在的 SSTable 文件
     */
    private void loadExistingSSTables() {
        String sstableDir = Constants.DATA_DIR + "/sstables";
        
        for (int level = 0; level < MAX_LEVELS; level++) {
            File levelDir = new File(sstableDir + "/level-" + level);
            if (!levelDir.exists()) {
                continue;
            }
            
            File[] sstFiles = levelDir.listFiles((dir, name) -> name.endsWith(".sst"));
            if (sstFiles == null || sstFiles.length == 0) {
                continue;
            }
            
            for (File sstFile : sstFiles) {
                String name = sstFile.getName();
                try {
                    long fileIndex = Long.parseLong(name.substring(4, name.length() - 4)); // "sst-X.sst"
                    SSTable sstable = new SSTable(level, fileIndex);
                    levels.get(level).add(sstable);
                    
                    // 更新最大文件索引
                    if (fileIndex >= sstableIndex) {
                        sstableIndex = fileIndex + 1;
                    }
                } catch (NumberFormatException e) {
                    System.err.println("[LSMTree] Invalid SSTable file name: " + name);
                }
            }
            
            // 按文件索引排序
            levels.get(level).sort(Comparator.comparingLong(SSTable::getFileIndex));
        }
        
        System.out.println("[LSMTree] Loaded SSTables: L0=" + levels.get(0).size() 
            + ", L1=" + levels.get(1).size() + ", L2=" + levels.get(2).size());
    }

    /**
     * 添加键值对
     */
    public void put(String key, String value) {
        lock.readLock().lock();
        try {
            activeMemTable.put(key, value);
            
            // 检查是否需要刷新
            if (activeMemTable.isFull()) {
                flushMemTable();
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取值
     */
    public String get(String key) {
        lock.readLock().lock();
        try {
            // 先查活跃 MemTable
            String value = activeMemTable.get(key);
            if (value != null) {
                return value;
            }
            
            // 再查不可变 MemTable
            for (MemTable memTable : immutableMemTables) {
                value = memTable.get(key);
                if (value != null) {
                    return value;
                }
            }
            
            // 最后查各层 SSTable（从高层到低层）
            for (int level = 0; level < MAX_LEVELS; level++) {
                for (SSTable sstable : levels.get(level)) {
                    value = sstable.get(key);
                    if (value != null) {
                        return value;
                    }
                }
            }
            
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 删除键（使用 null 作为墓碑标记）
     */
    public void delete(String key) {
        put(key, null);
    }

    /**
     * 检查键是否存在
     */
    public boolean contains(String key) {
        return get(key) != null;
    }

    /**
     * 获取所有键
     */
    public List<String> getKeys() {
        lock.readLock().lock();
        try {
            Set<String> keys = new TreeSet<>();
            
            // 收集活跃 MemTable 的键
            keys.addAll(activeMemTable.getKeys());
            
            // 收集不可变 MemTable 的键
            for (MemTable memTable : immutableMemTables) {
                keys.addAll(memTable.getKeys());
            }
            
            // 收集各层 SSTable 的键
            for (int level = 0; level < MAX_LEVELS; level++) {
                for (SSTable sstable : levels.get(level)) {
                    keys.addAll(sstable.getAllKeys());
                }
            }
            
            return new ArrayList<>(keys);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取指定前缀的键
     */
    public List<String> getKeysWithPrefix(String prefix) {
        lock.readLock().lock();
        try {
            Set<String> keys = new TreeSet<>();
            
            // 收集活跃 MemTable 的键
            for (String key : activeMemTable.getKeys()) {
                if (key.startsWith(prefix)) {
                    keys.add(key);
                }
            }
            
            // 收集不可变 MemTable 的键
            for (MemTable memTable : immutableMemTables) {
                for (String key : memTable.getKeys()) {
                    if (key.startsWith(prefix)) {
                        keys.add(key);
                    }
                }
            }
            
            // 收集各层 SSTable 的键
            for (int level = 0; level < MAX_LEVELS; level++) {
                for (SSTable sstable : levels.get(level)) {
                    for (String key : sstable.getAllKeys()) {
                        if (key.startsWith(prefix)) {
                            keys.add(key);
                        }
                    }
                }
            }
            
            return new ArrayList<>(keys);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 刷新 MemTable 到 SSTable
     */
    private void flushMemTable() {
        lock.writeLock().lock();
        try {
            // 将活跃 MemTable 转为不可变
            MemTable oldMemTable = activeMemTable;
            oldMemTable.makeImmutable();
            immutableMemTables.add(oldMemTable);
            
            // 创建新的活跃 MemTable
            activeMemTable = new MemTable();
            
            // 异步刷盘
            flushExecutor.submit(() -> {
                try {
                    SSTable sstable = new SSTable(0, sstableIndex++);
                    sstable.writeFromMemTable(oldMemTable);
                    
                    lock.writeLock().lock();
                    try {
                        levels.get(0).add(sstable);
                        immutableMemTables.remove(oldMemTable);
                        
                        // 检查是否需要合并
                        checkAndMerge();
                    } finally {
                        lock.writeLock().unlock();
                    }
                } catch (Exception e) {
                    System.err.println("[LSMTree] Failed to flush MemTable: " + e.getMessage());
                }
            });
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 启动后台刷新守护线程
     */
    private void startFlushDaemon() {
        // 已在 put 操作中触发刷新，这里可以添加定期检查
    }

    /**
     * 启动后台合并守护线程
     */
    private void startMergeDaemon() {
        mergeExecutor.submit(() -> {
            while (true) {
                try {
                    Thread.sleep(30000); // 每30秒检查一次
                    checkAndMerge();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    /**
     * 检查并执行合并
     */
    private void checkAndMerge() {
        // 检查 Level 0
        if (levels.get(0).size() >= L0_MAX_FILES) {
            mergeLevel(0);
        }
        
        // 检查 Level 1
        if (levels.get(1).size() >= L1_MAX_FILES) {
            mergeLevel(1);
        }
        
        // 检查 Level 2
        if (levels.get(2).size() >= L2_MAX_FILES) {
            mergeLevel(2);
        }
    }

    /**
     * 合并指定层级的 SSTable
     */
    private void mergeLevel(int level) {
        if (level >= MAX_LEVELS - 1) {
            return; // 最高层不合并
        }
        
        lock.writeLock().lock();
        try {
            List<SSTable> currentLevel = levels.get(level);
            if (currentLevel.isEmpty()) {
                return;
            }
            
            // 获取下一层级的 SSTable
            List<SSTable> nextLevel = levels.get(level + 1);
            
            // 收集所有待合并的条目
            Map<String, String> mergedEntries = new TreeMap<>();
            
            // 添加当前层的所有条目
            for (SSTable sstable : currentLevel) {
                for (Map.Entry<String, String> entry : sstable.getAllEntries()) {
                    mergedEntries.put(entry.getKey(), entry.getValue());
                }
            }
            
            // 添加下一层的所有条目（如果需要）
            if (level == 0) {
                // Level 0 需要与 Level 1 合并
                for (SSTable sstable : nextLevel) {
                    for (Map.Entry<String, String> entry : sstable.getAllEntries()) {
                        mergedEntries.put(entry.getKey(), entry.getValue());
                    }
                }
                nextLevel.clear();
            }
            
            // 删除已删除的条目（值为 null，即墓碑标记）
            mergedEntries.entrySet().removeIf(e -> e.getValue() == null);
            
            // 创建新的 MemTable 用于写入
            MemTable tempMemTable = new MemTable(Long.MAX_VALUE);
            for (Map.Entry<String, String> entry : mergedEntries.entrySet()) {
                tempMemTable.put(entry.getKey(), entry.getValue());
            }
            
            // 写入新的 SSTable
            SSTable newSSTable = new SSTable(level + 1, sstableIndex++);
            newSSTable.writeFromMemTable(tempMemTable);
            nextLevel.add(newSSTable);
            
            // 删除旧的 SSTable
            for (SSTable sstable : currentLevel) {
                sstable.delete();
            }
            currentLevel.clear();
            
            System.out.println("[LSMTree] Merged level " + level + " to level " + (level + 1) 
                + ", entries: " + mergedEntries.size());
            
        } catch (Exception e) {
            System.err.println("[LSMTree] Failed to merge level " + level + ": " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 关闭 LSM-Tree
     */
    public void close() {
        // 刷新所有 MemTable
        flushMemTable();
        
        // 等待刷新完成
        flushExecutor.shutdown();
        mergeExecutor.shutdown();
        
        // 等待不可变 MemTable 刷盘完成
        while (!immutableMemTables.isEmpty()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
        }
        
        System.out.println("[LSMTree] Closed");
    }

    /**
     * 获取当前 MemTable 大小
     */
    public long getMemTableSize() {
        return activeMemTable.getSize();
    }

    /**
     * 获取层级信息
     */
    public String getLevelInfo() {
        StringBuilder sb = new StringBuilder();
        for (int level = 0; level < MAX_LEVELS; level++) {
            sb.append("Level ").append(level).append(": ").append(levels.get(level).size()).append(" files, ");
        }
        sb.append("MemTable: ").append(activeMemTable.getCount()).append(" entries");
        return sb.toString();
    }
}