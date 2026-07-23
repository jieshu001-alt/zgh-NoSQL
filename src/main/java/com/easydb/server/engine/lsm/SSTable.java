package com.easydb.server.engine.lsm;

import com.easydb.common.constants.Constants;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * SSTable - LSM-Tree 的有序字符串表
 * 存储有序的键值对，支持二分查找和随机访问
 */
public class SSTable {

    private static final String SSTABLE_DIR = Constants.DATA_DIR + "/sstables";
    private static final String SSTABLE_SUFFIX = ".sst";
    private static final String INDEX_SUFFIX = ".idx";

    private final int level;
    private final long fileIndex;
    private final File dataFile;
    private final File indexFile;
    private volatile long size = 0;

    public SSTable(int level, long fileIndex) {
        this.level = level;
        this.fileIndex = fileIndex;
        
        // 确保目录存在
        try {
            Files.createDirectories(Paths.get(SSTABLE_DIR + "/level-" + level));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create SSTable directory", e);
        }
        
        String basePath = SSTABLE_DIR + "/level-" + level + "/sst-" + fileIndex;
        this.dataFile = new File(basePath + SSTABLE_SUFFIX);
        this.indexFile = new File(basePath + INDEX_SUFFIX);
    }

    /**
     * 将 MemTable 刷盘为 SSTable（未压缩格式，支持随机访问）
     */
    public void writeFromMemTable(MemTable memTable) throws IOException {
        List<Map.Entry<String, String>> entries = memTable.getEntries();
        if (entries.isEmpty()) {
            return;
        }

        // 过滤掉墓碑标记（null值）
        List<Map.Entry<String, String>> validEntries = new ArrayList<>();
        for (Map.Entry<String, String> entry : entries) {
            if (entry.getValue() != null) {
                validEntries.add(entry);
            }
        }

        // 写入数据文件（未压缩格式）
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(dataFile), Constants.ENCODING))) {
            
            for (Map.Entry<String, String> entry : validEntries) {
                String line = entry.getKey() + " " + entry.getValue() + Constants.LINE_SEPARATOR;
                writer.write(line);
                size += line.getBytes(Constants.ENCODING).length;
            }
        }

        // 写入索引文件（稀疏索引）
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(indexFile)))) {
            
            int count = 0;
            long offset = 0;
            for (Map.Entry<String, String> entry : validEntries) {
                // 每隔一定数量的条目建立一个索引
                if (count % 100 == 0) {
                    String key = entry.getKey();
                    dos.writeInt(key.getBytes(Constants.ENCODING).length);
                    dos.write(key.getBytes(Constants.ENCODING));
                    dos.writeLong(offset);
                }
                
                String line = entry.getKey() + " " + entry.getValue() + Constants.LINE_SEPARATOR;
                offset += line.getBytes(Constants.ENCODING).length;
                count++;
            }
        }

        System.out.println("[SSTable] Written level-" + level + "/sst-" + fileIndex + ", entries: " + validEntries.size());
    }

    /**
     * 查找键值对
     */
    public String get(String key) {
        // 先检查索引文件，定位大致位置
        Long offset = findOffsetInIndex(key);
        if (offset == null) {
            return null;
        }

        // 在数据文件中查找
        return searchInDataFile(key, offset);
    }

    /**
     * 在索引文件中查找偏移量
     */
    private Long findOffsetInIndex(String key) {
        if (!indexFile.exists()) {
            return null;
        }

        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(indexFile)))) {
            
            String lastKey = null;
            long lastOffset = 0;

            while (dis.available() > 0) {
                int keyLength = dis.readInt();
                byte[] keyBytes = new byte[keyLength];
                dis.readFully(keyBytes);
                String indexKey = new String(keyBytes, Constants.ENCODING);
                long indexOffset = dis.readLong();

                if (key.compareTo(indexKey) < 0) {
                    return lastOffset;
                }

                lastKey = indexKey;
                lastOffset = indexOffset;
            }

            // key 比所有索引键都大，返回最后一个偏移量
            return lastOffset;

        } catch (IOException e) {
            System.err.println("[SSTable] Failed to read index file: " + e.getMessage());
            return null;
        }
    }

    /**
     * 在数据文件中从指定偏移量开始查找（使用随机访问）
     */
    private String searchInDataFile(String key, long startOffset) {
        if (!dataFile.exists()) {
            return null;
        }

        try (RandomAccessFile raf = new RandomAccessFile(dataFile, "r")) {
            // 定位到起始偏移量
            raf.seek(startOffset);
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(raf.getFD()), Constants.ENCODING));
            
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                int spaceIdx = line.indexOf(' ');
                if (spaceIdx > 0) {
                    String storedKey = line.substring(0, spaceIdx);
                    String storedValue = line.substring(spaceIdx + 1);

                    if (storedKey.equals(key)) {
                        return storedValue;
                    } else if (storedKey.compareTo(key) > 0) {
                        // 键已经大于目标键，说明不在这个文件中
                        return null;
                    }
                }
            }

        } catch (IOException e) {
            System.err.println("[SSTable] Failed to read data file: " + e.getMessage());
        }

        return null;
    }

    /**
     * 获取文件大小
     */
    public long getSize() {
        return size;
    }

    /**
     * 获取文件
     */
    public File getDataFile() {
        return dataFile;
    }

    /**
     * 获取索引文件
     */
    public File getIndexFile() {
        return indexFile;
    }

    /**
     * 获取层级
     */
    public int getLevel() {
        return level;
    }

    /**
     * 获取文件索引
     */
    public long getFileIndex() {
        return fileIndex;
    }

    /**
     * 检查文件是否存在
     */
    public boolean exists() {
        return dataFile.exists() && indexFile.exists();
    }

    /**
     * 删除文件
     */
    public void delete() {
        dataFile.delete();
        indexFile.delete();
    }

    /**
     * 获取所有键（用于合并）
     */
    public List<String> getAllKeys() {
        List<String> keys = new ArrayList<>();
        
        if (!dataFile.exists()) {
            return keys;
        }

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(dataFile), Constants.ENCODING));

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                int spaceIdx = line.indexOf(' ');
                if (spaceIdx > 0) {
                    keys.add(line.substring(0, spaceIdx));
                }
            }

        } catch (IOException e) {
            System.err.println("[SSTable] Failed to read all keys: " + e.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }

        return keys;
    }

    /**
     * 获取所有键值对（用于合并）
     */
    public List<Map.Entry<String, String>> getAllEntries() {
        List<Map.Entry<String, String>> entries = new ArrayList<>();
        
        if (!dataFile.exists()) {
            return entries;
        }

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(dataFile), Constants.ENCODING));

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                int spaceIdx = line.indexOf(' ');
                if (spaceIdx > 0) {
                    final String key = line.substring(0, spaceIdx);
                    final String value = line.substring(spaceIdx + 1);
                    entries.add(new AbstractMap.SimpleEntry<>(key, value));
                }
            }

        } catch (IOException e) {
            System.err.println("[SSTable] Failed to read all entries: " + e.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }

        return entries;
    }
}