package com.cosme.service;

/**
 * 统一的 AI 服务配置。
 *
 * defaultApi 是「总 API」；vision / research 是各功能的覆盖配置。
 * 覆盖配置里留空的字段按字段回退到总 API，满足「其他功能 API 为空就用总 API」。
 * 这个对象里带明文 Key，不能直接返回给前端。
 */
public class AiSettings {

    public VisionSettings defaultApi = new VisionSettings();
    public VisionSettings vision = new VisionSettings();
    public VisionSettings research = new VisionSettings();
    public String updatedAt;

    public VisionSettings effectiveVision() {
        return merge(vision, defaultApi);
    }

    public VisionSettings effectiveResearch() {
        return merge(research, defaultApi);
    }

    public static VisionSettings merge(VisionSettings own, VisionSettings fallback) {
        VisionSettings a = own == null ? new VisionSettings() : own;
        VisionSettings b = fallback == null ? new VisionSettings() : fallback;
        return new VisionSettings(
                first(a.baseUrl, b.baseUrl),
                first(a.apiKey, b.apiKey),
                first(a.model, b.model));
    }

    public void normalize() {
        if (defaultApi == null) defaultApi = new VisionSettings();
        if (vision == null) vision = new VisionSettings();
        if (research == null) research = new VisionSettings();
        normalize(defaultApi);
        normalize(vision);
        normalize(research);
    }

    private void normalize(VisionSettings s) {
        s.baseUrl = clean(s.baseUrl);
        s.apiKey = clean(s.apiKey);
        s.model = clean(s.model);
    }

    public static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    public static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
