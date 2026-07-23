package com.easydb.server.engine.disk;

import com.easydb.common.constants.Constants;
import com.easydb.server.engine.index.SparseIndex;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

public class DataFileManager {

    private static final String DATA_FILE_PREFIX = Constants.DATA_DIR + "/easy-db-";
    private AtomicLong currentFileIndex = new AtomicLong(0);
    private volatile File currentFile;
    private volatile BufferedWriter currentWriter;
    private volatile long currentFileSize = 0;
    private Compactor compactor;

    public DataFileManager() {
        init();
    }

    public void setCompactor(Compactor compactor) {
        this.compactor = compactor;
    }

    private void init() {
        try {
            Path dataPath = Paths.get(Constants.DATA_DIR);
            if (!Files.exists(dataPath)) {
                Files.createDirectories(dataPath);
            }
            rotate();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize data file manager", e);
        }
    }

    private synchronized void rotate() throws IOException {
        File oldFile = currentFile;
        
        if (currentWriter != null) {
            currentWriter.close();
        }
        
        File[] existingFiles = new File(Constants.DATA_DIR).listFiles((dir, name) -> 
            name.startsWith("easy-db-") && (name.endsWith(Constants.DATA_FILE_SUFFIX) || name.endsWith(Constants.GZ_FILE_SUFFIX)));
        
        if (existingFiles != null && existingFiles.length > 0) {
            long maxIndex = 0;
            File lastFile = null;
            for (File f : existingFiles) {
                String name = f.getName();
                try {
                    // 提取文件索引（去掉后缀）
                    String baseName = name;
                    if (baseName.endsWith(Constants.GZ_FILE_SUFFIX)) {
                        baseName = baseName.substring(0, baseName.length() - Constants.GZ_FILE_SUFFIX.length());
                    }
                    if (baseName.endsWith(Constants.DATA_FILE_SUFFIX)) {
                        baseName = baseName.substring(0, baseName.length() - Constants.DATA_FILE_SUFFIX.length());
                    }
                    if (baseName.startsWith("easy-db-")) {
                        long index = Long.parseLong(baseName.substring("easy-db-".length()));
                        if (index > maxIndex) {
                            maxIndex = index;
                            lastFile = f;
                        }
                    }
                } catch (NumberFormatException e) {
                }
            }
            
            // 检查最后一个非压缩文件是否为空
            File lastDataFile = new File(DATA_FILE_PREFIX + maxIndex + Constants.DATA_FILE_SUFFIX);
            if (lastDataFile.exists() && lastDataFile.length() == 0) {
                currentFileIndex.set(maxIndex);
            } else {
                currentFileIndex.set(maxIndex + 1);
            }
        }
        
        currentFile = new File(DATA_FILE_PREFIX + currentFileIndex.get() + Constants.DATA_FILE_SUFFIX);
        currentWriter = new BufferedWriter(new FileWriter(currentFile, true));
        currentFileSize = currentFile.length();
        
        // 对旧文件进行压缩（仅对非压缩文件）
        if (oldFile != null && oldFile.exists() && oldFile.length() > 0 && compactor != null) {
            if (!oldFile.getName().endsWith(Constants.GZ_FILE_SUFFIX)) {
                compactor.submitCompaction(oldFile);
            }
        }
    }

    public synchronized WriteResult write(String key, String value) throws IOException {
        if (currentFileSize >= Constants.DATA_FILE_MAX_SIZE) {
            rotate();
        }
        
        String line = key + " " + value + Constants.LINE_SEPARATOR;
        long offset = currentFileSize;
        currentWriter.write(line);
        currentWriter.flush();
        long bytesWritten = line.getBytes(Constants.ENCODING).length;
        currentFileSize += bytesWritten;
        
        return new WriteResult(currentFileIndex.get(), offset, value.length());
    }

    /**
     * 写入结果 - 返回写入位置信息
     */
    public static class WriteResult {
        private final long fileIndex;
        private final long offset;
        private final int valueLength;

        public WriteResult(long fileIndex, long offset, int valueLength) {
            this.fileIndex = fileIndex;
            this.offset = offset;
            this.valueLength = valueLength;
        }

        public long getFileIndex() {
            return fileIndex;
        }

        public long getOffset() {
            return offset;
        }

        public int getValueLength() {
            return valueLength;
        }
    }

    /**
     * 扫描所有数据文件（包括压缩文件）查找指定 key
     * 按文件索引从大到小扫描（优先查找最新的数据）
     */
    public String scanByKey(String key) {
        File[] dataFiles = new File(Constants.DATA_DIR).listFiles((dir, name) -> 
            name.startsWith("easy-db-") && (name.endsWith(Constants.DATA_FILE_SUFFIX) || name.endsWith(Constants.GZ_FILE_SUFFIX)));
        
        if (dataFiles == null || dataFiles.length == 0) {
            return null;
        }
        
        // 按文件索引从大到小排序（最新文件优先）
        List<File> fileList = new ArrayList<>(Arrays.asList(dataFiles));
        fileList.sort((f1, f2) -> {
            long idx1 = extractIndex(f1.getName());
            long idx2 = extractIndex(f2.getName());
            return Long.compare(idx2, idx1);
        });
        
        for (File file : fileList) {
            String result = searchInFile(file, key);
            if (result != null) {
                return result;
            }
        }
        
        return null;
    }

    /**
     * 从文件名中提取索引
     */
    private long extractIndex(String fileName) {
        try {
            String baseName = fileName;
            if (baseName.endsWith(Constants.GZ_FILE_SUFFIX)) {
                baseName = baseName.substring(0, baseName.length() - Constants.GZ_FILE_SUFFIX.length());
            }
            if (baseName.endsWith(Constants.DATA_FILE_SUFFIX)) {
                baseName = baseName.substring(0, baseName.length() - Constants.DATA_FILE_SUFFIX.length());
            }
            if (baseName.startsWith("easy-db-")) {
                return Long.parseLong(baseName.substring("easy-db-".length()));
            }
        } catch (NumberFormatException e) {
        }
        return 0;
    }

    /**
     * 在单个文件中查找 key
     * 支持 .data 和 .gz 文件
     */
    private String searchInFile(File file, String key) {
        BufferedReader reader = null;
        try {
            if (file.getName().endsWith(Constants.GZ_FILE_SUFFIX)) {
                reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(file)), Constants.ENCODING));
            } else {
                reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), Constants.ENCODING));
            }
            
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
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[DataFileManager] Failed to read file " + file.getName() + ": " + e.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
        return null;
    }

    public File getCurrentFile() {
        return currentFile;
    }

    /**
     * 获取所有数据文件列表（按索引排序）
     */
    public List<File> getDataFiles() {
        File[] dataFiles = new File(Constants.DATA_DIR).listFiles((dir, name) -> 
            name.startsWith("easy-db-") && (name.endsWith(Constants.DATA_FILE_SUFFIX) || name.endsWith(Constants.GZ_FILE_SUFFIX)));
        
        if (dataFiles == null || dataFiles.length == 0) {
            return new ArrayList<>();
        }
        
        List<File> fileList = new ArrayList<>(Arrays.asList(dataFiles));
        fileList.sort((f1, f2) -> Long.compare(extractIndex(f1.getName()), extractIndex(f2.getName())));
        return fileList;
    }

    /**
     * 通过索引直接读取数据
     * 使用文件通道实现随机访问，避免全文件扫描
     */
    public String readByIndex(SparseIndex.IndexEntry entry) {
        // 构建文件名
        String fileName = DATA_FILE_PREFIX + entry.getFileIndex() + Constants.DATA_FILE_SUFFIX;
        File file = new File(fileName);
        
        // 如果普通文件不存在，尝试压缩文件
        if (!file.exists()) {
            file = new File(fileName + Constants.GZ_FILE_SUFFIX);
        }
        
        if (!file.exists()) {
            return null;
        }
        
        // 对于压缩文件，无法直接随机访问，回退到扫描
        if (file.getName().endsWith(Constants.GZ_FILE_SUFFIX)) {
            return scanByKeyFromFile(file, entry);
        }
        
        // 对于普通文件，使用文件通道直接定位读取
        try (RandomAccessFile raf = new RandomAccessFile(file, "r");
             FileChannel channel = raf.getChannel()) {
            
            // 定位到偏移量
            long keyLength = getKeyLengthAtOffset(channel, entry.getOffset());
            if (keyLength <= 0) {
                return null;
            }
            
            // 跳过 key 和空格，读取 value
            long valueOffset = entry.getOffset() + keyLength + 1; // +1 for space
            int valueLength = entry.getValueLength();
            
            ByteBuffer buffer = ByteBuffer.allocate(valueLength);
            channel.position(valueOffset);
            int bytesRead = channel.read(buffer);
            
            if (bytesRead > 0) {
                buffer.flip();
                byte[] valueBytes = new byte[bytesRead];
                buffer.get(valueBytes);
                return new String(valueBytes, Constants.ENCODING).trim();
            }
            
        } catch (IOException e) {
            System.err.println("[DataFileManager] Failed to read by index: " + e.getMessage());
        }
        
        return null;
    }

    /**
     * 获取指定偏移量处 key 的长度
     */
    private long getKeyLengthAtOffset(FileChannel channel, long offset) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        channel.position(offset);
        int bytesRead = channel.read(buffer);
        
        if (bytesRead <= 0) {
            return -1;
        }
        
        buffer.flip();
        byte[] data = new byte[bytesRead];
        buffer.get(data);
        
        // 查找第一个空格
        for (int i = 0; i < data.length; i++) {
            if (data[i] == ' ') {
                return i;
            }
        }
        
        return -1;
    }

    /**
     * 从压缩文件中扫描查找（索引方式不适用压缩文件）
     */
    private String scanByKeyFromFile(File file, SparseIndex.IndexEntry entry) {
        // 这里我们无法直接使用索引读取压缩文件，
        // 需要回退到全文件扫描，但可以利用 key 信息加速
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(file)), Constants.ENCODING));
            
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                
                int spaceIdx = line.indexOf(' ');
                if (spaceIdx > 0) {
                    String storedValue = line.substring(spaceIdx + 1);
                    if (storedValue.length() == entry.getValueLength()) {
                        return storedValue;
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[DataFileManager] Failed to scan compressed file: " + e.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
        return null;
    }

    public void close() {
        try {
            if (currentWriter != null) {
                currentWriter.close();
            }
        } catch (IOException e) {
            System.err.println("[DataFileManager] Failed to close writer: " + e.getMessage());
        }
    }
}