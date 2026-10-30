package com.cosme.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 拍照识别（可选能力）。
 *
 * 没有配置视觉模型时，前端会自动引导用户改用「粘贴成分表文本」或选择示例，
 * 核心分析链路完全不依赖这个服务——这样做是为了保证演示不被网络和额度卡住。
 *
 * 配置方式（任一即可）：
 *   VISION_API_KEY + VISION_BASE_URL + VISION_MODEL
 *   或复用 OPENAI_API_KEY + OPENAI_BASE_URL
 * 兼容 OpenAI /chat/completions 协议（通义千问、智谱、硅基流动、DeepSeek-VL 等均可）
 */
@Service
public class VisionService {

    private static final String PROMPT =
            "这是一张化妆品包装背面的照片。请只做一件事：把成分表里的成分名称，按包装上的原始顺序提取出来，"
                    + "用中文顿号「、」分隔，输出成一行纯文本。不要翻译，不要解释，不要补全，不要输出任何多余文字。"
                    + "如果图片里没有成分表，只输出：未识别到成分表";

    private final RestTemplate rt;
    private final ObjectMapper om = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public VisionService(RestTemplateBuilder builder) {
        this.rt = builder.setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(60)).build();

        String key = firstNonEmpty(System.getenv("VISION_API_KEY"), System.getenv("OPENAI_API_KEY"));
        String base = firstNonEmpty(System.getenv("VISION_BASE_URL"), System.getenv("OPENAI_BASE_URL"));
        String m = firstNonEmpty(System.getenv("VISION_MODEL"), "qwen-vl-plus");

        this.apiKey = key;
        this.baseUrl = base == null ? null : base.replaceAll("/+$", "");
        this.model = m;
    }

    public boolean enabled() {
        return apiKey != null && !apiKey.isEmpty() && baseUrl != null && !baseUrl.isEmpty();
    }

    public String modelName() { return model; }

    /** 传入纯 base64（不含 data: 前缀），返回识别出的成分文本 */
    public String recognize(String base64, String mime) throws Exception {
        if (!enabled()) throw new IllegalStateException("未配置视觉模型");

        ObjectNode payload = om.createObjectNode();
        payload.put("model", model);
        payload.put("max_tokens", 800);
        ArrayNode messages = payload.putArray("messages");

        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", "你是化妆品标签识别助手，只输出成分名列表，用顿号分隔。");

        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");
        ObjectNode textPart = content.addObject();
        textPart.put("type", "text");
        textPart.put("text", PROMPT);
        ObjectNode imgPart = content.addObject();
        imgPart.put("type", "image_url");
        ObjectNode url = imgPart.putObject("image_url");
        url.put("url", "data:" + (mime == null || mime.isEmpty() ? "image/jpeg" : mime) + ";base64," + base64);

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", "Bearer " + apiKey);

        String resp = rt.postForObject(baseUrl + "/chat/completions",
                new HttpEntity<>(om.writeValueAsString(payload), h), String.class);
        JsonNode root = om.readTree(resp == null ? "{}" : resp);
        String out = root.path("choices").path(0).path("message").path("content").asText("");
        return out.trim();
    }

    private String firstNonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.trim().isEmpty()) return v.trim();
        return null;
    }
}
