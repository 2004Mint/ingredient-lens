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
 * 把总 API / 视觉 API / 研究 API 落到 server/data/ai-settings.json。
 * 文件里带明文 Key，data/ 已加入 .gitignore。
 */
@Service
public class AiSettingsStore {

    private final ObjectMapper om = new ObjectMapper();
    private final Path dir = Paths.get(System.getProperty("user.dir"), "data");
    private final Path file = dir.resolve("ai-settings.json");
    private final Path legacyFile = dir.resolve("vision-settings.json");

    public Path filePath() {
        return file;
    }

    public AiSettings load() {
        if (Files.exists(file)) {
            try {
                AiSettings settings = om.readValue(file.toFile(), AiSettings.class);
                settings.normalize();
                return settings;
            } catch (Exception e) {
                System.err.println("[AI 配置] 读取 ai-settings.json 失败：" + e.getMessage());
                return null;
            }
        }

        // 兼容旧版单独的视觉配置：把它迁到 vision 覆盖块，其他字段留空走总 API
        if (Files.exists(legacyFile)) {
            try {
                VisionSettings old = om.readValue(legacyFile.toFile(), VisionSettings.class);
                AiSettings settings = new AiSettings();
                settings.vision = old;
                settings.normalize();
                System.out.println("[AI 配置] 已兼容读取旧版 vision-settings.json");
                return settings;
            } catch (Exception e) {
                System.err.println("[AI 配置] 读取旧版视觉配置失败：" + e.getMessage());
            }
        }
        return null;
    }

    public synchronized void save(AiSettings settings) throws IOException {
        settings.normalize();
        settings.updatedAt = Instant.now().toString();
        Files.createDirectories(dir);

        Path tmp = Files.createTempFile(dir, "ai-settings-", ".tmp");
        om.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), settings);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
