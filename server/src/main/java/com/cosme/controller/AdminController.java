package com.cosme.controller;

import com.cosme.AppMeta;
import com.cosme.service.AdminAuthService;
import com.cosme.service.AiConfigService;
import com.cosme.service.FeedbackStore;
import com.cosme.service.IngredientRepository;
import com.cosme.service.ReportStore;
import com.cosme.service.ResearchException;
import com.cosme.service.ResearchService;
import com.cosme.service.ResearchTaskStore;
import com.cosme.service.VisionException;
import com.cosme.service.VisionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.*;

/**
 * 管理台接口。
 *
 * 除了登录，全部要求 Bearer 令牌；令牌只存在内存里，后端重启即失效。
 * 这里能改视觉服务的地址与 Key，因此不做「无密码也能进」的便利模式。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final VisionService visionService;
    private final AdminAuthService auth;
    private final IngredientRepository ingredients;
    private final ReportStore reports;
    private final FeedbackStore feedback;
    private final AiConfigService aiConfig;
    private final ResearchService researchService;
    private final ResearchTaskStore researchTasks;

    public AdminController(VisionService visionService, AdminAuthService auth,
                           IngredientRepository ingredients, ReportStore reports,
                           FeedbackStore feedback, AiConfigService aiConfig,
                           ResearchService researchService, ResearchTaskStore researchTasks) {
        this.visionService = visionService;
        this.auth = auth;
        this.ingredients = ingredients;
        this.reports = reports;
        this.feedback = feedback;
        this.aiConfig = aiConfig;
        this.researchService = researchService;
        this.researchTasks = researchTasks;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody(required = false) Map<String, String> body) {
        String password = body == null ? null : body.get("password");
        String token = auth.login(password);
        if (token == null) {
            // 稍微慢一点，降低本地暴力猜密码的收益
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return ResponseEntity.status(401).body(err("密码不正确"));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("token", token);
        out.put("expiresIn", auth.ttlSeconds());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/vision")
    public ResponseEntity<?> getVision(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!authorized(authorization)) return unauthorized();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("settings", visionService.snapshot());
        out.put("passwordSource", auth.passwordSource());
        return ResponseEntity.ok(out);
    }

    @PutMapping("/vision")
    public ResponseEntity<?> updateVision(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, String> body) {
        if (!authorized(authorization)) return unauthorized();
        if (body == null) return bad("缺少配置内容");

        try {
            visionService.apply(body.get("baseUrl"), body.get("apiKey"), body.get("model"));
            return ResponseEntity.ok(settingsResponse());
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(500).body(err("保存配置失败：" + e.getMessage()));
        }
    }

    @PostMapping("/vision/models")
    public ResponseEntity<?> listModels(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, String> body) {
        if (!authorized(authorization)) return unauthorized();

        String baseUrl = body == null ? null : body.get("baseUrl");
        String apiKey = body == null ? null : body.get("apiKey");
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("models", visionService.listModels(baseUrl, apiKey));
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (VisionException e) {
            return ResponseEntity.status(502).body(err(e.getMessage()));
        }
    }

    @PostMapping("/vision/test")
    public ResponseEntity<?> testVision(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, String> body) {
        if (!authorized(authorization)) return unauthorized();
        if (body == null) return bad("缺少测试参数");

        String image = body.get("image");
        if (image == null || image.trim().isEmpty()) return bad("缺少测试图片");
        int comma = image.indexOf(',');
        if (image.startsWith("data:") && comma > 0) {
            image = image.substring(comma + 1);
        }
        if (image.length() > 4_000_000) return bad("测试图片太大");

        String mime = body.get("mime") == null ? "image/jpeg" : body.get("mime");
        try {
            String reply = visionService.test(
                    body.get("baseUrl"), body.get("apiKey"), body.get("model"), image, mime);
            String testedModel = body.get("model");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("reply", reply);
            out.put("model", testedModel != null && !testedModel.trim().isEmpty()
                    ? testedModel.trim() : visionService.modelName());
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (VisionException e) {
            return ResponseEntity.status(502).body(err(e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = token(authorization);
        if (token != null) auth.logout(token);
        return ResponseEntity.ok(Collections.singletonMap("ok", true));
    }

    /** 控制台概览：一次拿到成分库、分享、反馈、视觉配置与版本，避免前端拼五个请求 */
    @GetMapping("/stats")
    public ResponseEntity<?> stats(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!authorized(authorization)) return unauthorized();

        Map<String, Object> library = new LinkedHashMap<>();
        library.put("size", ingredients.size());
        library.put("total", ingredients.totalCount());
        library.put("counts", ingredients.statusCounts());
        library.put("source", ingredients.source());
        library.put("overridden", ingredients.isOverridden());
        library.put("file", ingredients.overrideFile().toString());

        Map<String, Object> reportStats = new LinkedHashMap<>();
        reportStats.put("count", reports.count());
        reportStats.put("directory", reports.directory().toString());

        Map<String, Object> feedbackStats = new LinkedHashMap<>();
        feedbackStats.put("count", feedback.count());
        feedbackStats.put("file", feedback.filePath().toString());

        Map<String, Object> server = new LinkedHashMap<>();
        server.put("name", "成分镜");
        server.put("version", AppMeta.APP_VERSION);
        server.put("rulesVersion", AppMeta.RULES_VERSION);
        server.put("libraryVersion", AppMeta.LIBRARY_VERSION);
        server.put("apiVersion", "v1");
        server.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);

        Map<String, Object> admin = new LinkedHashMap<>();
        admin.put("passwordSource", auth.passwordSource());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("library", library);
        out.put("reports", reportStats);
        out.put("feedback", feedbackStats);
        out.put("vision", visionService.snapshot());
        out.put("research", aiConfig.researchSnapshot());
        out.put("researchTasks", researchTasks.counts());
        out.put("ai", aiConfig.snapshot());
        out.put("server", server);
        out.put("admin", admin);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/reports")
    public ResponseEntity<?> listReports(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        if (!authorized(authorization)) return unauthorized();

        int size = Math.max(1, Math.min(limit, 200));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("total", reports.count());
        out.put("items", reports.list(size));
        return ResponseEntity.ok(out);
    }

    @DeleteMapping("/reports/{id}")
    public ResponseEntity<?> deleteReport(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") String id) {
        if (!authorized(authorization)) return unauthorized();
        try {
            if (!reports.delete(id)) {
                return ResponseEntity.status(404).body(err("分享报告不存在或已被删除"));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("id", id);
            out.put("total", reports.count());
            return ResponseEntity.ok(out);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(err("删除分享报告失败：" + e.getMessage()));
        }
    }

    @GetMapping("/feedback")
    public ResponseEntity<?> listFeedback(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        if (!authorized(authorization)) return unauthorized();

        int size = Math.max(1, Math.min(limit, 200));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("total", feedback.count());
        out.put("items", feedback.list(size));
        return ResponseEntity.ok(out);
    }

    /** 总 API / 视觉 API / 研究 API 的统一配置 */
    @GetMapping("/ai")
    public ResponseEntity<?> getAi(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!authorized(authorization)) return unauthorized();
        Map<String, Object> out = aiConfig.snapshot();
        out.put("passwordSource", auth.passwordSource());
        return ResponseEntity.ok(out);
    }

    @PutMapping("/ai")
    public ResponseEntity<?> updateAi(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!authorized(authorization)) return unauthorized();
        try {
            aiConfig.apply(body);
            Map<String, Object> out = aiConfig.snapshot();
            out.put("ok", true);
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(500).body(err("保存 AI 配置失败：" + e.getMessage()));
        }
    }

    @PostMapping("/ai/models")
    public ResponseEntity<?> listAiModels(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, String> body) {
        if (!authorized(authorization)) return unauthorized();
        String baseUrl = body == null ? null : body.get("baseUrl");
        String apiKey = body == null ? null : body.get("apiKey");
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("models", visionService.listModels(baseUrl, apiKey));
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (VisionException e) {
            return ResponseEntity.status(502).body(err(e.getMessage()));
        }
    }

    @PostMapping("/ai/research/test")
    public ResponseEntity<?> testResearch(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, String> body) {
        if (!authorized(authorization)) return unauthorized();
        if (!researchService.enabled()) {
            return bad("还没有配置研究 API。请先填写总 API 或研究 API 的模型并保存。");
        }
        String name = body == null ? null : body.get("name");
        if (name == null || name.trim().isEmpty()) return bad("请填写要测试的成分名");
        try {
            return ResponseEntity.ok(researchService.test(name.trim()));
        } catch (ResearchException e) {
            return ResponseEntity.status(502).body(err(e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, Object> settingsResponse() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("settings", visionService.snapshot());
        return out;
    }

    private boolean authorized(String authorization) {
        String token = token(authorization);
        return token != null && auth.isValid(token);
    }

    private String token(String authorization) {
        if (authorization == null) return null;
        String value = authorization.trim();
        if (value.startsWith("Bearer ")) {
            value = value.substring("Bearer ".length()).trim();
        }
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
        return m;
    }
}
