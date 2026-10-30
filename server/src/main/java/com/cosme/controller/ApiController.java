package com.cosme.controller;

import com.cosme.AppMeta;
import com.cosme.service.AiConfigService;
import com.cosme.service.AnalyzeService;
import com.cosme.service.IngredientRepository;
import com.cosme.service.ResearchTaskStore;
import com.cosme.service.VisionException;
import com.cosme.service.VisionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final AnalyzeService analyzeService;
    private final VisionService visionService;
    private final IngredientRepository ingredientRepository;
    private final ResearchTaskStore researchTasks;
    private final AiConfigService aiConfig;

    public ApiController(AnalyzeService analyzeService, VisionService visionService,
                         IngredientRepository ingredientRepository,
                         ResearchTaskStore researchTasks, AiConfigService aiConfig) {
        this.analyzeService = analyzeService;
        this.visionService = visionService;
        this.ingredientRepository = ingredientRepository;
        this.researchTasks = researchTasks;
        this.aiConfig = aiConfig;
    }

    /** 前端启动时拉取能力开关，决定是否展示拍照入口 */
    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("visionEnabled", visionService.enabled());
        m.put("visionModel", visionService.modelName());
        m.put("librarySize", ingredientRepository.size());
        return m;
    }

    /** 产品版本：报告编号、关于页、页脚都从这里取，避免多处硬编码 */
    @GetMapping("/version")
    public Map<String, Object> version() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "成分镜");
        m.put("version", AppMeta.APP_VERSION);
        m.put("rulesVersion", AppMeta.RULES_VERSION);
        m.put("libraryVersion", AppMeta.LIBRARY_VERSION);
        m.put("librarySize", ingredientRepository.size());
        m.put("apiVersion", "v1");
        return m;
    }

    /** 成分库：前端成分库页面一次取全量 60 条，在浏览器里做搜索与筛选 */
    @GetMapping("/ingredients")
    public Map<String, Object> ingredients() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode n : ingredientRepository.active()) {
            List<String> aliases = new ArrayList<>();
            for (JsonNode a : n.path("a")) aliases.add(a.asText());

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", n.path("n").asText());
            m.put("aliases", aliases);
            m.put("role", n.path("r").asText());
            m.put("cat", n.path("c").asText());
            m.put("function", n.path("f").asText());
            m.put("description", n.path("ds").asText(""));
            m.put("researched", "ai".equals(n.path("sr").asText()));
            m.put("safety", n.path("s").asText("low"));
            m.put("comedo", n.path("cd").asInt());
            m.put("irrit", n.path("ir").asText("low"));
            m.put("preg", n.path("pg").asText("safe"));
            m.put("sensitive", n.path("sn").asText("safe"));
            m.put("limit", n.path("lm").asText(""));
            m.put("note", n.path("nt").asText(""));
            items.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("total", items.size());
        out.put("libraryVersion", AppMeta.LIBRARY_VERSION);
        out.put("updatedAt", AppMeta.LIBRARY_VERSION);
        out.put("source", "《化妆品安全技术规范》与常见 INCI 名称整理");
        out.put("storage", ingredientRepository.source());
        out.put("overridden", ingredientRepository.isOverridden());
        out.put("items", items);
        return out;
    }

    /** 示例配方：用于无网络、无 Key 时的完整演示 */
    @GetMapping("/samples")
    public List<Map<String, Object>> samples() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(sample("示例 · 美白精华液",
                "水、甘油、丙二醇、丁二醇、1,2-戊二醇、透明质酸钠、泛醇、卡波姆、氢氧化钠、苯氧乙醇、羟苯甲酯、烟酰胺、香精、柠檬烯",
                "主打烟酰胺美白，但烟酰胺排在很后面",
                Arrays.asList("概念添加", "烟酰胺第 12 位")));
        list.add(sample("示例 · 抗老紧致面霜",
                "水、甘油、异丙基棕榈酸酯、矿油、鲸蜡硬脂醇、聚二甲基硅氧烷、甘油硬脂酸酯、视黄醇、生育酚、苯氧乙醇、羟苯丙酯、香精、芳樟醇、柠檬烯",
                "含视黄醇，孕期需注意；含致痘性较高的润肤酯",
                Arrays.asList("孕期禁忌", "致痘性 4/5")));
        list.add(sample("示例 · 清爽控油洁面",
                "水、月桂醇硫酸酯钠、椰油酰胺丙基甜菜碱、甘油、氯化钠、柠檬酸、甲基异噻唑啉酮、香精",
                "SLS 体系，清洁力强但刺激偏大",
                Arrays.asList("刺激性表活", "MIT 防腐剂")));
        return list;
    }

    /** 核心接口：文本 -> 个性化解读 */
    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestBody Map<String, Object> body) {
        try {
            Object text = body.get("text");
            if (text == null || text.toString().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(err("成分表内容不能为空"));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> profile = body.get("profile") instanceof Map
                    ? (Map<String, Object>) body.get("profile") : Collections.emptyMap();

            Map<String, Object> report = analyzeService.analyze(text.toString(), profile);

            // 未收录 token 先进入持久化研究队列；worker 在后端异步研究，
            // 不再要求 AI 当场成功，也不会让失败/超限的 token 无声消失。
            Object unresolvedValue = report.get("unresolved");
            if (!(unresolvedValue instanceof List)) unresolvedValue = report.get("unknown");
            if (unresolvedValue instanceof List) {
                List<String> queries = new ArrayList<>();
                for (Object item : (List<?>) unresolvedValue) {
                    if (item != null && !item.toString().trim().isEmpty()) queries.add(item.toString().trim());
                }
                if (!queries.isEmpty()) {
                    ResearchTaskStore.EnqueueResult enqueued = researchTasks.enqueue(queries, text.toString());
                    report.put("researchEnabled", aiConfig.researchEnabled());
                    report.put("researchQueued", enqueued.queued);
                    report.put("researchRunning", enqueued.researching);
                    report.put("researchFailed", enqueued.failed);
                    report.put("researchDone", enqueued.done);
                    report.put("researchTasks", enqueued.active);
                }
            }

            return ResponseEntity.ok(report);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(err("分析失败：" + e.getMessage()));
        }
    }

    /** 拍照识别：需要配置视觉模型，未配置时返回明确提示而不是假装成功 */
    @PostMapping("/vision")
    public ResponseEntity<?> vision(@RequestBody Map<String, Object> body) {
        if (!visionService.enabled()) {
            return ResponseEntity.status(503).body(err(
                    "未配置视觉模型。请设置环境变量 VISION_API_KEY / VISION_BASE_URL / VISION_MODEL 后重启，"
                            + "或改用「粘贴成分表文本」与示例配方。"));
        }
        try {
            Object img = body.get("image");
            if (img == null) return ResponseEntity.badRequest().body(err("缺少图片数据"));
            String base64 = img.toString();
            int comma = base64.indexOf(',');
            if (base64.startsWith("data:") && comma > 0) base64 = base64.substring(comma + 1);
            String mime = body.get("mime") == null ? "image/jpeg" : body.get("mime").toString();

            VisionService.Recognition recognized = visionService.recognize(base64, mime);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("text", recognized.text);
            out.put("productName", recognized.productName);
            return ResponseEntity.ok(out);
        } catch (VisionException e) {
            // message 已经是给用户看的可行动提示，不要再套一层「识别失败」
            return ResponseEntity.status(502).body(err(e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(502).body(err("识别失败：" + e.getMessage()));
        }
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        m.put("service", "cosme-backend");
        m.put("version", AppMeta.APP_VERSION);
        m.put("librarySize", ingredientRepository.size());
        m.put("visionConfigured", visionService.enabled());
        return m;
    }

    private Map<String, Object> sample(String name, String text, String hint, List<String> tags) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("text", text);
        m.put("hint", hint);
        m.put("tags", tags);
        return m;
    }

    private Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }
}
