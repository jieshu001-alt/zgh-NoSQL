package com.easydb.server.engine.index;

import com.easydb.common.constants.Constants;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 稀疏索引 - 实现 key → (fileIndex, offset, length) 的映射
 * 支持持久化到索引文件，重启时加载
 */
public class SparseIndex {

    private static final String INDEX_FILE = Constants.DATA_DIR + "/index.dat";
    private static final int INDEX_VERSION = 1;

    /**
     * 索引条目 - 记录 key 在磁盘上的位置
     */
    public static class IndexEntry {
        private final long fileIndex;    // 数据文件索引
        private final long offset;       // 在文件中的偏移量
        private final int length;        // 值的长度

        public IndexEntry(long fileIndex, long offset, int length) {
            this.fileIndex = fileIndex;
            this.offset = offset;
            this.length = length;
        }

        public long getFileIndex() {
            return fileIndex;
        }

        public long getOffset() {
            return offset;
        }

        public int getLength() {
            return length;
        }
        
        /**
         * 获取值的长度（兼容 DataFileManager 的调用）
         */
        public int getValueLength() {
            return length;
        }
    }

    private final ConcurrentHashMap<String, IndexEntry> indexMap = new ConcurrentHashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile boolean loaded = false;

    public SparseIndex() {
        loadFromDisk();
    }

    /**
     * 添加索引条目
     */
    public void put(String key, long fileIndex, long offset, int length) {
        lock.writeLock().lock();
        try {
            indexMap.put(key, new IndexEntry(fileIndex, offset, length));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 获取索引条目
     */
    public IndexEntry get(String key) {
        lock.readLock().lock();
        try {
            return indexMap.get(key);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 删除索引条目
     */
    public void remove(String key) {
        lock.writeLock().lock();
        try {
            indexMap.remove(key);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 检查 key 是否存在
     */
    public boolean contains(String key) {
        lock.readLock().lock();
        try {
            return indexMap.containsKey(key);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取所有 key 的列表
     */
    public List<String> getAllKeys() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(indexMap.keySet());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取所有 key（带前缀过滤）
     */
    public List<String> getKeysWithPrefix(String prefix) {
        lock.readLock().lock();
        try {
            List<String> result = new ArrayList<>();
            for (String key : indexMap.keySet()) {
                if (key.startsWith(prefix)) {
                    result.add(key);
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取索引大小
     */
    public int size() {
        return indexMap.size();
    }

    /**
     * 将索引持久化到磁盘
     */
    public void persist() {
        lock.readLock().lock();
        try {
            Path dataPath = Paths.get(Constants.DATA_DIR);
            if (!Files.exists(dataPath)) {
                Files.createDirectories(dataPath);
            }

            try (DataOutputStream dos = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(INDEX_FILE)))) {
                
                // 写入版本号
                dos.writeInt(INDEX_VERSION);
                // 写入条目数量
                dos.writeInt(indexMap.size());
                
                // 写入每个条目
                for (Map.Entry<String, IndexEntry> entry : indexMap.entrySet()) {
                    String key = entry.getKey();
                    IndexEntry value = entry.getValue();
                    
                    // 写入 key 长度和 key
                    dos.writeInt(key.getBytes(Constants.ENCODING).length);
                    dos.write(key.getBytes(Constants.ENCODING));
                    
                    // 写入索引条目
                    dos.writeLong(value.getFileIndex());
                    dos.writeLong(value.getOffset());
                    dos.writeInt(value.getLength());
                }
            }
            
            System.out.println("[SparseIndex] Persisted " + indexMap.size() + " entries");
        } catch (IOException e) {
            System.err.println("[SparseIndex] Failed to persist index: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 从磁盘加载索引
     */
    private void loadFromDisk() {
        File indexFile = new File(INDEX_FILE);
        if (!indexFile.exists()) {
            loaded = true;
            return;
        }

        lock.writeLock().lock();
        try {
            try (DataInputStream dis = new DataInputStream(
                    new BufferedInputStream(new FileInputStream(indexFile)))) {
                
                // 读取版本号
                int version = dis.readInt();
                if (version != INDEX_VERSION) {
                    System.err.println("[SparseIndex] Unsupported index version: " + version);
                    loaded = true;
                    return;
                }
                
                // 读取条目数量
                int count = dis.readInt();
                
                // 读取每个条目
                for (int i = 0; i < count; i++) {
                    // 读取 key
                    int keyLength = dis.readInt();
                    byte[] keyBytes = new byte[keyLength];
                    dis.readFully(keyBytes);
                    String key = new String(keyBytes, Constants.ENCODING);
                    
                    // 读取索引条目
                    long fileIndex = dis.readLong();
                    long offset = dis.readLong();
                    int length = dis.readInt();
                    
                    indexMap.put(key, new IndexEntry(fileIndex, offset, length));
                }
            }
            
            System.out.println("[SparseIndex] Loaded " + indexMap.size() + " entries from disk");
        } catch (IOException e) {
            System.err.println("[SparseIndex] Failed to load index: " + e.getMessage());
            // 索引文件损坏，清空并重新构建
            indexMap.clear();
        } finally {
            lock.writeLock().unlock();
            loaded = true;
        }
    }

    /**
     * 清空索引
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            indexMap.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 检查索引是否已加载
     */
    public boolean isLoaded() {
        return loaded;
    }
}