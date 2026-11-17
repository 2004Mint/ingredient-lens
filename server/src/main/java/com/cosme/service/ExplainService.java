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
        // 没有结论就没有可解释的东西：别把空事实喂给模型，让它对着空气发挥
        if (report == null || report.isEmpty() || !(report.get("verdict") instanceof Map)) {
            return noticeStream("没有收到可解释的报告内容，刷新页面后再试一次。", "缺少报告内容");
        }
        return start(buildReportFacts(report, profile), fallbackReport(report), REPORT_SYSTEM, "report");
    }

    /** B：单个成分的解释，点开详情才请求。 */
    public SseEmitter ingredientStream(Map<String, Object> payload) {
        Map<String, Object> safe = payload == null ? Collections.emptyMap() : payload;
        if (str(safe.get("name")).isEmpty()) {
            return noticeStream("没有收到成分名，这段说明没法生成。", "缺少成分名");
        }
        Map<String, Object> facts = buildIngredientFacts(safe);
        return start(facts, fallbackIngredient(safe), INGREDIENT_SYSTEM, "ingredient");
    }

    /** 输入不完整时直接给一句说明，不调用模型，也不假装生成过。 */
    private SseEmitter noticeStream(String text, String reason) {
        SseEmitter emitter = new SseEmitter(15_000L);
        AtomicBoolean closed = new AtomicBoolean(false);
        pool.submit(() -> {
            try {
                sendReplace(emitter, closed, text, "template", reason);
                done(emitter, closed, "template", reason);
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

    /** 模型不可用或输出没过闸门时的本地解释：只用已有字段拼装，不新增任何事实。 */
    private String fallbackIngredient(Map<String, Object> payload) {
        String name = str(payload.get("name"));
        String role = str(payload.get("role"));
        String tier = tierText(str(payload.get("tier")));
        String function = firstText(payload.get("description"), payload.get("function"));
        String note = str(payload.get("note"));

        StringBuilder sb = new StringBuilder();
        sb.append(name.isEmpty() ? "这个成分" : name);
        boolean placed = false;
        if (!role.isEmpty()) {
            sb.append("在这瓶配方里属于").append(role).append("成分");
            placed = true;
        }
        if (!tier.isEmpty()) {
            sb.append(placed ? "，位置在" : "位置在").append(tier);
            placed = true;
        }
        if (placed) sb.append("。");
        appendSentence(sb, function);
        appendSentence(sb, note);
        // 名字之外什么都没有：多半是成分库还没收录。别只吐一个成分名当解释——
        // 用户看到「CI 77891。」会以为页面坏了，而「没收录」本身就是一条有用的信息。
        if (!placed && function.isEmpty() && note.isEmpty()) {
            sb.append("：成分库里还没有它的记录，作用与风险暂时没有可靠结论，这里只能确认它出现在这份配方里。");
        }
        return sb.toString();
    }

    /** 拼句子：原文已经有句末标点就不再补，避免出现「。。」 */
    private static void appendSentence(StringBuilder sb, String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return;
        sb.append(t);
        char last = t.charAt(t.length() - 1);
        if (last != '。' && last != '！' && last != '？' && last != '；' && last != '…') sb.append("。");
    }

    // ---------------------------------------------------------------- 流式主体

    private SseEmitter start(Map<String, Object> facts, String fallback, String system, String kind) {
        // 超时要盖得住「流式 45s + 断流后非流式重试 60s」，否则重试还没回来，
        // 容器先把连接关了，用户只能看到半句话。150s 留一次完整重试的余量。
        SseEmitter emitter = new SseEmitter(150_000L);
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
                    String settled = retried.isEmpty() || !endsWithTerminal(retried) ? null : settle(retried);
                    if (settled != null) {
                        send(emitter, closed, step("retry", "已重新生成完整解释", "done"));
                        sendReplace(emitter, closed, settled, "llm", "重新生成了一次完整解释");
                        done(emitter, closed, "llm", "重新生成了一次完整解释");
                        return;
                    }
                    incomplete = true;
                }

                if (text.isEmpty() || incomplete) {
                    String reason = upstreamError == null ? "这次回答没写完" : shorten(upstreamError);
                    send(emitter, closed, step("guard", "已回退本地解释", "done"));
                    sendReplace(emitter, closed, fallback, "template", reason);
                    done(emitter, closed, "template", reason);
                    return;
                }

                String violation = guard(text);
                if (violation != null) {
                    // 计数类数字先改写成中文；带百分号/单位的改不了，直接回退，别让用户看到越界文本
                    String rewritten = "出现了数字或百分比".equals(violation) ? salvage(text) : null;
                    if (rewritten != null) {
                        send(emitter, closed, step("guard", "解释里的数字已改写为中文", "done"));
                        sendReplace(emitter, closed, rewritten, "llm", "数字已改写为中文");
                        done(emitter, closed, "llm", "数字已改写为中文");
                        return;
                    }
                    // 已经把不合规的文字推给前端了，所以整段替换，而不是假装没发生
                    send(emitter, closed, step("guard", friendly(violation) + "，已改用规则说明", "done"));
                    sendReplace(emitter, closed, fallback, "template", friendly(violation));
                    done(emitter, closed, "template", friendly(violation));
                    return;
                }

                send(emitter, closed, step("guard", "数字与用语校验通过", "done"));
                done(emitter, closed, "llm", null);
            } catch (Exception e) {
                System.err.println("[解释] 流式失败：" + e);
                streamFallback(emitter, closed, fallback, "template", "这次没能生成");
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
        // 已经推给浏览器的字收不回来。此时再换下一个地址重试，用户会看到
        // 前半段 + 后半段拼在一起的两份内容，所以只能失败，交给上层整段替换。
        boolean emitted = false;
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
                        if (!delta.isEmpty()) {
                            onDelta.accept(delta);
                            emitted = true;
                        }
                        String finish = choice.path("finish_reason").asText("");
                        if (!finish.isEmpty()) result.finishReason = finish;
                    }
                }
                return result;
            } catch (Exception e) {
                if (emitted) throw e;
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

    Map<String, Object> buildReportFacts(Map<String, Object> report, Map<String, Object> profile) {
        Map<String, Object> facts = new LinkedHashMap<>();
        Map<?, ?> verdict = asMap(report.get("verdict"));

        facts.put("结论等级", levelText(str(verdict.get("level"))));
        facts.put("结论标题", neutralize(str(verdict.get("headline"))));
        // 事实里一律不出现阿拉伯数字：模型最容易照抄的就是这几个计数，抄了就过不了闸门
        facts.put("成分总数", cn(intOf(report.get("total"))));
        facts.put("已识别", cn(intOf(report.get("matched"))));
        facts.put("未识别", cn(intOf(report.get("unknownCount"))));
        facts.put("用户档案", profileText(profile));

        List<Map<String, Object>> flags = new ArrayList<>();
        for (Object item : asList(report.get("flags"))) {
            Map<?, ?> f = asMap(item);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("类型", str(f.get("type")));
            row.put("等级", str(f.get("level")));
            row.put("成分", f.get("ingredients"));
            row.put("说明", neutralize(shorten(str(f.get("message")))));
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
            row.put("排位", "第" + cn(intOf(a.get("index")) + 1) + "位");
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

    Map<String, Object> buildIngredientFacts(Map<String, Object> payload) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("成分", str(payload.get("name")));
        facts.put("角色", str(payload.get("role")));
        facts.put("分类", str(payload.get("cat")));
        // 只有真的知道排位时才给排位。缺省时曾经按 index=0 算成「第一位」，
        // 模型就会理直气壮地说「位居首位」——这是凭空造依据，比不说更糟。
        Object index = payload.get("index");
        if (index instanceof Number && ((Number) index).intValue() >= 0) {
            facts.put("排位", "第" + cn(((Number) index).intValue() + 1) + "位");
        }
        facts.put("梯队", tierText(str(payload.get("tier"))));
        facts.put("致痘性", comedoText(payload.get("comedo")));
        facts.put("刺激性", riskText(str(payload.get("irrit"))));
        facts.put("孕期", riskText(str(payload.get("preg"))));
        facts.put("敏感肌", riskText(str(payload.get("sensitive"))));
        facts.put("法规限量", limitText(str(payload.get("limit"))));
        facts.put("使用提示", neutralize(str(payload.get("note"))));
        facts.put("一句话作用", neutralize(str(payload.get("function"))));
        facts.put("报告里的评价", neutralize(str(payload.get("comment"))));
        facts.put("用户档案", profileText(asMap(payload.get("profile"))));
        // 空值去掉，避免模型把「null」当成事实复述
        facts.entrySet().removeIf(e -> e.getValue() == null
                || (e.getValue() instanceof String && ((String) e.getValue()).trim().isEmpty()));
        return facts;
    }

    /** 输出闸门：返回 null 表示放行，否则返回拦截原因。包内可见，便于单测直接调用。 */
    static String guard(String text) {
        if (text.isEmpty()) return "模型没有返回内容";
        if (text.length() < 20) return "内容过短";
        if (text.length() > 520) return "内容过长";
        // 百分号本身就是浓度声明，先拦，别被下面的「成分名豁免」放过
        if (text.contains("%") || text.contains("％")) return "出现了数字或百分比";
        // 成分名里的数字不是浓度：1,2-戊二醇、二苯酮-3、聚山梨醇酯-20 必须能说出口
        String withoutNames = NAME_DIGITS.matcher(text).replaceAll("");
        // 维生素B5、SPF50 这类「字母 + 数字」是名称/标示，和成分名同等待遇；
        // 不豁免的话，成分库里泛醇的作用说明会被改写成「维生素B五」再喂给模型。
        withoutNames = CODE_DIGITS.matcher(withoutNames).replaceAll("");
        // 着色剂编号：CI 77891、CI 77491、CI 15850:1。数字就是名字本身，
        // 不豁免的话，凡是带 CI 号的成分（彩妆几乎全都有）都解释不出来。
        withoutNames = COLOR_CODE.matcher(withoutNames).replaceAll("");
        withoutNames = COLOR_NAME.matcher(withoutNames).replaceAll("");
        if (DIGITS.matcher(withoutNames).find()) return "出现了数字或百分比";
        for (String word : BANNED) {
            if (text.contains(word)) return "出现了医疗用语「" + word + "」";
        }
        return null;
    }

    /** 成分名里的数字：数字串紧跟连字符（1,2- 戊二醇），或连字符后接数字（二苯酮-3） */
    private static final Pattern NAME_DIGITS = Pattern.compile(
            "[0-9０-９]+(?:[，,][0-9０-９]+)*-|[-－][0-9０-９]+");

    /** 带字母的代号：维生素B5、B3、SPF50、C10。它们是名称的一部分，不是浓度。 */
    private static final Pattern CODE_DIGITS = Pattern.compile("[A-Za-z][0-9]{1,2}");

    /** 着色剂编号：CI 77891 / CI77491 / CI 15850:1。彩妆配方里几乎每条都带，必须能说出来。 */
    private static final Pattern COLOR_CODE = Pattern.compile("(?i)\\bCI\\s?[0-9]{4,5}(?::[0-9]{1,2})?");

    /** 色素旧称：FD&C Yellow 5、D&C Red 33、Yellow 5。同样是名字，不是数值。 */
    private static final Pattern COLOR_NAME = Pattern.compile(
            "(?i)\\b(?:FD&C|D&C)?\\s*(?:yellow|red|blue|green|orange|violet|black|brown)"
                    + "\\s*(?:no\\.?\\s*)?[0-9]{1,3}\\b");

    /**
     * 闸门只因为数字没过时，先试着把数字改写成中文再说。
     *
     * <p>为什么需要这一步：事实里本来就有「成分总数」「第几位」这类计数，模型很自然会把它们抄进解释，
     * 于是「喂数字、禁数字」自己把自己卡住。计数改写成中文（「第 5 位」→「第五位」）不丢掉信息，
     * 也不会变成浓度声明；但一旦带百分号或剂量单位（2.0%、0.5 克），说明它在说浓度，一律不改写，直接回退模板。</p>
     */
    static String salvage(String text) {
        if (text == null || text.isEmpty()) return null;
        if (MEASURE.matcher(text).find()) return null;
        String rewritten = neutralize(text);
        if (rewritten.equals(text)) return null;
        return guard(rewritten) == null ? rewritten : null;
    }

    /** 能过闸门就原样用，过不了就试着改写数字；两条都不行返回 null，交给模板兜底 */
    static String settle(String text) {
        if (text == null || text.isEmpty()) return null;
        if (guard(text) == null) return text;
        return salvage(text);
    }

    /** 数字后面直接跟这些单位，说明是剂量或浓度，不能靠改写成文字蒙混过关 */
    private static final Pattern MEASURE = Pattern.compile(
            "[0-9０-９]\\s*(?:%|％|mg|g|ml|毫升|毫克|微克|克)");

    /** 把自由文本里的计数改成中文数字；小数保持原样（小数意味着精度，不能改） */
    static String neutralize(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder ascii = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            ascii.append(c >= '０' && c <= '９' ? (char) ('0' + (c - '０')) : c);
        }
        String s = ascii.toString();
        // 序号：「第 10 位」连空格一起去掉，否则会读成「第 十 位」。
        // 这里只替换「第」后面的数字，别把「第」本身再写一遍（会变成「第第十位」）。
        s = Pattern.compile("(?<=第)\\s*([0-9]{1,3})\\s*(?![0-9.．])")
                .matcher(s)
                .replaceAll(hit -> cn(numOf(hit.group(1))));
        // 行首或空格后的计数：「2 个成分」→「两个成分」。句子以数字开头时（模型偶尔会这样写），
        // 上面那条「汉字在前」的规则接不住，会留下「十二 个成分」这种带空格的断句。
        s = Pattern.compile("(?<![0-9A-Za-z.．])[ \\t]*([0-9]{1,3})[ \\t]*(?=[个种次条瓶份处片支款])")
                .matcher(s)
                .replaceAll(hit -> cn(numOf(hit.group(1))));
        // 中文句子里的计数：「共 12 个」→「共十二个」，把两侧空格一并收掉；
        // 数字紧邻连字符、逗号、小数点、斜杠、百分号时一律不动——那是成分名或浓度。
        // 紧跟在字母后面的数字同样不动：那是维生素B5、SPF50 这类代号，改了就成了错字。
        s = Pattern.compile("(?<=[\\u4e00-\\u9fa5，。、；：！？）】])\\s*([0-9]{1,3})\\s*(?![0-9.．,，\\-－/%％])")
                .matcher(s)
                .replaceAll(hit -> cn(numOf(hit.group(1))));
        // 剩下的（英文/符号上下文里的）只换数字，不动空格；同样跳过成分名与浓度写法
        java.util.regex.Matcher m = Pattern.compile(
                "(?<![A-Za-z\\-－,，.．/0-9])([0-9]{1,3})(?![0-9.．,，\\-－/%％])").matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, cn(numOf(m.group(1))));
        }
        m.appendTail(out);
        // 「2 个成分」应说成「两个成分」；「十二个」「二百个」保持不变
        return LIANG_TWO.matcher(out.toString()).replaceAll("两");
    }

    /** 单独使用的「二」在量词前应写成「两」：两个成分 / 两次；但十二个、第二款不动 */
    private static final Pattern LIANG_TWO = Pattern.compile(
            "(?<![十百千万亿两第0-9])二(?=[个种次条瓶份处片支款])");

    private static int numOf(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 0–999 的中文写法：事实里不出现阿拉伯数字，模型照抄也不会踩闸门 */
    static String cn(int n) {
        String[] d = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        if (n < 0 || n > 999) return String.valueOf(n);
        if (n < 10) return d[n];
        if (n < 20) return n == 10 ? "十" : "十" + d[n % 10];
        if (n < 100) return d[n / 10] + "十" + (n % 10 == 0 ? "" : d[n % 10]);
        int rest = n % 100;
        String head = d[n / 100] + "百";
        if (rest == 0) return head;
        if (rest < 10) return head + "零" + d[rest];
        return head + cn(rest);
    }

    private static int intOf(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 致痘性 0–5 只说等级：说了数字就等于在暗示精确程度；不知道就干脆不说 */
    private static String comedoText(Object value) {
        if (!(value instanceof Number)) return "";
        int v = ((Number) value).intValue();
        if (v <= 0) return "低";
        if (v <= 2) return "较低";
        if (v == 3) return "中等";
        if (v == 4) return "较高";
        return "高";
    }

    /** 限量原文带数字时只给「有没有限制」，不把数值喂给模型，避免它照抄 */
    private static String limitText(String limit) {
        if (limit == null || limit.trim().isEmpty()) return "";
        return limit.matches("(?s).*[0-9０-９].*") ? "有明确的法规限量要求" : limit;
    }

    /** 给用户看的拦截原因：不出现「闸门」「越界」这类内部说法 */
    private static String friendly(String violation) {
        if (violation == null) return "";
        if (violation.startsWith("出现了数字")) return "解释里带了具体数值";
        if (violation.startsWith("出现了医疗用语")) return "解释越过了医疗边界";
        if (violation.equals("内容过长") || violation.equals("内容过短")) return "解释长度不合适";
        return violation;
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
    /** 判断模型输出是否以句末标点收尾：用来识别「上游提前断流」。包内可见，便于单测。 */
    static boolean endsWithTerminal(String text) {
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
