package com.easydb.server.engine.disk;

import com.easydb.common.constants.Constants;
import com.easydb.common.utils.ByteUtils;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class WalManager {

    private static final String WAL_FILE = Constants.DATA_DIR + "/easy-db" + Constants.WAL_FILE_SUFFIX;
    private FileChannel writeChannel;

    public WalManager() {
        init();
    }

    private void init() {
        try {
            Path dataPath = Paths.get(Constants.DATA_DIR);
            if (!Files.exists(dataPath)) {
                Files.createDirectories(dataPath);
            }
            File file = new File(WAL_FILE);
            writeChannel = FileChannel.open(file.toPath(), 
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize WAL manager", e);
        }
    }

    public synchronized void write(String command, String key, String value) {
        try {
            String line = command + " " + key + " " + (value != null ? value : "") + Constants.LINE_SEPARATOR;
            byte[] data = ByteUtils.toBytes(line);
            ByteBuffer buffer = ByteBuffer.wrap(data);
            writeChannel.write(buffer);
            writeChannel.force(true);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write WAL", e);
        }
    }

    public synchronized void batchWrite(String command, Map<String, String> entries) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(command).append(" ");
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                sb.append(entry.getKey()).append(" ").append(entry.getValue() != null ? entry.getValue() : "").append(" ");
            }
            sb.append(Constants.LINE_SEPARATOR);
            byte[] data = ByteUtils.toBytes(sb.toString());
            ByteBuffer buffer = ByteBuffer.wrap(data);
            writeChannel.write(buffer);
            writeChannel.force(true);
        } catch (IOException e) {
            throw new RuntimeException("Failed to batch write WAL", e);
        }
    }

    public synchronized void batchWriteDel(String command, List<String> keys) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(command).append(" ");
            for (String key : keys) {
                sb.append(key).append(" ");
            }
            sb.append(Constants.LINE_SEPARATOR);
            byte[] data = ByteUtils.toBytes(sb.toString());
            ByteBuffer buffer = ByteBuffer.wrap(data);
            writeChannel.write(buffer);
            writeChannel.force(true);
        } catch (IOException e) {
            throw new RuntimeException("Failed to batch write WAL", e);
        }
    }

    /**
     * 回放 WAL，支持所有命令格式
     * @return 记录列表，格式：[command, key1, value1, key2, value2, ...]
     */
    public synchronized List<String[]> replay() {
        List<String[]> records = new CopyOnWriteArrayList<>();
        File file = new File(WAL_FILE);
        
        if (!file.exists()) {
            return records;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                
                String[] parts = line.split("\\s+");
                if (parts.length >= 1) {
                    String command = parts[0].toUpperCase();
                    
                    // 根据命令类型处理不同格式
                    switch (command) {
                        case Constants.COMMAND_SET:
                            if (parts.length >= 3) {
                                String key = parts[1];
                                StringBuilder valueBuilder = new StringBuilder();
                                for (int i = 2; i < parts.length; i++) {
                                    if (i > 2) valueBuilder.append(" ");
                                    valueBuilder.append(parts[i]);
                                }
                                records.add(new String[]{command, key, valueBuilder.toString()});
                            }
                            break;
                        case Constants.COMMAND_DEL:
                            if (parts.length >= 2) {
                                records.add(new String[]{command, parts[1], null});
                            }
                            break;
                        case Constants.COMMAND_MSET:
                            if (parts.length >= 4 && (parts.length - 1) % 2 == 0) {
                                String[] msetRecord = new String[parts.length];
                                System.arraycopy(parts, 0, msetRecord, 0, parts.length);
                                records.add(msetRecord);
                            }
                            break;
                        case Constants.COMMAND_MDEL:
                            if (parts.length >= 2) {
                                String[] mdelRecord = new String[parts.length];
                                System.arraycopy(parts, 0, mdelRecord, 0, parts.length);
                                records.add(mdelRecord);
                            }
                            break;
                        case Constants.COMMAND_CREATE:
                            if (parts.length >= 2) {
                                records.add(new String[]{command, parts[1], null});
                            }
                            break;
                        case Constants.COMMAND_DROP:
                            if (parts.length >= 2) {
                                records.add(new String[]{command, parts[1], null});
                            }
                            break;
                        default:
                            // 未知命令，记录原始格式
                            records.add(parts);
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to replay WAL", e);
        }
        
        return records;
    }

    public synchronized void clear() {
        try {
            writeChannel.position(0);
            writeChannel.truncate(0);
            writeChannel.force(true);
        } catch (IOException e) {
            throw new RuntimeException("Failed to clear WAL", e);
        }
    }

    public void close() {
        try {
            if (writeChannel != null) {
                writeChannel.close();
            }
        } catch (IOException e) {
            // ignore
        }
    }
}