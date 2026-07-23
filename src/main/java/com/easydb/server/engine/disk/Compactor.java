package com.easydb.server.engine.disk;

import com.easydb.common.constants.Constants;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class Compactor {

    private static final String ARCHIVE_DIR = Constants.DATA_DIR + "/archive";
    
    private final ExecutorService executor = Executors.newFixedThreadPool(Constants.COMPACTOR_CORE_THREADS);

    public Compactor() {
        initArchiveDir();
    }

    private void initArchiveDir() {
        try {
            Path archivePath = Paths.get(ARCHIVE_DIR);
            if (!Files.exists(archivePath)) {
                Files.createDirectories(archivePath);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize archive directory", e);
        }
    }

    public void submitCompaction(File file) {
        executor.submit(() -> {
            try {
                // 1. 读取文件内容，去重并保留最新版本
                Map<String, String> keyValueMap = readAndDeduplicate(file);
                
                if (keyValueMap.isEmpty()) {
                    // 文件内容为空，直接删除
                    file.delete();
                    return;
                }
                
                // 2. 生成压缩文件名（保留原文件名，加 .gz 后缀）
                String fileName = file.getName();
                String gzFileName = fileName + Constants.GZ_FILE_SUFFIX;
                File gzFile = new File(file.getParent(), gzFileName);
                
                // 3. 按 key 排序后写入压缩文件
                writeSortedToGzip(keyValueMap, gzFile);
                
                // 4. 压缩成功后删除原文件
                file.delete();
                
                System.out.println("[Compactor] Compressed " + fileName + " to " + gzFileName 
                    + ", entries: " + keyValueMap.size());
                
            } catch (IOException e) {
                System.err.println("[Compactor] Failed to compact file " + file.getName() + ": " + e.getMessage());
            }
        });
    }

    /**
     * 读取文件内容，去重并保留最新版本
     */
    private Map<String, String> readAndDeduplicate(File file) throws IOException {
        Map<String, String> keyValueMap = new ConcurrentHashMap<>();
        
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
                    String key = line.substring(0, spaceIdx);
                    String value = line.substring(spaceIdx + 1);
                    // 保留最新版本（后面的覆盖前面的）
                    keyValueMap.put(key, value);
                }
            }
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
        
        return keyValueMap;
    }

    /**
     * 按 key 排序后写入压缩文件
     */
    private void writeSortedToGzip(Map<String, String> keyValueMap, File gzFile) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(gzFile)), Constants.ENCODING))) {
            
            keyValueMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    try {
                        writer.write(entry.getKey() + " " + entry.getValue() + Constants.LINE_SEPARATOR);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        }
    }

    public void shutdown() {
        executor.shutdown();
    }
}