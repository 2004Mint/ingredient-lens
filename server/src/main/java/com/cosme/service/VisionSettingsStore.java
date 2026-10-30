package com.cosme.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * 把管理台保存的视觉配置落到 server/data/vision-settings.json。
 * 文件里带明文 Key，所以 data/ 目录已经加入 .gitignore；不要提交。
 */
@Service
public class VisionSettingsStore {

    private final ObjectMapper om = new ObjectMapper();
    private final Path dir = Paths.get(System.getProperty("user.dir"), "data");
    private final Path file = dir.resolve("vision-settings.json");

    public Path filePath() {
        return file;
    }

    public VisionSettings load() {
        if (!Files.exists(file)) return null;
        try {
            return om.readValue(file.toFile(), VisionSettings.class);
        } catch (Exception e) {
            System.err.println("[配置] 读取视觉配置失败：" + e.getMessage());
            return null;
        }
    }

    public synchronized void save(VisionSettings settings) throws IOException {
        Files.createDirectories(dir);
        settings.updatedAt = Instant.now().toString();

        Path tmp = Files.createTempFile(dir, "vision-settings-", ".tmp");
        om.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), settings);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
