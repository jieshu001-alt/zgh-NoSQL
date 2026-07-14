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
import java.util.List;
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

    public synchronized void batchWrite(String command, java.util.Map<String, String> entries) {
        try {
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, String> entry : entries.entrySet()) {
                sb.append(command).append(" ").append(entry.getKey()).append(" ").append(entry.getValue() != null ? entry.getValue() : "").append(Constants.LINE_SEPARATOR);
            }
            byte[] data = ByteUtils.toBytes(sb.toString());
            ByteBuffer buffer = ByteBuffer.wrap(data);
            writeChannel.write(buffer);
            writeChannel.force(true);
        } catch (IOException e) {
            throw new RuntimeException("Failed to batch write WAL", e);
        }
    }

    public synchronized void batchWriteDel(String command, java.util.List<String> keys) {
        try {
            StringBuilder sb = new StringBuilder();
            for (String key : keys) {
                sb.append(command).append(" ").append(key).append(" ").append(Constants.LINE_SEPARATOR);
            }
            byte[] data = ByteUtils.toBytes(sb.toString());
            ByteBuffer buffer = ByteBuffer.wrap(data);
            writeChannel.write(buffer);
            writeChannel.force(true);
        } catch (IOException e) {
            throw new RuntimeException("Failed to batch write WAL", e);
        }
    }

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
                String[] parts = line.split(" ", 3);
                if (parts.length >= 2) {
                    String command = parts[0];
                    String key = parts[1];
                    String value = parts.length == 3 ? parts[2] : null;
                    records.add(new String[]{command, key, value});
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
