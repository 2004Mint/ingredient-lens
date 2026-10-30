package com.cosme.service;

/**
 * 视觉模型的运行时配置。
 * 这个对象里带明文 Key，别写进日志、别直接返回给前端。
 */
public class VisionSettings {

    public String baseUrl;
    public String apiKey;
    public String model;
    public String updatedAt;

    public VisionSettings() {
    }

    public VisionSettings(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    public boolean usable() {
        return notBlank(baseUrl) && notBlank(apiKey) && notBlank(model);
    }

    public String maskedKey() {
        if (!notBlank(apiKey)) return "";
        if (apiKey.length() <= 8) return "****";
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    public static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
