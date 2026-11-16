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
    private final Path dir = DataPaths.dir();
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
                continue;
            }
            // 之前失败过、但还没到重试上限：再分析一次时自动放回队列，不必去管理台点重试
            if (ResearchTask.FAILED.equals(existing.status)
                    && existing.attempts < 3
                    && existing.nextAttemptAt <= System.currentTimeMillis()) {
                existing.status = ResearchTask.QUEUED;
                existing.nextAttemptAt = 0;
                existing.updatedAt = Instant.now().toString();
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
        int queued = 0, researching = 0, failed = 0, done = 0, review = 0;
        for (ResearchTask task : tasks) {
            if (ResearchTask.QUEUED.equals(task.status)) queued++;
            else if (ResearchTask.RESEARCHING.equals(task.status)) researching++;
            else if (ResearchTask.FAILED.equals(task.status)) failed++;
            else if (ResearchTask.REVIEW.equals(task.status)) review++;
            else if (ResearchTask.DONE.equals(task.status)) done++;
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("queued", queued);
        out.put("researching", researching);
        out.put("review", review);
        out.put("failed", failed);
        out.put("done", done);
        out.put("active", queued + researching + review + failed);
        out.put("total", tasks.size());
        return out;
    }

    /** 认领超过这个时间还没动静的任务：多半是进程被杀或上游挂住。 */
    private static final long STALE_MS = 3 * 60_000L;

    /** worker 认领一条：优先排队，其次到时间重试的失败任务，最后是卡住的 researching。 */
    public synchronized ResearchTask claimNext() {
        long now = System.currentTimeMillis();
        ResearchTask best = null;
        for (ResearchTask task : tasks) {
            boolean stale = ResearchTask.RESEARCHING.equals(task.status) && isStale(task, now);
            boolean eligible = ResearchTask.QUEUED.equals(task.status)
                    || stale
                    || (ResearchTask.FAILED.equals(task.status)
                    && task.attempts < 3
                    && task.nextAttemptAt <= now);
            if (!eligible) continue;
            if (best == null || String.valueOf(task.createdAt).compareTo(String.valueOf(best.createdAt)) < 0) {
                best = task;
            }
        }
        if (best == null) return null;
        if (ResearchTask.RESEARCHING.equals(best.status)) {
            System.out.println("[研究队列] 回收卡住的任务：" + best.query);
        }
        best.status = ResearchTask.RESEARCHING;
        best.updatedAt = Instant.now().toString();
        saveLocked();
        return best;
    }

    /** 启动时把长时间停在 researching 的任务放回队列，返回处理条数。 */
    public synchronized int requeueStaleResearching(long olderThanMs) {
        long now = System.currentTimeMillis();
        int changed = 0;
        for (ResearchTask task : tasks) {
            if (!ResearchTask.RESEARCHING.equals(task.status)) continue;
            if (!isStale(task, now, olderThanMs)) continue;
            task.status = ResearchTask.QUEUED;
            task.updatedAt = Instant.now().toString();
            changed++;
        }
        if (changed > 0) saveLocked();
        return changed;
    }

    private boolean isStale(ResearchTask task, long now) {
        return isStale(task, now, STALE_MS);
    }

    private boolean isStale(ResearchTask task, long now, long olderThanMs) {
        try {
            Instant at = Instant.parse(String.valueOf(task.updatedAt));
            return now - at.toEpochMilli() > olderThanMs;
        } catch (Exception e) {
            return true;
        }
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

    /** Agent 跑完但需要人工确认：草稿与轨迹都留住，先不写库。 */
    public synchronized void markReview(String id, IngredientAdminService.Draft draft, Double confidence,
                                        String model, String evidence, String reason,
                                        List<String> plan, List<ResearchAgent.Step> trace) {
        ResearchTask task = byIdLocked(id);
        if (task == null) return;
        task.status = ResearchTask.REVIEW;
        task.draft = draft;
        task.confidence = confidence;
        task.model = model;
        task.evidence = evidence == null ? "" : evidence;
        task.reason = reason == null ? "" : reason;
        task.plan = plan;
        task.trace = trace;
        task.error = "";
        task.updatedAt = Instant.now().toString();
        saveLocked();
    }

    /** 只记录计划与轨迹，不改状态：自动收录后仍能在管理台回看。 */
    public synchronized void recordTrace(String id, List<String> plan, List<ResearchAgent.Step> trace, String note) {
        ResearchTask task = byIdLocked(id);
        if (task == null) return;
        task.plan = plan;
        task.trace = trace;
        if (note != null && !note.isEmpty()) task.reason = note;
        task.updatedAt = Instant.now().toString();
        saveLocked();
    }

    /** 人工通过：标记完成，草稿由调用方写入成分库。 */
    public synchronized ResearchTask approve(String id) {
        ResearchTask task = byIdLocked(id);
        if (task == null) return null;
        task.status = ResearchTask.DONE;
        task.resultName = task.draft == null ? task.query : task.draft.name;
        task.error = "";
        task.reason = "";
        task.updatedAt = Instant.now().toString();
        saveLocked();
        return task;
    }

    /** 人工拒绝：保留轨迹与原因，方便以后重跑。 */
    public synchronized boolean reject(String id, String why) {
        ResearchTask task = byIdLocked(id);
        if (task == null) return false;
        task.status = ResearchTask.FAILED;
        task.reason = why == null ? "" : why;
        task.error = "人工拒绝" + (why == null || why.isEmpty() ? "" : "：" + why);
        task.nextAttemptAt = 0;
        task.updatedAt = Instant.now().toString();
        saveLocked();
        return true;
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
            if (!ResearchTask.FAILED.equals(task.status)
                    && !ResearchTask.QUEUED.equals(task.status)
                    && !ResearchTask.REVIEW.equals(task.status)) continue;
            task.status = ResearchTask.QUEUED;
            task.attempts = 0;
            task.nextAttemptAt = 0;
            task.error = "";
            task.reason = "";
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

    public synchronized ResearchTask find(String id) {
        return byIdLocked(id);
    }

    public synchronized ResearchTask findByQuery(String query) {
        return findLocked(query);
    }

    /** 这批未收录成分里，失败任务的第一条原因：给报告页显示，而不是只说「失败了」。 */
    public synchronized String failureReason(List<String> queries) {
        if (queries == null) return "";
        for (String query : queries) {
            ResearchTask task = findLocked(query);
            if (task == null || !ResearchTask.FAILED.equals(task.status)) continue;
            String error = task.error == null ? "" : task.error.trim();
            if (error.isEmpty()) continue;
            // 只取第一句：完整错误里往往带着「请换一个模型」这类给管理员看的建议
            int stop = error.indexOf('。');
            String brief = stop > 0 ? error.substring(0, stop + 1) : error;
            return brief.length() > 80 ? brief.substring(0, 80) + "…" : brief;
        }
        return "";
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
