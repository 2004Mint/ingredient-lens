package com.cosme.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 配置的运行时入口。
 *
 * 解析顺序：总 API（defaultApi）→ 功能覆盖（vision / research）→ 环境变量兜底。
 * 管理台保存后立即重建内存配置，不需要重启。
 */
@Service
public class AiConfigService {

    private final AiSettingsStore store;
    private volatile AiSettings current;
    private volatile String source;

    public AiConfigService(AiSettingsStore store) {
        this.store = store;
        AiSettings saved = store.load();
        if (saved != null) {
            this.current = saved;
            this.source = "管理台";
        } else {
            this.current = fromEnv();
            this.source = hasAnyConfig(current) ? "环境变量" : "未配置";
        }
        current.normalize();

        System.out.println("[AI] 总 API=" + shortUrl(current.defaultApi.baseUrl)
                + " 视觉模型=" + visionEndpoint().model + "（" + visionSourceName() + "）"
                + " 研究模型=" + researchEndpoint().model + "（" + researchSourceName() + "）"
                + " 配置文件=" + store.filePath());
    }

    public VisionSettings visionEndpoint() {
        return current.effectiveVision();
    }

    public VisionSettings researchEndpoint() {
        return current.effectiveResearch();
    }

    public boolean visionEnabled() {
        return current.effectiveVision().usable();
    }

    public boolean researchEnabled() {
        return current.effectiveResearch().usable();
    }

    public String sourceName() {
        return source;
    }

    public String visionSourceName() {
        return blockSource(current.vision);
    }

    public String researchSourceName() {
        return blockSource(current.research);
    }

    public Path filePath() {
        return store.filePath();
    }

    /** 给控制台的总 API / 视觉 / 研究三块配置；Key 只返回掩码 */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("source", source);
        out.put("configFile", store.filePath().toString());
        out.put("updatedAt", current.updatedAt == null ? "" : current.updatedAt);
        out.put("defaultApi", block(current.defaultApi, current.defaultApi, false));
        out.put("vision", block(current.vision, current.effectiveVision(), true));
        out.put("research", block(current.research, current.effectiveResearch(), true));
        return out;
    }

    /** 给 /api/config、/api/health 和旧视觉配置接口使用的精简快照 */
    public Map<String, Object> visionSnapshot() {
        VisionSettings cfg = current.effectiveVision();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", cfg.usable());
        m.put("baseUrl", n(cfg.baseUrl));
        m.put("model", n(cfg.model));
        m.put("keyMasked", cfg.maskedKey());
        m.put("source", visionSourceName());
        m.put("configFile", store.filePath().toString());
        return m;
    }

    public Map<String, Object> researchSnapshot() {
        VisionSettings cfg = current.effectiveResearch();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", cfg.usable());
        m.put("baseUrl", n(cfg.baseUrl));
        m.put("model", n(cfg.model));
        m.put("keyMasked", cfg.maskedKey());
        m.put("source", researchSourceName());
        m.put("configFile", store.filePath().toString());
        return m;
    }

    /**
     * 保存三块配置。
     *
     * useDefault=true 表示清空该功能覆盖，直接走总 API；
     * 功能块 Key 留空表示保留该块原有 Key（不把 Key 打到前端再传回来）。
     */
    public synchronized void apply(Map<String, Object> body) throws IOException {
        if (body == null) throw new IllegalArgumentException("缺少配置内容");

        Map<String, Object> defMap = asMap(body.containsKey("defaultApi") ? body.get("defaultApi") : body.get("default"));
        Map<String, Object> visMap = asMap(body.get("vision"));
        Map<String, Object> resMap = asMap(body.get("research"));

        VisionSettings nextDefault = mergeInput(defMap, current.defaultApi, "总 API");
        VisionSettings nextVision = bool(visMap.get("useDefault"))
                ? new VisionSettings()
                : mergeInput(visMap, current.vision, "视觉 API");
        VisionSettings nextResearch = bool(resMap.get("useDefault"))
                ? new VisionSettings()
                : mergeInput(resMap, current.research, "研究 API");

        AiSettings next = new AiSettings();
        next.defaultApi = nextDefault;
        next.vision = nextVision;
        next.research = nextResearch;
        next.normalize();

        store.save(next);
        this.current = next;
        this.source = "管理台";
        System.out.println("[AI] 管理台已更新：总 API=" + shortUrl(nextDefault.baseUrl)
                + " 视觉模型=" + next.effectiveVision().model
                + " 研究模型=" + next.effectiveResearch().model);
    }

    /** 兼容旧版 PUT /api/admin/vision：只改视觉覆盖块 */
    public synchronized void updateVision(String baseInput, String keyInput, String modelInput) throws IOException {
        VisionSettings cur = current.vision;
        String base = clean(baseInput);
        if (!base.isEmpty() && !base.startsWith("http://") && !base.startsWith("https://")) {
            throw new IllegalArgumentException("服务地址必须以 http:// 或 https:// 开头");
        }
        String key = clean(keyInput);
        if (key.isEmpty()) {
            if (!base.isEmpty() && !base.equals(n(cur.baseUrl)) && !n(cur.apiKey).isEmpty()) {
                throw new IllegalArgumentException("更换服务地址时必须同时填写该地址的 API Key");
            }
            key = n(cur.apiKey);
        }
        String model = clean(modelInput);
        if (model.isEmpty()) model = n(cur.model);

        current.vision = new VisionSettings(base, key, model);
        current.normalize();
        store.save(current);
        this.source = "管理台";
    }

    // ---------------------------------------------------------------- 内部

    private AiSettings fromEnv() {
        AiSettings s = new AiSettings();
        s.defaultApi = new VisionSettings(
                first(env("AI_BASE_URL"), env("OPENAI_BASE_URL")),
                first(env("AI_API_KEY"), env("OPENAI_API_KEY")),
                first(env("AI_MODEL"), env("OPENAI_MODEL")));

        s.vision = new VisionSettings(
                env("VISION_BASE_URL"),
                env("VISION_API_KEY"),
                first(env("VISION_MODEL"), "qwen-vl-plus"));

        s.research = new VisionSettings(
                env("RESEARCH_BASE_URL"),
                env("RESEARCH_API_KEY"),
                env("RESEARCH_MODEL"));
        return s;
    }

    private VisionSettings mergeInput(Map<String, Object> map, VisionSettings currentBlock, String label) {
        VisionSettings cur = currentBlock == null ? new VisionSettings() : currentBlock;
        String base = clean(str(map.get("baseUrl")));
        if (!base.isEmpty() && !base.startsWith("http://") && !base.startsWith("https://")) {
            throw new IllegalArgumentException(label + " 的服务地址必须以 http:// 或 https:// 开头");
        }
        String key = clean(str(map.get("apiKey")));
        String model = clean(str(map.get("model")));

        if (key.isEmpty()) {
            if (!base.isEmpty() && !base.equals(n(cur.baseUrl)) && !n(cur.apiKey).isEmpty()) {
                throw new IllegalArgumentException(label + " 更换服务地址时必须填写该地址的 API Key");
            }
            key = n(cur.apiKey);
        }
        return new VisionSettings(base, key, model);
    }

    private Map<String, Object> block(VisionSettings own, VisionSettings effective, boolean allowDefault) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("baseUrl", n(own.baseUrl));
        b.put("model", n(own.model));
        b.put("keyMasked", own.maskedKey());

        Map<String, Object> e = new LinkedHashMap<>();
        e.put("baseUrl", n(effective.baseUrl));
        e.put("model", n(effective.model));
        e.put("keyMasked", effective.maskedKey());
        e.put("enabled", effective.usable());
        b.put("effective", e);
        if (allowDefault) {
            b.put("usingDefault", n(own.baseUrl).isEmpty() && n(own.apiKey).isEmpty() && n(own.model).isEmpty());
        }
        return b;
    }

    private String blockSource(VisionSettings own) {
        if (own == null || (n(own.baseUrl).isEmpty() && n(own.apiKey).isEmpty() && n(own.model).isEmpty())) {
            return source + " · 总 API";
        }
        if ("环境变量".equals(source)) return "环境变量 · 功能覆盖";
        return "管理台 · 功能覆盖";
    }

    private boolean hasAnyConfig(AiSettings s) {
        if (s == null) return false;
        return any(s.defaultApi) || any(s.vision) || any(s.research);
    }

    private boolean any(VisionSettings s) {
        return s != null && (!n(s.baseUrl).isEmpty() || !n(s.apiKey).isEmpty() || !n(s.model).isEmpty());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    private boolean bool(Object value) {
        return value instanceof Boolean ? (Boolean) value : "true".equals(String.valueOf(value));
    }

    private String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }

    private String first(String... values) {
        return AiSettings.first(values);
    }

    private String n(String value) {
        return value == null ? "" : value.trim();
    }

    private String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("/+$", "");
    }

    private String shortUrl(String url) {
        String u = n(url);
        return u.length() > 48 ? u.substring(0, 48) + "…" : u;
    }
}
