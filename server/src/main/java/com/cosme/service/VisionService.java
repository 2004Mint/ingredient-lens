package com.cosme.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

/**
 * 拍照识别（可选能力）。
 *
 * 配置优先级：管理台保存的 data/vision-settings.json > 环境变量。
 * 管理台保存后立即热更新，不需要重启后端。
 */
@Service
public class VisionService {

    private static final String PROMPT =
            "这是一张化妆品包装照片。请提取两件事，并严格输出一个 JSON 对象，不要 Markdown、不要解释：\n"
                    + "{\"productName\":\"包装上清晰可见的品牌+产品名；看不到就填空字符串；不要猜\","
                    + "\"ingredients\":\"成分表里的成分名称，按包装原始顺序，用中文顿号分隔；不翻译、不补全\"}\n"
                    + "productName 最多 40 字，只填能直接读到的名字；不要把成分表文字、广告语或使用方法当成产品名。"
                    + "如果图片里没有成分表，ingredients 填：未识别到成分表。";

    private static final String TEST_PROMPT =
            "这是一张化妆品成分表照片。请只输出图片里的成分名称，用顿号分隔，不要解释。"
                    + "如果看不清，只输出：看不清";

    private final RestTemplate rt;
    private final RestTemplate fastRt;
    private final ObjectMapper om = new ObjectMapper();
    private final AiConfigService aiConfig;

    public VisionService(RestTemplateBuilder builder, AiConfigService aiConfig) {
        this.rt = builder.setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(60)).build();
        this.fastRt = builder.setConnectTimeout(Duration.ofSeconds(8))
                .setReadTimeout(Duration.ofSeconds(15)).build();
        this.aiConfig = aiConfig;
    }

    public boolean enabled() {
        return aiConfig.visionEnabled();
    }

    public String modelName() {
        return aiConfig.visionEndpoint().model;
    }

    public String sourceName() {
        return aiConfig.visionSourceName();
    }

    public Map<String, Object> snapshot() {
        return aiConfig.visionSnapshot();
    }

    /** 旧版接口兼容：只更新视觉覆盖块；新控制台走 /api/admin/ai */
    public synchronized void apply(String baseInput, String keyInput, String modelInput) throws IOException {
        aiConfig.updateVision(baseInput, keyInput, modelInput);
        System.out.println("[视觉] 已更新视觉覆盖块，model=" + aiConfig.visionEndpoint().model);
    }

    /** 传入纯 base64（不含 data: 前缀），返回识别出的成分文本与产品名 */
    public Recognition recognize(String base64, String mime) {
        VisionSettings cfg = aiConfig.visionEndpoint();
        if (!cfg.usable()) {
            throw new VisionException("还没有配置视觉模型，请到管理控制台填写总 API 或视觉 API。");
        }
        String raw = chat(cfg, base64, mime, PROMPT);
        return parseRecognition(raw);
    }

    /** 管理台「测试连接」：用一张小图真实调用一次，返回模型原文 */
    public String test(String baseInput, String keyInput, String modelInput, String base64, String mime) {
        VisionSettings cfg = resolve(baseInput, keyInput, modelInput);
        return chat(cfg, base64, mime, TEST_PROMPT);
    }

    /** 拉取 OpenAI 兼容服务的模型列表，供管理台下拉选择 */
    public List<Map<String, Object>> listModels(String baseInput, String keyInput) {
        Credentials c = credentials(baseInput, keyInput);
        String body = getModels(c);
        JsonNode root;
        try {
            root = om.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            throw new VisionException("模型列表解析失败，请确认服务地址是 OpenAI 兼容接口。");
        }
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            throw new VisionException("这个服务没有返回模型列表，可以手动填写模型名。");
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode n : data) {
            String id = n.path("id").asText("").trim();
            if (id.isEmpty()) continue;
            List<String> endpoints = new ArrayList<>();
            for (JsonNode e : n.path("supported_endpoint_types")) endpoints.add(e.asText());

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("owner", n.path("owned_by").asText(""));
            m.put("endpoints", endpoints);
            m.put("imageGeneration", looksLikeImageModel(id, endpoints));
            out.add(m);
        }
        return out;
    }

    // ---------------------------------------------------------------- 内部

    private String chat(VisionSettings cfg, String base64, String mime, String prompt) {
        ObjectNode payload = om.createObjectNode();
        payload.put("model", cfg.model);
        payload.put("max_tokens", 800);
        ArrayNode messages = payload.putArray("messages");

        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", "你是化妆品标签识别助手，严格按用户要求的格式输出；用户要求 JSON 时就只输出 JSON，不要解释或 Markdown。");

        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");
        ObjectNode textPart = content.addObject();
        textPart.put("type", "text");
        textPart.put("text", prompt);
        ObjectNode imgPart = content.addObject();
        imgPart.put("type", "image_url");
        ObjectNode url = imgPart.putObject("image_url");
        String realMime = (mime == null || mime.isEmpty()) ? "image/jpeg" : mime;
        url.put("url", "data:" + realMime + ";base64," + base64);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + cfg.apiKey);

        String resp = postChat(cfg.baseUrl, om, payload, headers);
        JsonNode root;
        try {
            root = om.readTree(resp == null ? "{}" : resp);
        } catch (Exception e) {
            throw new VisionException("视觉服务返回的内容无法解析，请确认服务地址是 OpenAI 兼容接口。", e);
        }

        String out = extractText(root.path("choices").path(0).path("message").path("content"));
        if (out.isEmpty()) {
            throw new VisionException("视觉模型返回了空内容。这个模型可能不支持看图，请换一个多模态模型。");
        }
        if (out.startsWith("data:image") || out.startsWith("http://") || out.startsWith("https://")) {
            throw new VisionException("视觉模型返回的是图片而不是文字。这个模型可能只支持画图，不能识别成分表。");
        }
        return out;
    }

    /** 兼容 base 带不带 /v1 两种写法：先试 /chat/completions，404 再试 /v1/chat/completions */
    private String postChat(String baseUrl, ObjectMapper om, ObjectNode payload, HttpHeaders headers) {
        String json;
        try {
            json = om.writeValueAsString(payload);
        } catch (Exception e) {
            throw new VisionException("构造视觉请求失败", e);
        }

        String[] urls = { baseUrl + "/chat/completions", baseUrl + "/v1/chat/completions" };
        for (int i = 0; i < urls.length; i++) {
            try {
                String resp = rt.postForObject(urls[i],
                        new HttpEntity<>(json, headers), String.class);
                return resp;
            } catch (HttpStatusCodeException e) {
                int status = e.getStatusCode().value();
                String body = e.getResponseBodyAsString();
                boolean modelMissing = body.contains("model_not_found") || body.contains("No available channel");
                // 只有「路径不对」的 404 才值得换 /v1 再试；模型不存在不能重试
                if (status == 404 && i == 0 && !modelMissing) {
                    continue;
                }
                // 上游原始 JSON 只进日志，不丢给用户看
                System.err.println("[视觉] 上游返回 " + status + "：" + body);
                throw new VisionException(friendlyMessage(status, body, payload.path("model").asText("")), e);
            } catch (ResourceAccessException e) {
                System.err.println("[视觉] 连接失败：" + e.getMessage());
                throw new VisionException(
                        "连不上视觉模型服务，请检查网络或服务地址后重试；也可以先改用粘贴文本。", e);
            }
        }
        throw new VisionException("服务地址下没有找到对话接口，请确认 Base URL 是否填对。");
    }

    private String getModels(Credentials c) {
        String[] urls = { c.baseUrl + "/v1/models", c.baseUrl + "/models" };
        for (int i = 0; i < urls.length; i++) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.set("Authorization", "Bearer " + c.apiKey);
                ResponseEntity<String> resp = fastRt.exchange(
                        urls[i], HttpMethod.GET, new HttpEntity<>(headers), String.class);
                if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                    return resp.getBody();
                }
            } catch (HttpStatusCodeException e) {
                int status = e.getStatusCode().value();
                if (status == 404) continue;
                if (status == 401 || status == 403) {
                    throw new VisionException("这个 API Key 没有权限访问模型列表，请检查 Key 或服务地址。");
                }
                System.err.println("[视觉] 拉取模型列表失败 " + status + "：" + e.getResponseBodyAsString());
                throw new VisionException(friendlyMessage(status, e.getResponseBodyAsString(), c.modelName));
            } catch (ResourceAccessException e) {
                throw new VisionException("连不上这个服务地址，请检查 Base URL 和网络。", e);
            }
        }
        throw new VisionException("服务地址下没有找到模型列表接口，可以手动填写模型名。");
    }

    private VisionSettings resolve(String baseInput, String keyInput, String modelInput) {
        Credentials c = credentials(baseInput, keyInput);
        VisionSettings eff = aiConfig.visionEndpoint();
        String model = VisionSettings.notBlank(modelInput) ? modelInput.trim() : eff.model;
        if (!VisionSettings.notBlank(model)) {
            throw new IllegalArgumentException("请填写视觉模型名");
        }
        return new VisionSettings(c.baseUrl, c.apiKey, model);
    }

    /**
     * 解析管理台提交的地址与 Key。
     * 没填的字段回退到已保存的视觉覆盖块 / 总 API；换地址时不允许悄悄复用旧 Key。
     */
    private Credentials credentials(String baseInput, String keyInput) {
        VisionSettings eff = aiConfig.visionEndpoint();
        String base = VisionSettings.notBlank(baseInput) ? requireHttpBase(baseInput) : cleanBase(eff.baseUrl);
        String key = keyInput == null ? "" : keyInput.trim();

        if (key.isEmpty()) {
            if (!VisionSettings.notBlank(base)) {
                throw new IllegalArgumentException("请填写服务地址");
            }
            if (!base.equals(cleanBase(eff.baseUrl)) && VisionSettings.notBlank(eff.apiKey)) {
                throw new IllegalArgumentException("更换服务地址时，必须同时填写该服务的 API Key");
            }
            key = eff.apiKey;
        }
        if (!VisionSettings.notBlank(base)) {
            throw new IllegalArgumentException("请填写服务地址");
        }
        if (!VisionSettings.notBlank(key)) {
            throw new IllegalArgumentException("请填写 API Key");
        }
        return new Credentials(base, key, eff.model);
    }

    private Recognition parseRecognition(String raw) {
        Recognition result = new Recognition();
        result.raw = raw == null ? "" : raw.trim();
        if (result.raw.isEmpty()) {
            throw new VisionException("视觉模型返回了空内容。这个模型可能不支持看图，请换一个多模态模型。");
        }

        String candidate = stripFence(result.raw);
        JsonNode node = null;
        try {
            node = om.readTree(candidate);
        } catch (Exception ignored) {
            int start = candidate.indexOf('{');
            int end = candidate.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    node = om.readTree(candidate.substring(start, end + 1));
                } catch (Exception ignoredAgain) {
                    // 下面按纯文本回退
                }
            }
        }

        if (node != null && node.isObject()) {
            result.productName = cleanProductName(firstText(node, "productName", "product_name"));
            result.text = ingredientText(node.path("ingredients"));
            if (result.text.isEmpty()) result.text = node.path("text").asText("").trim();
        } else {
            // 兼容不按 JSON 返回的旧模型：整段当成分表，产品名留空
            if (!candidate.startsWith("{") && !candidate.startsWith("[")) {
                result.text = candidate;
            }
        }

        result.text = result.text.trim();
        if (result.text.isEmpty()) {
            throw new VisionException("视觉模型没有返回成分表，请换一张更清晰的照片，或先粘贴成分表文本。");
        }
        return result;
    }

    private String stripFence(String value) {
        String text = value == null ? "" : value.trim();
        if (!text.startsWith("```")) return text;
        int firstLine = text.indexOf('\n');
        if (firstLine < 0) return text.replace("```", "").trim();
        String body = text.substring(firstLine + 1);
        int fence = body.lastIndexOf("```");
        if (fence >= 0) body = body.substring(0, fence);
        return body.trim();
    }

    private String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            String value = node.path(key).asText("").trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private String ingredientText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : node) {
                String value = item.isTextual() ? item.asText("").trim() : firstText(item, "name", "text");
                if (!value.isEmpty()) parts.add(value);
            }
            return String.join("、", parts);
        }
        return node.asText("").trim();
    }

    private String cleanProductName(String value) {
        String name = value == null ? "" : value.trim()
                .replaceAll("\\s+", " ")
                .replaceFirst("^(产品名|品名|商品名)\\s*[:：]\\s*", "");
        String lower = name.toLowerCase(Locale.ROOT);
        if (name.isEmpty() || lower.equals("null") || lower.equals("unknown")
                || name.equals("未识别") || name.equals("无") || name.equals("看不到")
                || name.equals("—") || name.equals("-")) {
            return "";
        }
        return name.length() > 40 ? name.substring(0, 40) : name;
    }

    public static class Recognition {
        /** 兼容旧字段：成分表文本 */
        public String text = "";
        public String productName = "";
        public String raw = "";
    }

    private String extractText(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) return "";
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (part.isTextual()) {
                    sb.append(part.asText());
                } else if (part.has("text")) {
                    sb.append(part.path("text").asText(""));
                }
            }
            return sb.toString().trim();
        }
        return content.asText("").trim();
    }

    private String friendlyMessage(int status, String body, String model) {
        String b = body == null ? "" : body;
        String m = VisionSettings.notBlank(model) ? model : "当前模型";

        if (b.contains("model_not_found") || b.contains("No available channel")
                || b.contains("model not found")) {
            return "视觉模型「" + m + "」在当前服务不可用（这个密钥分组可能没有开通它）。"
                    + "请确认服务已开通视觉模型，并据此设置模型名；也可以先改用「粘贴成分表文本」。";
        }
        if (status == 401 || status == 403) {
            return "视觉模型密钥无效或没有权限，请检查 API Key 和服务地址。";
        }
        if (status == 429) {
            return "视觉模型的额度或频率已用完，请稍后再试，或改用粘贴文本。";
        }
        if (status == 400 || status == 422) {
            return "视觉服务不接受这张图片，换一张更清晰、正对标签的照片，或改用粘贴文本。";
        }
        if (status == 413) {
            return "图片太大，视觉服务拒收了。换一张清晰但体积更小的照片，或改用粘贴文本。";
        }
        return "视觉服务暂时不可用（" + status + "），请稍后重试或改用粘贴文本。";
    }

    private boolean looksLikeImageModel(String id, List<String> endpoints) {
        String lower = id.toLowerCase(Locale.ROOT);
        if (endpoints.contains("image-generation")) return true;
        return lower.contains("image") || lower.contains("dall-e") || lower.contains("imagen")
                || lower.contains("flux") || lower.contains("stable-diffusion");
    }

    private String requireHttpBase(String url) {
        String u = cleanBase(url);
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            throw new IllegalArgumentException("服务地址必须以 http:// 或 https:// 开头");
        }
        return u;
    }

    private String cleanBase(String url) {
        return url == null ? "" : url.trim().replaceAll("/+$", "");
    }

    private static class Credentials {
        final String baseUrl;
        final String apiKey;
        final String modelName;

        Credentials(String baseUrl, String apiKey, String modelName) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.modelName = modelName;
        }
    }
}
