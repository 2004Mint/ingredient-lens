package com.cosme.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/**
 * 分享报告的本地存储。
 *
 * 这是一个单机产品：报告以不可猜测的短 ID 存在 server/data/reports/，
 * 任何人拿到链接都能查看，所以链接里包含的档案信息会在前端明确提示。
 * 30 天后自动清理，避免无限堆积。
 */
@Service
public class ReportStore {

    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final int ID_LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectMapper om = new ObjectMapper();
    private final Path dir = DataPaths.resolve("reports");

    @PostConstruct
    @Scheduled(fixedDelay = 6L * 60 * 60 * 1000)
    public void cleanupExpired() {
        if (!Files.isDirectory(dir)) return;
        long cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
        int removed = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                try {
                    if (Files.getLastModifiedTime(file).toMillis() < cutoff) {
                        Files.delete(file);
                        removed++;
                    }
                } catch (IOException ignored) {
                    // 单个文件清理失败不影响启动
                }
            }
        } catch (IOException e) {
            System.err.println("[分享] 扫描过期报告失败：" + e.getMessage());
        }
        if (removed > 0) {
            System.out.println("[分享] 已清理 " + removed + " 份超过 30 天的分享报告");
        }
    }

    public String save(Map<String, Object> payload) throws IOException {
        Files.createDirectories(dir);

        String id;
        Path target;
        do {
            id = newId();
            target = dir.resolve(id + ".json");
        } while (Files.exists(target));

        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("id", id);
        wrapper.put("sharedAt", Instant.now().toString());
        wrapper.putAll(payload);

        Path tmp = Files.createTempFile(dir, "report-", ".tmp");
        om.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), wrapper);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return id;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> load(String id) throws IOException {
        if (!validId(id)) return null;
        Path file = dir.resolve(id + ".json");
        if (!Files.exists(file)) return null;
        return om.readValue(file.toFile(), Map.class);
    }

    public Path directory() {
        return dir;
    }

    /** 管理台列表：只取元信息，不返回整份报告，避免一次拉太多 */
    public List<Map<String, Object>> list(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                try {
                    Map<String, Object> data = om.readValue(file.toFile(), Map.class);
                    Map<String, Object> meta = asMap(data.get("meta"));
                    Map<String, Object> report = asMap(data.get("report"));
                    Map<String, Object> verdict = asMap(report.get("verdict"));

                    Map<String, Object> item = new LinkedHashMap<>();
                    String id = data.get("id") == null
                            ? file.getFileName().toString().replace(".json", "")
                            : String.valueOf(data.get("id"));
                    item.put("id", id);
                    item.put("name", data.get("name") == null ? "" : data.get("name"));
                    item.put("sharedAt", data.get("sharedAt") == null ? "" : data.get("sharedAt"));
                    item.put("analysisId", meta.get("id") == null ? "" : meta.get("id"));
                    item.put("level", verdict.get("level") == null ? "" : verdict.get("level"));
                    item.put("headline", verdict.get("headline") == null ? "" : verdict.get("headline"));
                    item.put("matched", report.get("matched") == null ? 0 : report.get("matched"));
                    item.put("total", report.get("total") == null ? 0 : report.get("total"));
                    Object text = data.get("text");
                    item.put("textLength", text == null ? 0 : text.toString().length());
                    item.put("size", Files.size(file));
                    out.add(item);
                } catch (Exception e) {
                    System.err.println("[分享] 读取列表时跳过 " + file.getFileName() + "：" + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[分享] 读取分享列表失败：" + e.getMessage());
        }

        out.sort((a, b) -> String.valueOf(b.get("sharedAt")).compareTo(String.valueOf(a.get("sharedAt"))));
        if (limit > 0 && out.size() > limit) return new ArrayList<>(out.subList(0, limit));
        return out;
    }

    public int count() {
        if (!Files.isDirectory(dir)) return 0;
        int n = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path ignored : stream) n++;
        } catch (IOException e) {
            return 0;
        }
        return n;
    }

    public boolean delete(String id) throws IOException {
        if (!validId(id)) return false;
        return Files.deleteIfExists(dir.resolve(id + ".json"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }

    private boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9]{6,16}");
    }

    private String newId() {
        StringBuilder sb = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < ID_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
