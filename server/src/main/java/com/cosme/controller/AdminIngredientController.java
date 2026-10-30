package com.cosme.controller;

import com.cosme.service.AdminAuthService;
import com.cosme.service.IngredientAdminService;
import com.cosme.service.IngredientRepository;
import com.cosme.service.ResearchException;
import com.cosme.service.ResearchService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;

/** 管理台 · 成分数据增删改；所有写操作都要求管理令牌。 */
@RestController
@RequestMapping("/api/admin/ingredients")
public class AdminIngredientController {

    private final IngredientAdminService service;
    private final IngredientRepository repository;
    private final AdminAuthService auth;
    private final ResearchService research;

    public AdminIngredientController(IngredientAdminService service, IngredientRepository repository,
                                     AdminAuthService auth, ResearchService research) {
        this.service = service;
        this.repository = repository;
        this.auth = auth;
        this.research = research;
    }

    /** 管理台成分列表：包含待研究 / 已确认 / 已拒绝 */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!authorized(authorization)) return unauthorized();

        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode n : repository.all()) items.add(service.toView(n));
        items.sort((a, b) -> Long.compare(createdValue(b), createdValue(a)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("total", items.size());
        out.put("active", repository.size());
        out.put("counts", repository.statusCounts());
        out.put("storage", repository.source());
        out.put("overridden", repository.isOverridden());
        out.put("researchEnabled", research.enabled());
        out.put("items", items);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/status")
    public ResponseEntity<?> status(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!authorized(authorization)) return unauthorized();
        if (body == null) return bad("缺少审核内容");
        try {
            service.setStatus(stringList(body.get("names")), String.valueOf(body.get("status")));
            return ResponseEntity.ok(done(null));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(500).body(err("保存成分状态失败：" + e.getMessage()));
        }
    }

    @PostMapping("/research")
    public ResponseEntity<?> research(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!authorized(authorization)) return unauthorized();
        if (body == null) return bad("缺少要研究的成分");
        if (!research.enabled()) return bad("还没有配置研究 API。请到「API 与模型」配置总 API 或研究 API。");

        try {
            ResearchService.BatchResult result = research.researchExisting(stringList(body.get("names")));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("researched", result.researched);
            out.put("failed", result.failed);
            out.put("errors", result.errors);
            out.put("counts", repository.statusCounts());
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (ResearchException e) {
            return ResponseEntity.status(502).body(err(e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) IngredientAdminService.Draft draft) {
        if (!authorized(authorization)) return unauthorized();
        try {
            JsonNode node = service.create(draft);
            return ResponseEntity.ok(done(node));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(500).body(err("保存成分库失败：" + e.getMessage()));
        }
    }

    @PutMapping
    public ResponseEntity<?> update(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam("name") String name,
            @RequestBody(required = false) IngredientAdminService.Draft draft) {
        if (!authorized(authorization)) return unauthorized();
        try {
            JsonNode node = service.update(name, draft);
            return ResponseEntity.ok(done(node));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(500).body(err("保存成分库失败：" + e.getMessage()));
        }
    }

    @DeleteMapping
    public ResponseEntity<?> delete(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam("name") String name) {
        if (!authorized(authorization)) return unauthorized();
        try {
            service.delete(name);
            return ResponseEntity.ok(done(null));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(500).body(err("保存成分库失败：" + e.getMessage()));
        }
    }

    @PostMapping("/reset")
    public ResponseEntity<?> reset(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!authorized(authorization)) return unauthorized();
        try {
            service.reset();
            return ResponseEntity.ok(done(null));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(err("恢复内置库失败：" + e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, Object> done(JsonNode ingredient) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("ingredient", ingredient);
        out.put("total", repository.totalCount());
        out.put("active", repository.size());
        out.put("counts", repository.statusCounts());
        out.put("storage", repository.source());
        out.put("overridden", repository.isOverridden());
        out.put("researchEnabled", research.enabled());
        return out;
    }

    private List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item != null && !item.toString().trim().isEmpty()) out.add(item.toString().trim());
            }
        } else if (value != null && !value.toString().trim().isEmpty()) {
            out.add(value.toString().trim());
        }
        return out;
    }

    private long createdValue(Map<String, Object> item) {
        try {
            return java.time.OffsetDateTime.parse(String.valueOf(item.get("createdAt"))).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
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
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return Collections.unmodifiableMap(m);
    }
}
