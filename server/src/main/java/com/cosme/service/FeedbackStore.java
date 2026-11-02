package com.cosme.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** 本机反馈存储：一行一条 JSONL，不做第三方上报。 */
@Service
public class FeedbackStore {

    private final ObjectMapper om = new ObjectMapper();
    private final Path dir = DataPaths.dir();
    private final Path file = dir.resolve("feedback.jsonl");

    public synchronized void append(Map<String, Object> feedback) throws IOException {
        Files.createDirectories(dir);
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("receivedAt", Instant.now().toString());
        wrapper.putAll(feedback);
        String line = om.writeValueAsString(wrapper) + System.lineSeparator();
        Files.write(file, line.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public Path filePath() {
        return file;
    }

    /** 管理台列表：最新在前；最多返回 limit 条 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> list(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.exists(file)) return out;
        int max = limit <= 0 ? 100 : limit;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0 && out.size() < max; i--) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                try {
                    out.add(om.readValue(line, Map.class));
                } catch (Exception ignored) {
                    // 跳过损坏行，不影响其他反馈
                }
            }
        } catch (IOException e) {
            System.err.println("[反馈] 读取失败：" + e.getMessage());
        }
        return out;
    }

    public int count() {
        if (!Files.exists(file)) return 0;
        int n = 0;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.trim().isEmpty()) n++;
            }
        } catch (IOException e) {
            return 0;
        }
        return n;
    }
}
