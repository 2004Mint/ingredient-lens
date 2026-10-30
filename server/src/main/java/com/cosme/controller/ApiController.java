package com.cosme.controller;

import com.cosme.service.AnalyzeService;
import com.cosme.service.VisionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final AnalyzeService analyzeService;
    private final VisionService visionService;

    public ApiController(AnalyzeService analyzeService, VisionService visionService) {
        this.analyzeService = analyzeService;
        this.visionService = visionService;
    }

    /** 前端启动时拉取能力开关，决定是否展示拍照入口 */
    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("visionEnabled", visionService.enabled());
        m.put("visionModel", visionService.modelName());
        return m;
    }

    /** 示例配方：用于无网络、无 Key 时的完整演示 */
    @GetMapping("/samples")
    public List<Map<String, Object>> samples() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(sample("示例 · 美白精华液",
                "水、甘油、丙二醇、丁二醇、1,2-戊二醇、透明质酸钠、泛醇、卡波姆、氢氧化钠、苯氧乙醇、羟苯甲酯、烟酰胺、香精、柠檬烯",
                "主打烟酰胺美白，但烟酰胺排在很后面"));
        list.add(sample("示例 · 抗老紧致面霜",
                "水、甘油、异丙基棕榈酸酯、矿油、鲸蜡硬脂醇、聚二甲基硅氧烷、甘油硬脂酸酯、视黄醇、生育酚、苯氧乙醇、羟苯丙酯、香精、芳樟醇、柠檬烯",
                "含视黄醇，孕期需注意；含致痘性较高的润肤酯"));
        list.add(sample("示例 · 清爽控油洁面",
                "水、月桂醇硫酸酯钠、椰油酰胺丙基甜菜碱、甘油、氯化钠、柠檬酸、甲基异噻唑啉酮、香精",
                "SLS 体系，清洁力强但刺激偏大"));
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
            return ResponseEntity.ok(analyzeService.analyze(text.toString(), profile));
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

            String text = visionService.recognize(base64, mime);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("text", text);
            return ResponseEntity.ok(out);
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
        return m;
    }

    private Map<String, Object> sample(String name, String text, String hint) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("text", text);
        m.put("hint", hint);
        return m;
    }

    private Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }
}
