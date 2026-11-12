package com.cosme.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 生成式解释（A 结论人话版 + B 单个成分按需解释）。
 *
 * <p>三条原则：</p>
 * <ol>
 *   <li><b>事实由规则提供</b>：模型只拿规则算好的结论、风险项、位次，不允许自己判断。</li>
 *   <li><b>真流式</b>：上游 {@code stream:true} 的 delta 直接透传给浏览器，不是前端假打字机。</li>
 *   <li><b>输出闸门</b>：解释里不许出现数字/百分比、不许出现医疗用语、不许超长；不过关就整段回退模板。</li>
 * </ol>
 *
 * <p>为什么禁止数字：位次只能说明相对顺序，推不出浓度；让模型复述数字，等于把不可证的推断
 * 交给概率模型再放大一次。禁用数字后，解释只能描述"位置与角色"，这正好是本产品敢说、也说得清的部分。</p>
 */
@Service
public class ExplainService {

    /** 解释里禁止出现的医疗用语：越界即回退。 */
    private static final Set<String> BANNED = new HashSet<>(Arrays.asList(
            "治疗", "治愈", "诊断", "处方", "根治", "疗效", "药效", "药用", "医美"));

    /** 数字、百分号、中文百分数：一律不允许出现在模型输出里。 */
    private static final Pattern DIGITS = Pattern.compile("[0-9０-９]|百分之|[一二三四五六七八九十]+%");

    private static final String REPORT_SYSTEM =
            "你是化妆品成分解读助手。根据给定事实，用 2–4 句中文解释这份分析为什么给出这个结论。\n"
                    + "硬性要求：\n"
                    + "1. 只能使用事实里出现的信息，不得新增成分，不得推测任何浓度或含量。\n"
                    + "2. 禁止出现任何阿拉伯数字、百分号或「百分之」。\n"
                    + "3. 不得给出医疗建议，不得使用「治疗」「诊断」「处方」「疗效」等词。\n"
                    + "4. 不用 Markdown、不用列表、不加标题，直接输出句子；不要重复事实清单。";

    private static final String INGREDIENT_SYSTEM =
            "你是化妆品成分解读助手。根据给定事实，用 2–3 句中文说明这个成分在这瓶配方里的位置和意义。\n"
                    + "硬性要求：\n"
                    + "1. 只能用事实里出现的信息；不得推测浓度，不得新增成分。\n"
                    + "2. 禁止出现任何阿拉伯数字、百分号或「百分之」。\n"
                    + "3. 不得给出医疗建议或疗效承诺。\n"
                    + "4. 至少写满三个短句，不要只给一句短语。\n"
                    + "5. 不用 Markdown、不用列表，直接输出句子。";

    private final AiConfigService aiConfig;
    private final ObjectMapper om = new ObjectMapper();
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "explain-stream");
        t.setDaemon(true);
        return t;
    });

    public ExplainService(AiConfigService aiConfig) {
        this.aiConfig = aiConfig;
    }

    // ---------------------------------------------------------------- 对外入口

    /** A：整份报告的结论解释。 */
    public SseEmitter reportStream(Map<String, Object> report, Map<String, Object> profile) {
        return start(buildReportFacts(report, profile), fallbackReport(report), REPORT_SYSTEM, "report");
    }

    /** B：单个成分的解释，点开详情才请求。 */
    public SseEmitter ingredientStream(Map<String, Object> payload) {
        Map<String, Object> facts = buildIngredientFacts(payload);
        return start(facts, fallbackIngredient(payload), INGREDIENT_SYSTEM, "ingredient");
    }

    /** 模型不可用或输出没过闸门时的本地解释：只用已有字段拼装，不新增任何事实。 */
    private String fallbackIngredient(Map<String, Object> payload) {
        String name = str(payload.get("name"));
        String role = str(payload.get("role"));
        String tier = tierText(str(payload.get("tier")));
        String function = firstText(payload.get("description"), payload.get("function"));
        String note = str(payload.get("note"));

        StringBuilder sb = new StringBuilder();
        sb.append(name.isEmpty() ? "这个成分" : name);
        if (!role.isEmpty()) sb.append("在这瓶配方里属于").append(role).append("成分");
        if (!tier.isEmpty()) sb.append("，位置在").append(tier);
        sb.append("。");
        if (!function.isEmpty()) sb.append(function).append("。");
        if (!note.isEmpty()) sb.append(note).append("。");
        return sb.toString();
    }

    // ---------------------------------------------------------------- 流式主体

    private SseEmitter start(Map<String, Object> facts, String fallback, String system, String kind) {
        SseEmitter emitter = new SseEmitter(90_000L);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> {
            closed.set(true);
            emitter.complete();
        });
        emitter.onError(e -> closed.set(true));

        pool.submit(() -> {
            try {
                // 步骤一：事实已经由规则算好，这一步是真实状态，不是装饰
                send(emitter, closed, step("facts", "读取规则结论", "done"));

                VisionSettings cfg = aiConfig.researchEndpoint();
                if (!aiConfig.researchEnabled()) {
                    send(emitter, closed, step("gen", "模型未启用，使用本地模板", "done"));
                    streamFallback(emitter, closed, fallback, "template", "未配置文本模型");
                    return;
                }

                send(emitter, closed, step("gen", kind.equals("report") ? "生成解释" : "生成成分说明", "running"));

                StringBuilder got = new StringBuilder();
                String userPrompt = "事实（JSON）：\n" + om.writeValueAsString(facts)
                        + "\n请按要求输出解释。";

                String upstreamError = null;
                StreamResult streamResult = null;
                try {
                    streamResult = streamChat(cfg, system, userPrompt, delta -> {
                        got.append(delta);
                        send(emitter, closed, deltaEvent(delta));
                    });
                } catch (Exception e) {
                    upstreamError = e.getMessage();
                }

                String text = got.toString().trim();
                // 上游可能中途断流：既没有 [DONE]，也没有 finish_reason；或者「正常」结束
                // 但最后一句没写完。这两种都不能当成成功——否则用户看到半句话却没有任何提示。
                boolean incomplete = streamResult == null
                        || !streamResult.hasFinishSignal()
                        || !endsWithTerminal(text);

                if (incomplete && upstreamError == null) {
                    send(emitter, closed, step("retry", "上游提前结束，重新生成完整解释", "running"));
                    String full = null;
                    try {
                        full = completeChat(cfg, system, userPrompt);
                    } catch (Exception e) {
                        upstreamError = e.getMessage();
                    }
                    String retried = full == null ? "" : full.trim();
                    if (!retried.isEmpty() && endsWithTerminal(retried) && guard(retried) == null) {
                        send(emitter, closed, step("retry", "已重新生成完整解释", "done"));
                        sendReplace(emitter, closed, retried, "llm", "上游断流后重试成功");
                        done(emitter, closed, "llm", "上游断流后重试成功");
                        return;
                    }
                    incomplete = true;
                }

                if (text.isEmpty() || incomplete) {
                    String reason = upstreamError == null ? "上游输出不完整" : shorten(upstreamError);
                    send(emitter, closed, step("guard", "已回退本地解释", "done"));
                    sendReplace(emitter, closed, fallback, "template", reason);
                    done(emitter, closed, "template", reason);
                    return;
                }

                String violation = guard(text);
                if (violation != null) {
                    // 已经把不合规的文字推给前端了，所以整段替换，而不是假装没发生
                    send(emitter, closed, step("guard", "输出闸门拦截：" + violation, "done"));
                    sendReplace(emitter, closed, fallback, "template", violation);
                    done(emitter, closed, "template", violation);
                    return;
                }

                send(emitter, closed, step("guard", "数字与用语校验通过", "done"));
                done(emitter, closed, "llm", null);
            } catch (Exception e) {
                System.err.println("[解释] 流式失败：" + e);
                streamFallback(emitter, closed, fallback, "template", "生成失败");
            } finally {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // 客户端已断开
                }
            }
        });
        return emitter;
    }

    private void streamFallback(SseEmitter emitter, AtomicBoolean closed, String text,
                                String source, String reason) {
        String body = text == null ? "" : text.trim();
        if (body.isEmpty()) body = "这份报告暂时没有可用的解释。";
        // 本地文本分块推送，节奏接近真实输出；前端会标注「本地解释」
        int step = 22;
        for (int i = 0; i < body.length(); i += step) {
            String piece = body.substring(i, Math.min(body.length(), i + step));
            if (!send(emitter, closed, deltaEvent(piece))) return;
            try {
                Thread.sleep(18);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        done(emitter, closed, source, reason);
    }

    // ---------------------------------------------------------------- 上游流式调用

    private StreamResult streamChat(VisionSettings cfg, String system, String user,
                                    java.util.function.Consumer<String> onDelta) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", cfg.model);
        payload.put("temperature", 0.4);
        payload.put("max_tokens", 600);
        payload.put("stream", true);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(msg("system", system));
        messages.add(msg("user", user));
        payload.put("messages", messages);
        byte[] body = om.writeValueAsBytes(payload);

        StreamResult result = new StreamResult();
        Exception last = null;
        for (String url : candidateUrls(cfg.baseUrl)) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(8_000);
                conn.setReadTimeout(45_000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Accept", "text/event-stream");
                conn.setRequestProperty("Authorization", "Bearer " + cfg.apiKey);

                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body);
                }

                int status = conn.getResponseCode();
                if (status == 404) {
                    last = new IllegalStateException("404 " + url);
                    continue;
                }
                if (status >= 400) {
                    throw new IllegalStateException("上游返回 " + status + "：" + readSnippet(conn.getErrorStream()));
                }

                try (InputStream in = conn.getInputStream();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data:")) continue;
                        String chunk = line.substring(5).trim();
                        if (chunk.isEmpty()) continue;
                        if ("[DONE]".equals(chunk)) {
                            result.sawDone = true;
                            break;
                        }
                        JsonNode node = om.readTree(chunk);
                        JsonNode choice = node.path("choices").path(0);
                        String delta = choice.path("delta").path("content").asText("");
                        if (delta.isEmpty()) {
                            // 有些兼容实现只在 message.content 里给整段
                            delta = choice.path("message").path("content").asText("");
                        }
                        if (!delta.isEmpty()) onDelta.accept(delta);
                        String finish = choice.path("finish_reason").asText("");
                        if (!finish.isEmpty()) result.finishReason = finish;
                    }
                }
                return result;
            } catch (Exception e) {
                last = e;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw last == null ? new IllegalStateException("没有可用的对话接口") : last;
    }

    /** 非流式补全，用于「上游断流」时拿一份完整答案。 */
    private String completeChat(VisionSettings cfg, String system, String user) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", cfg.model);
        payload.put("temperature", 0.4);
        payload.put("max_tokens", 600);
        payload.put("stream", false);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(msg("system", system));
        messages.add(msg("user", user));
        payload.put("messages", messages);
        byte[] body = om.writeValueAsBytes(payload);

        Exception last = null;
        for (String url : candidateUrls(cfg.baseUrl)) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(8_000);
                conn.setReadTimeout(60_000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Authorization", "Bearer " + cfg.apiKey);

                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body);
                }

                int status = conn.getResponseCode();
                if (status == 404) {
                    last = new IllegalStateException("404 " + url);
                    continue;
                }
                if (status >= 400) {
                    throw new IllegalStateException("上游返回 " + status + "：" + readSnippet(conn.getErrorStream()));
                }

                try (InputStream in = conn.getInputStream()) {
                    JsonNode root = om.readTree(in);
                    return root.path("choices").path(0).path("message").path("content").asText("");
                }
            } catch (Exception e) {
                last = e;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw last == null ? new IllegalStateException("没有可用的对话接口") : last;
    }

    /** 一次流式调用的结果：是否收到明确的结束信号。 */
    private static class StreamResult {
        boolean sawDone;
        String finishReason;

        boolean hasFinishSignal() {
            return sawDone || (finishReason != null && !finishReason.isEmpty());
        }
    }

    private List<String> candidateUrls(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        List<String> urls = new ArrayList<>();
        if (base.endsWith("/v1")) {
            urls.add(base + "/chat/completions");
        } else {
            urls.add(base + "/v1/chat/completions");
            urls.add(base + "/chat/completions");
        }
        return urls;
    }

    private Map<String, String> msg(String role, String content) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private String readSnippet(InputStream in) {
        if (in == null) return "";
        try (InputStream s = in) {
            byte[] buf = new byte[400];
            int n = s.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 事实与闸门

    private Map<String, Object> buildReportFacts(Map<String, Object> report, Map<String, Object> profile) {
        Map<String, Object> facts = new LinkedHashMap<>();
        Map<?, ?> verdict = asMap(report.get("verdict"));

        facts.put("结论等级", levelText(str(verdict.get("level"))));
        facts.put("结论标题", str(verdict.get("headline")));
        facts.put("成分总数", report.get("total"));
        facts.put("已识别", report.get("matched"));
        facts.put("未识别", report.get("unknownCount"));
        facts.put("用户档案", profileText(profile));

        List<Map<String, Object>> flags = new ArrayList<>();
        for (Object item : asList(report.get("flags"))) {
            Map<?, ?> f = asMap(item);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("类型", str(f.get("type")));
            row.put("等级", str(f.get("level")));
            row.put("成分", f.get("ingredients"));
            row.put("说明", shorten(str(f.get("message"))));
            flags.add(row);
            if (flags.size() >= 8) break;
        }
        facts.put("风险项", flags);

        List<Map<String, Object>> actives = new ArrayList<>();
        List<String> tail = new ArrayList<>();
        for (Object item : asList(report.get("actives"))) {
            Map<?, ?> a = asMap(item);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("名称", str(a.get("name")));
            row.put("排位", a.get("index"));
            row.put("梯队", tierText(str(a.get("tier"))));
            row.put("分类", str(a.get("cat")));
            actives.add(row);
            if ("tail".equals(str(a.get("tier")))) tail.add(str(a.get("name")));
            if (actives.size() >= 6) break;
        }
        facts.put("功效成分", actives);
        facts.put("排在后段功效成分", tail);
        return facts;
    }

    private Map<String, Object> buildIngredientFacts(Map<String, Object> payload) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("成分", str(payload.get("name")));
        facts.put("角色", str(payload.get("role")));
        facts.put("分类", str(payload.get("cat")));
        facts.put("排位", payload.get("index"));
        facts.put("梯队", tierText(str(payload.get("tier"))));
        facts.put("致痘性", payload.get("comedo"));
        facts.put("刺激性", riskText(str(payload.get("irrit"))));
        facts.put("孕期", riskText(str(payload.get("preg"))));
        facts.put("敏感肌", riskText(str(payload.get("sensitive"))));
        facts.put("法规限量", str(payload.get("limit")));
        facts.put("使用提示", str(payload.get("note")));
        facts.put("一句话作用", str(payload.get("function")));
        facts.put("报告里的评价", str(payload.get("comment")));
        facts.put("用户档案", profileText(asMap(payload.get("profile"))));
        // 空值去掉，避免模型把「null」当成事实复述
        facts.entrySet().removeIf(e -> e.getValue() == null
                || (e.getValue() instanceof String && ((String) e.getValue()).trim().isEmpty()));
        return facts;
    }

    private String guard(String text) {
        if (text.isEmpty()) return "模型没有返回内容";
        if (text.length() < 20) return "内容过短";
        if (text.length() > 520) return "内容过长";
        if (DIGITS.matcher(text).find()) return "出现了数字或百分比";
        for (String word : BANNED) {
            if (text.contains(word)) return "出现了医疗用语「" + word + "」";
        }
        return null;
    }

    private String fallbackReport(Map<String, Object> report) {
        Map<?, ?> verdict = asMap(report.get("verdict"));
        String summary = str(verdict.get("summary"));
        if (!summary.isEmpty()) return summary;
        return "这份成分表的结论已经按你的档案算好，但暂时没有可用的文字解释。";
    }

    // ---------------------------------------------------------------- 事件与工具

    private Map<String, Object> step(String id, String label, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "step");
        m.put("id", id);
        m.put("label", label);
        m.put("status", status);
        return m;
    }

    private Map<String, Object> deltaEvent(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "delta");
        m.put("text", text);
        return m;
    }

    /** 用一段完整文本替换已经流出去的内容（重试成功 / 回退模板 / 闸门拦截）。 */
    private void sendReplace(SseEmitter emitter, AtomicBoolean closed, String text,
                             String source, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "replace");
        m.put("text", text == null ? "" : text);
        m.put("source", source);
        if (reason != null) m.put("reason", reason);
        send(emitter, closed, m);
    }

    /** 句子有没有写完：上游断流最常见的表现就是停在一句话中间。 */
    private boolean endsWithTerminal(String text) {
        if (text == null || text.isEmpty()) return false;
        char c = text.charAt(text.length() - 1);
        return c == '。' || c == '！' || c == '？' || c == '…'
                || c == '」' || c == '”' || c == '"' || c == ')';
    }

    private void done(SseEmitter emitter, AtomicBoolean closed, String source, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "done");
        m.put("source", source);
        if (reason != null) m.put("reason", reason);
        send(emitter, closed, m);
    }

    private boolean send(SseEmitter emitter, AtomicBoolean closed, Object payload) {
        if (closed.get()) return false;
        try {
            emitter.send(SseEmitter.event().data(payload));
            return true;
        } catch (Exception e) {
            closed.set(true);
            return false;
        }
    }

    private Map<?, ?> asMap(Object value) {
        return value instanceof Map ? (Map<?, ?>) value : Collections.emptyMap();
    }

    private List<?> asList(Object value) {
        return value instanceof List ? (List<?>) value : Collections.emptyList();
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstText(Object... values) {
        for (Object v : values) {
            String s = str(v);
            if (!s.isEmpty()) return s;
        }
        return "";
    }

    private String shorten(String text) {
        if (text == null) return "";
        String s = text.trim();
        return s.length() > 70 ? s.substring(0, 70) + "…" : s;
    }

    private String levelText(String level) {
        if ("avoid".equals(level)) return "建议避开";
        if ("caution".equals(level)) return "需要留意";
        if ("good".equals(level)) return "没有明显风险";
        if ("insufficient".equals(level)) return "信息不足，无法下结论";
        return level;
    }

    private String tierText(String tier) {
        if ("core".equals(tier)) return "配方前段";
        if ("middle".equals(tier)) return "配方中段";
        if ("tail".equals(tier)) return "配方后段";
        return "";
    }

    private String riskText(String value) {
        if ("avoid".equals(value)) return "需要避开";
        if ("caution".equals(value)) return "需要留意";
        if ("safe".equals(value)) return "通常安全";
        if ("high".equals(value)) return "高";
        if ("medium".equals(value)) return "中";
        if ("low".equals(value)) return "低";
        return value;
    }

    private String profileText(Map<?, ?> profile) {
        if (profile == null || profile.isEmpty()) return "未填写";
        String skin = str(profile.get("skin"));
        String skinText;
        switch (skin) {
            case "dry": skinText = "干性"; break;
            case "oily": skinText = "油性"; break;
            case "combo": skinText = "混合性"; break;
            case "normal": skinText = "中性"; break;
            default: skinText = skin.isEmpty() ? "未填写" : skin;
        }
        return skinText
                + (Boolean.TRUE.equals(profile.get("sensitive")) ? "、敏感肌" : "")
                + (Boolean.TRUE.equals(profile.get("pregnant")) ? "、孕期" : "");
    }
}
