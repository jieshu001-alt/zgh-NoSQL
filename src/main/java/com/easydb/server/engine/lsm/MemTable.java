package com.easydb.server.engine.lsm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * MemTable - LSM-Tree 的内存表
 * 使用 ConcurrentSkipListMap 实现有序存储，支持并发访问
 */
public class MemTable {

    private static final long DEFAULT_MEMTABLE_MAX_SIZE = 64 * 1024 * 1024; // 64MB

    /**
     * 墓碑标记常量 - ConcurrentSkipListMap 不允许 null 值，
     * 使用此特殊字符串作为删除标记
     */
    public static final String TOMBSTONE = "\u0001__TOMBSTONE__\u0001";

    private final ConcurrentSkipListMap<String, String> data;
    private final long maxSize;
    private volatile long size = 0;
    private volatile boolean immutable = false;

    public MemTable() {
        this(DEFAULT_MEMTABLE_MAX_SIZE);
    }

    public MemTable(long maxSize) {
        this.data = new ConcurrentSkipListMap<>();
        this.maxSize = maxSize;
    }

    /**
     * 添加键值对
     */
    public void put(String key, String value) {
        if (immutable) {
            throw new IllegalStateException("MemTable is immutable");
        }
        
        String oldValue = data.put(key, value);
        if (oldValue != null) {
            size -= oldValue.getBytes().length;
        }
        if (value != null) {
            size += key.getBytes().length + value.getBytes().length + 2; // +2 for separator and newline
        } else {
            // 墓碑标记，只计算key的大小
            size += key.getBytes().length + 2;
        }
    }

    /**
     * 获取值（返回null表示键不存在或已删除）
     */
    public String get(String key) {
        String value = data.get(key);
        if (value == null || value.equals(TOMBSTONE)) {
            return null;
        }
        return value;
    }

    /**
     * 删除键（插入墓碑标记）
     */
    public void delete(String key) {
        if (immutable) {
            throw new IllegalStateException("MemTable is immutable");
        }
        // 使用 TOMBSTONE 作为墓碑标记（ConcurrentSkipListMap 不允许 null 值）
        put(key, TOMBSTONE);
    }

    /**
     * 检查键是否存在（排除墓碑标记）
     */
    public boolean contains(String key) {
        String value = data.get(key);
        return value != null && !value.equals(TOMBSTONE);
    }

    /**
     * 获取所有键（按顺序，排除墓碑标记）
     */
    public List<String> getKeys() {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().equals(TOMBSTONE)) {
                keys.add(entry.getKey());
            }
        }
        return keys;
    }

    /**
     * 获取所有键值对（按顺序，包含墓碑标记用于合并）
     */
    public List<Map.Entry<String, String>> getEntries() {
        return new ArrayList<>(data.entrySet());
    }

    /**
     * 获取大小（字节）
     */
    public long getSize() {
        return size;
    }

    /**
     * 获取条目数量（包含墓碑）
     */
    public int getCount() {
        return data.size();
    }

    /**
     * 检查是否达到最大大小
     */
    public boolean isFull() {
        return size >= maxSize;
    }

    /**
     * 转为不可变状态
     */
    public void makeImmutable() {
        this.immutable = true;
    }

    /**
     * 检查是否不可变
     */
    public boolean isImmutable() {
        return immutable;
    }

    /**
     * 清空表
     */
    public void clear() {
        data.clear();
        size = 0;
        immutable = false;
    }

    /**
     * 获取内存占用（估算）
     */
    public long estimateMemoryUsage() {
        return size;
    }
}