package com.cosme.controller;

import com.cosme.service.AdminAuthService;
import com.cosme.service.ResearchCommitService;
import com.cosme.service.ResearchService;
import com.cosme.service.ResearchTask;
import com.cosme.service.ResearchTaskStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;

/** 管理台 · 未收录成分的研究任务队列 */
@RestController
@RequestMapping("/api/admin/research/tasks")
public class AdminResearchTaskController {

    private final ResearchTaskStore store;
    private final ResearchService research;
    private final ResearchCommitService commit;
    private final AdminAuthService auth;

    public AdminResearchTaskController(ResearchTaskStore store, ResearchService research,
                                       ResearchCommitService commit, AdminAuthService auth) {
        this.store = store;
        this.research = research;
        this.commit = commit;
        this.auth = auth;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        if (!authorized(authorization)) return unauthorized();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("counts", store.counts());
        out.put("researchEnabled", research.enabled());
        out.put("items", store.list(status, limit));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/retry")
    public ResponseEntity<?> retry(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!authorized(authorization)) return unauthorized();
        List<String> ids = new ArrayList<>();
        if (body != null && body.get("ids") instanceof List) {
            for (Object item : (List<?>) body.get("ids")) {
                if (item != null) ids.add(item.toString());
            }
        }
        boolean allFailed = body != null && Boolean.parseBoolean(String.valueOf(body.get("allFailed")));
        if (ids.isEmpty() && !allFailed) return bad("请选择要重试的任务");
        int changed = store.retry(ids, allFailed);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("retried", changed);
        out.put("counts", store.counts());
        return ResponseEntity.ok(out);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String id) {
        if (!authorized(authorization)) return unauthorized();
        boolean removed = store.delete(id);
        if (!removed) return ResponseEntity.status(404).body(err("任务不存在"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("counts", store.counts());
        return ResponseEntity.ok(out);
    }

    /**
     * 人工通过：把待复核草稿写入成分库，任务转 done。
     * 正常流程里 Agent 会自动收录（不经这一步），这里留给历史遗留的 review 任务与人工兜底。
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String id) {
        if (!authorized(authorization)) return unauthorized();
        ResearchTask task = store.find(id);
        if (task == null) return ResponseEntity.status(404).body(err("任务不存在"));
        if (!ResearchTask.REVIEW.equals(task.status)) return bad("只有待复核的任务可以确认");
        if (task.draft == null) return bad("这条任务没有可写入的草稿");

        try {
            String name = commit.commit(task.query, task.draft, task.confidence,
                    task.model, task.evidence);
            store.complete(id, name, task.confidence, task.model, task.evidence);
        } catch (IllegalArgumentException conflict) {
            return ResponseEntity.status(409).body(err(conflict.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(err("写入成分库失败：" + e.getMessage()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("counts", store.counts());
        out.put("items", store.list("review", 100));
        return ResponseEntity.ok(out);
    }

    /** 人工拒绝：保留轨迹与原因，方便以后重跑。 */
    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!authorized(authorization)) return unauthorized();
        String why = body == null || body.get("reason") == null ? "" : String.valueOf(body.get("reason"));
        if (!store.reject(id, why)) return ResponseEntity.status(404).body(err("任务不存在"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("counts", store.counts());
        out.put("items", store.list("review", 100));
        return ResponseEntity.ok(out);
    }

    private boolean authorized(String authorization) {
        String token = token(authorization);
        return token != null && auth.isValid(token);
    }

    private String token(String authorization) {
        if (authorization == null) return null;
        String value = authorization.trim();
        if (value.startsWith("Bearer ")) value = value.substring("Bearer ".length()).trim();
        return value.isEmpty() ? null : value;
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(err("登录已失效，请重新输入管理密码"));
    }

    private ResponseEntity<?> bad(String message) {
        return ResponseEntity.badRequest().body(err(message));
    }

    private Map<String, Object> err(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error", message);
        return Collections.unmodifiableMap(map);
    }
}
