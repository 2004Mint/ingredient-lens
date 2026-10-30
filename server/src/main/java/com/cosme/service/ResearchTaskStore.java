package com.cosme.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/**
 * 研究任务队列的本地持久化。
 *
 * 每一条未收录 token 都会先进入这里，不再只在 AI 成功后才留痕。
 * worker 每次认领一条，成功后写成分库，失败保留错误和重试时间。
 */
@Service
public class ResearchTaskStore {

    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectMapper om = new ObjectMapper();
    private final Path dir = Paths.get(System.getProperty("user.dir"), "data");
    private final Path file = dir.resolve("research-tasks.json");

    private final List<ResearchTask> tasks = new ArrayList<>();

    @PostConstruct
    public synchronized void load() {
        if (!Files.exists(file)) return;
        try {
            List<ResearchTask> loaded = om.readValue(file.toFile(), new TypeReference<List<ResearchTask>>() {
            });
            tasks.clear();
            if (loaded != null) {
                for (ResearchTask task : loaded) {
                    if (task == null || task.id == null || task.query == null) continue;
                    normalize(task);
                    // 后端重启时，正在研究的任务回到队列，避免永久卡住
                    if (ResearchTask.RESEARCHING.equals(task.status)) {
                        task.status = ResearchTask.QUEUED;
                        task.error = "服务重启，重新排队";
                        task.updatedAt = Instant.now().toString();
                    }
                    tasks.add(task);
                }
            }
            System.out.println("[研究队列] 已加载 " + tasks.size() + " 条任务，配置=" + file);
        } catch (Exception e) {
            System.err.println("[研究队列] 读取失败：" + e.getMessage());
        }
    }

    public Path filePath() {
        return file;
    }

    /** 分析时把未收录 token 入队；同一 token 只保留一条任务 */
    public synchronized EnqueueResult enqueue(List<String> queries, String context) {
        EnqueueResult result = new EnqueueResult();
        if (queries == null || queries.isEmpty()) return result;

        String ctx = context == null ? "" : context.trim();
        if (ctx.length() > 1000) ctx = ctx.substring(0, 1000);

        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String raw : queries) {
            if (raw == null) continue;
            String query = raw.trim();
            if (!query.isEmpty()) unique.add(query);
        }

        boolean changed = false;
        for (String query : unique) {
            ResearchTask existing = findLocked(query);
            if (existing == null) {
                ResearchTask task = new ResearchTask();
                task.id = newIdLocked();
                task.query = query;
                task.context = ctx;
                task.source = "auto";
                task.status = ResearchTask.QUEUED;
                task.createdAt = Instant.now().toString();
                task.updatedAt = task.createdAt;
                tasks.add(task);
                result.created++;
                changed = true;
            }
        }
        if (changed) saveLocked();

        for (String query : unique) {
            ResearchTask task = findLocked(query);
            if (task == null) continue;
            if (ResearchTask.QUEUED.equals(task.status)) result.queued++;
            else if (ResearchTask.RESEARCHING.equals(task.status)) result.researching++;
            else if (ResearchTask.FAILED.equals(task.status)) result.failed++;
            else if (ResearchTask.DONE.equals(task.status)) result.done++;
        }
        result.active = result.queued + result.researching + result.failed;
        return result;
    }

    public synchronized List<ResearchTask> list(String status, int limit) {
        int max = Math.max(1, Math.min(limit, 200));
        List<ResearchTask> out = new ArrayList<>();
        for (ResearchTask task : tasks) {
            if (status != null && !status.isEmpty() && !"all".equals(status)) {
                if ("active".equals(status)) {
                    if (ResearchTask.DONE.equals(task.status)) continue;
                } else if (!status.equals(task.status)) {
                    continue;
                }
            }
            out.add(task);
        }
        out.sort((a, b) -> String.valueOf(b.createdAt).compareTo(String.valueOf(a.createdAt)));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    public synchronized Map<String, Integer> counts() {
        int queued = 0, researching = 0, failed = 0, done = 0;
        for (ResearchTask task : tasks) {
            if (ResearchTask.QUEUED.equals(task.status)) queued++;
            else if (ResearchTask.RESEARCHING.equals(task.status)) researching++;
            else if (ResearchTask.FAILED.equals(task.status)) failed++;
            else if (ResearchTask.DONE.equals(task.status)) done++;
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("queued", queued);
        out.put("researching", researching);
        out.put("failed", failed);
        out.put("done", done);
        out.put("active", queued + researching + failed);
        out.put("total", tasks.size());
        return out;
    }

    /** worker 认领一条：优先排队，其次到时间重试的失败任务 */
    public synchronized ResearchTask claimNext() {
        long now = System.currentTimeMillis();
        ResearchTask best = null;
        for (ResearchTask task : tasks) {
            boolean eligible = ResearchTask.QUEUED.equals(task.status)
                    || (ResearchTask.FAILED.equals(task.status)
                    && task.attempts < 3
                    && task.nextAttemptAt <= now);
            if (!eligible) continue;
            if (best == null || String.valueOf(task.createdAt).compareTo(String.valueOf(best.createdAt)) < 0) {
                best = task;
            }
        }
        if (best == null) return null;
        best.status = ResearchTask.RESEARCHING;
        best.updatedAt = Instant.now().toString();
        saveLocked();
        return best;
    }

    public synchronized void complete(String id, String resultName, Double confidence, String model, String evidence) {
        ResearchTask task = byIdLocked(id);
        if (task == null) return;
        task.status = ResearchTask.DONE;
        task.resultName = resultName;
        task.confidence = confidence;
        task.model = model;
        task.evidence = evidence == null ? "" : evidence;
        task.error = "";
        task.updatedAt = Instant.now().toString();
        saveLocked();
    }

    public synchronized void fail(String id, String message) {
        ResearchTask task = byIdLocked(id);
        if (task == null) return;
        task.attempts++;
        task.status = ResearchTask.FAILED;
        task.error = message == null ? "研究失败" : (message.length() > 300 ? message.substring(0, 300) : message);
        long minutes = Math.min(30, (long) Math.pow(2, Math.min(task.attempts, 4)));
        task.nextAttemptAt = System.currentTimeMillis() + minutes * 60_000;
        task.updatedAt = Instant.now().toString();
        saveLocked();
    }

    public synchronized int retry(List<String> ids, boolean allFailed) {
        Set<String> wanted = new HashSet<>();
        if (ids != null) {
            for (String id : ids) if (id != null) wanted.add(id);
        }
        int changed = 0;
        for (ResearchTask task : tasks) {
            boolean hit = allFailed ? ResearchTask.FAILED.equals(task.status) : wanted.contains(task.id);
            if (!hit) continue;
            if (!ResearchTask.FAILED.equals(task.status) && !ResearchTask.QUEUED.equals(task.status)) continue;
            task.status = ResearchTask.QUEUED;
            task.attempts = 0;
            task.nextAttemptAt = 0;
            task.error = "";
            task.updatedAt = Instant.now().toString();
            changed++;
        }
        if (changed > 0) saveLocked();
        return changed;
    }

    public synchronized boolean delete(String id) {
        boolean removed = tasks.removeIf(task -> task.id.equals(id));
        if (removed) saveLocked();
        return removed;
    }

    // ---------------------------------------------------------------- 内部

    private ResearchTask findLocked(String query) {
        String key = IngredientRepository.norm(query);
        for (ResearchTask task : tasks) {
            if (IngredientRepository.norm(task.query).equals(key)) return task;
        }
        return null;
    }

    private ResearchTask byIdLocked(String id) {
        for (ResearchTask task : tasks) {
            if (task.id.equals(id)) return task;
        }
        return null;
    }

    private String newIdLocked() {
        while (true) {
            StringBuilder sb = new StringBuilder("RT-");
            for (int i = 0; i < 8; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            String id = sb.toString();
            if (byIdLocked(id) == null) return id;
        }
    }

    private void normalize(ResearchTask task) {
        if (task.context == null) task.context = "";
        if (task.source == null) task.source = "auto";
        if (task.status == null) task.status = ResearchTask.QUEUED;
        if (task.error == null) task.error = "";
        if (task.createdAt == null) task.createdAt = Instant.now().toString();
        if (task.updatedAt == null) task.updatedAt = task.createdAt;
        if (task.attempts < 0) task.attempts = 0;
    }

    private void saveLocked() {
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "research-tasks-", ".tmp");
            om.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), tasks);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[研究队列] 保存失败：" + e.getMessage());
        }
    }

    public static class EnqueueResult {
        public int created;
        public int queued;
        public int researching;
        public int failed;
        public int done;
        public int active;
    }
}
