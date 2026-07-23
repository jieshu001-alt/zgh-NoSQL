package com.easydb.server.engine.disk;

import com.easydb.common.constants.Constants;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
            // ignore
        }
    }

    public void submitCompaction(File file) {
        executor.submit(() -> {
            try {
                String fileName = file.getName();
                String gzFileName = fileName + Constants.GZ_FILE_SUFFIX;
                File gzFile = new File(file.getParent(), gzFileName);
                
                try (FileInputStream fis = new FileInputStream(file);
                     GZIPOutputStream gzos = new GZIPOutputStream(new FileOutputStream(gzFile))) {
                    
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        gzos.write(buffer, 0, bytesRead);
                    }
                }
                
                File archiveFile = new File(ARCHIVE_DIR, gzFileName);
                Files.move(gzFile.toPath(), archiveFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                
                file.delete();
            } catch (IOException e) {
                // ignore
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
    }
}
