package com.easydb.server.engine.disk;

import com.easydb.common.constants.Constants;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;

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
            name.endsWith(Constants.DATA_FILE_SUFFIX) && !name.endsWith(Constants.GZ_FILE_SUFFIX));
        
        if (existingFiles != null && existingFiles.length > 0) {
            long maxIndex = 0;
            File lastFile = null;
            for (File f : existingFiles) {
                String name = f.getName();
                try {
                    long index = Long.parseLong(name.substring("easy-db-".length(), name.length() - ".data".length()));
                    if (index > maxIndex) {
                        maxIndex = index;
                        lastFile = f;
                    }
                } catch (NumberFormatException e) {
                }
            }
            
            if (lastFile != null && lastFile.length() == 0) {
                currentFileIndex.set(maxIndex);
            } else {
                currentFileIndex.set(maxIndex + 1);
            }
        }
        
        currentFile = new File(DATA_FILE_PREFIX + currentFileIndex.get() + Constants.DATA_FILE_SUFFIX);
        currentWriter = new BufferedWriter(new FileWriter(currentFile, true));
        currentFileSize = currentFile.length();
        
        if (oldFile != null && oldFile.exists() && oldFile.length() > 0 && compactor != null) {
            compactor.submitCompaction(oldFile);
        }
    }

    public synchronized void write(String key, String value) throws IOException {
        if (currentFileSize >= Constants.DATA_FILE_MAX_SIZE) {
            rotate();
        }
        
        String line = key + " " + value + Constants.LINE_SEPARATOR;
        currentWriter.write(line);
        currentWriter.flush();
        currentFileSize += line.getBytes(Constants.ENCODING).length;
    }

    public String scanByKey(String key) {
        File[] dataFiles = new File(Constants.DATA_DIR).listFiles((dir, name) -> 
            name.endsWith(Constants.DATA_FILE_SUFFIX) && !name.endsWith(Constants.GZ_FILE_SUFFIX));
        
        if (dataFiles == null || dataFiles.length == 0) {
            return null;
        }
        
        for (int i = dataFiles.length - 1; i >= 0; i--) {
            try (BufferedReader reader = new BufferedReader(new FileReader(dataFiles[i]))) {
                String line;
                while ((line = reader.readLine()) != null) {
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
                // ignore
            }
        }
        
        return null;
    }

    public File getCurrentFile() {
        return currentFile;
    }

    public void close() {
        try {
            if (currentWriter != null) {
                currentWriter.close();
            }
        } catch (IOException e) {
            // ignore
        }
    }
}
