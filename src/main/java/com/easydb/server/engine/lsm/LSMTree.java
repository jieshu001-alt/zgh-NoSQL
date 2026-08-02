package com.easydb.server.engine.lsm;

import com.easydb.common.constants.Constants;
import com.easydb.server.engine.index.SparseIndex;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
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
    private final ExecutorService cleanupExecutor = Executors.newFixedThreadPool(2); // Rotate后文件清洗线程池

    private volatile MemTable activeMemTable;
    private final Deque<MemTable> immutableMemTables = new ConcurrentLinkedDeque<>();

    // 各层级的 SSTable 列表
    private final List<List<SSTable>> levels = new ArrayList<>();
    
    // 文件索引计数器
    private long sstableIndex = 0;
    
    // 全局稀疏索引 - 加速键定位
    private final SparseIndex globalIndex = new SparseIndex();
    
    // 后台 Rotate 检查线程
    private Thread rotateChecker;
    private volatile boolean running = true;

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
        
        // 启动后台 Rotate 检查线程
        startRotateChecker();
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
     * 获取值（优先使用全局索引加速定位）
     * 注意：MemTable 中的 TOMBSTONE 表示已删除，应直接返回 null
     */
    public String get(String key) {
        lock.readLock().lock();
        try {
            // 先查活跃 MemTable
            String value = activeMemTable.get(key);
            if (value != null) {
                return value;
            }
            // 如果 MemTable 中有 TOMBSTONE（get返回null但key存在），说明已删除
            if (activeMemTable.contains(key)) {
                return null; // 已被删除
            }
            
            // 再查不可变 MemTable
            for (MemTable memTable : immutableMemTables) {
                value = memTable.get(key);
                if (value != null) {
                    return value;
                }
                if (memTable.contains(key)) {
                    return null; // 已被删除
                }
            }
            
            // 检查全局索引，直接定位 SSTable
            SparseIndex.IndexEntry idxEntry = globalIndex.get(key);
            if (idxEntry != null) {
                int targetLevel = (int) idxEntry.getFileIndex();
                if (targetLevel >= 0 && targetLevel < MAX_LEVELS) {
                    boolean sstableFound = false;
                    for (SSTable sstable : levels.get(targetLevel)) {
                        if (sstable.getFileIndex() == idxEntry.getOffset()) {
                            sstableFound = true;
                            value = sstable.get(key);
                            if (value != null) return value;
                            break; // 索引定位到了这个 SSTable，如果没找到就是真没有
                        }
                    }
                    // 索引指向的 SSTable 已被合并删除，清理失效条目
                    if (!sstableFound) {
                        globalIndex.remove(key);
                    }
                }
            }
            
            // 全层扫描（索引未命中或已失效时回退）
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
     * 删除键（使用 TOMBSTONE 作为墓碑标记）
     */
    public void delete(String key) {
        lock.readLock().lock();
        try {
            activeMemTable.delete(key);
        } finally {
            lock.readLock().unlock();
        }
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
                        
                        // 更新全局索引
                        updateGlobalIndex(sstable, 0);
                        
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
     * 启动后台 Rotate 检查线程
     * 定期检查 SSTable 文件大小，超过限制则触发 Rotate 并压缩
     */
    private void startRotateChecker() {
        rotateChecker = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(10000); // 每10秒检查一次
                    checkRotateAndCompress();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "LSMTree-RotateChecker");
        rotateChecker.setDaemon(true);
        rotateChecker.start();
    }

    /**
     * 检查 SSTable 文件大小，触发 Rotate
     * 文件达到 64MB 后：关闭当前文件 -> 创建新文件继续写入 -> 异步清洗旧文件
     */
    private void checkRotateAndCompress() {
        lock.writeLock().lock();
        try {
            for (int level = 0; level < MAX_LEVELS; level++) {
                List<SSTable> sstables = levels.get(level);
                if (sstables.isEmpty()) continue;
                
                SSTable lastSSTable = sstables.get(sstables.size() - 1);
                
                // 检查文件大小是否超过限制
                if (!lastSSTable.isClosed() && lastSSTable.exceedsSizeLimit()) {
                    
                    // 关闭当前文件（Rotate）
                    lastSSTable.closeFile();
                    System.out.println("[LSMTree] Rotate triggered for SSTable " + 
                        lastSSTable.getFileIndex() + " (level " + level + "), size: " + 
                        lastSSTable.getSize());
                    
                    // 创建新文件继续写入
                    SSTable newSSTable = new SSTable(level, sstableIndex++);
                    sstables.add(newSSTable);
                    System.out.println("[LSMTree] Created new SSTable " + newSSTable.getFileIndex() + 
                        " (level " + level + ")");
                    
                    // 异步清洗旧文件：去重 + 去墓碑 + 生成精简SSTable
                    final SSTable oldSSTable = lastSSTable;
                    final int oldLevel = level;
                    cleanupExecutor.submit(() -> cleanupRotatedFile(oldSSTable, oldLevel));
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
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
            
            // 删除已删除的条目（值为 TOMBSTONE 即墓碑标记）
            mergedEntries.entrySet().removeIf(e -> e.getValue() == null || e.getValue().equals(MemTable.TOMBSTONE));
            
            // 创建新的 MemTable 用于写入
            MemTable tempMemTable = new MemTable(Long.MAX_VALUE);
            for (Map.Entry<String, String> entry : mergedEntries.entrySet()) {
                tempMemTable.put(entry.getKey(), entry.getValue());
            }
            
            // 写入新的 SSTable
            SSTable newSSTable = new SSTable(level + 1, sstableIndex++);
            newSSTable.writeFromMemTable(tempMemTable);
            nextLevel.add(newSSTable);
            
            // 更新全局索引
            updateGlobalIndex(newSSTable, level + 1);
            
            // 清理旧 SSTable 对应的索引条目
            for (SSTable sstable : currentLevel) {
                for (String key : sstable.getAllKeys()) {
                    SparseIndex.IndexEntry entry = globalIndex.get(key);
                    if (entry != null && entry.getOffset() == sstable.getFileIndex()) {
                        globalIndex.remove(key);
                    }
                }
            }
            
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
     * 清洗 Rotate 后的旧 SSTable 文件（异步执行）
     * 流程：读取全量数据 → TreeMap去重排序 → 过滤墓碑 → 生成精简SSTable → 替换旧文件 → 更新索引
     */
    private void cleanupRotatedFile(SSTable oldSSTable, int level) {
        try {
            System.out.println("[LSMTree] Cleaning up rotated SSTable " + oldSSTable.getFileIndex() + " (level " + level + ")");
            
            // 读取旧文件全部数据
            List<Map.Entry<String, String>> entries = oldSSTable.getAllEntries();
            if (entries.isEmpty()) {
                oldSSTable.delete();
                return;
            }
            
            // TreeMap 按 key 字典序排序并去重（同 key 保留后写入的版本）
            Map<String, String> deduped = new TreeMap<>();
            for (Map.Entry<String, String> entry : entries) {
                deduped.put(entry.getKey(), entry.getValue());
            }
            
            // 过滤墓碑标记
            deduped.entrySet().removeIf(e -> e.getValue() == null || e.getValue().equals(MemTable.TOMBSTONE));
            
            if (deduped.isEmpty()) {
                // 全是墓碑，直接删除旧文件
                lock.writeLock().lock();
                try {
                    // 清理旧索引
                    for (String key : oldSSTable.getAllKeys()) {
                        SparseIndex.IndexEntry idx = globalIndex.get(key);
                        if (idx != null && idx.getOffset() == oldSSTable.getFileIndex()) {
                            globalIndex.remove(key);
                        }
                    }
                    levels.get(level).remove(oldSSTable);
                } finally {
                    lock.writeLock().unlock();
                }
                oldSSTable.delete();
                System.out.println("[LSMTree] Cleanup: all entries were tombstones, deleted SSTable " + oldSSTable.getFileIndex());
                return;
            }
            
            // 写入精简后的新 SSTable
            MemTable temp = new MemTable(Long.MAX_VALUE);
            for (Map.Entry<String, String> entry : deduped.entrySet()) {
                temp.put(entry.getKey(), entry.getValue());
            }
            SSTable newSSTable = new SSTable(level, sstableIndex++);
            newSSTable.writeFromMemTable(temp);
            
            // 替换 levels 列表中的旧 SSTable 为新文件
            lock.writeLock().lock();
            try {
                List<SSTable> levelList = levels.get(level);
                int oldIdx = levelList.indexOf(oldSSTable);
                if (oldIdx >= 0) {
                    levelList.set(oldIdx, newSSTable);
                } else {
                    // 旧 SSTable 已被 mergeLevel 移除，清理新文件
                    newSSTable.delete();
                    oldSSTable.delete();
                    return;
                }
                
                // 清理旧索引条目
                for (String key : oldSSTable.getAllKeys()) {
                    SparseIndex.IndexEntry idx = globalIndex.get(key);
                    if (idx != null && idx.getOffset() == oldSSTable.getFileIndex()) {
                        globalIndex.remove(key);
                    }
                }
                // 更新新索引
                updateGlobalIndex(newSSTable, level);
            } finally {
                lock.writeLock().unlock();
            }
            
            // 删除旧文件
            oldSSTable.delete();
            
            System.out.println("[LSMTree] Cleanup complete: SSTable " + oldSSTable.getFileIndex() 
                + " → " + newSSTable.getFileIndex() + " (level " + level + "), "
                + "entries: " + deduped.size());
                
        } catch (Exception e) {
            System.err.println("[LSMTree] Failed to cleanup rotated SSTable: " + e.getMessage());
        }
    }

    /**
     * 关闭 LSM-Tree
     */
    public void close() {
        running = false;
        
        // 停止 Rotate 检查线程
        if (rotateChecker != null) {
            rotateChecker.interrupt();
        }
        
        // 刷新所有 MemTable
        flushMemTable();
        
        // 等待刷新完成
        flushExecutor.shutdown();
        mergeExecutor.shutdown();
        cleanupExecutor.shutdown();
        
        // 等待不可变 MemTable 刷盘完成
        while (!immutableMemTables.isEmpty()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
        }
        
        // 持久化全局索引
        globalIndex.persist();
        
        System.out.println("[LSMTree] Closed");
    }

    /**
     * 更新全局稀疏索引（将 SSTable 中的所有 key 映射到该 SSTable）
     */
    private void updateGlobalIndex(SSTable sstable, int level) {
        List<String> keys = sstable.getAllKeys();
        for (String key : keys) {
            // 用 fileIndex 存 level, offset 存 sstableIndex
            globalIndex.put(key, level, sstable.getFileIndex(), 0);
        }
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