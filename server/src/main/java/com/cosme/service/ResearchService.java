package com.cosme.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * 成分 AI 研究。
 *
 * 走 OpenAI 兼容的 /chat/completions，要求模型只输出 JSON；
 * 结果先回到「待研究」，人工确认后才成为正式已确认数据。
 * 扫描到的未知成分会自动研究并加入待研究库，让下一次匹配能认出来。
 */
@Service
public class ResearchService {

    private static final Set<String> ROLES = new LinkedHashSet<>(
            Arrays.asList("基质", "功效", "防腐", "香精", "防晒", "表活"));
    private static final Set<String> RISK = new LinkedHashSet<>(Arrays.asList("low", "medium", "high"));
    private static final Set<String> PROFILE = new LinkedHashSet<>(Arrays.asList("safe", "caution", "avoid"));

    private static final String SYSTEM_PROMPT =
            "你是化妆品成分数据库研究员。你只输出一个 JSON 对象，不要 Markdown、不要解释、不要代码块。"
                    + "不确定的法规限量必须留空，不要编造。";

    private final AiConfigService aiConfig;
    private final IngredientAdminService ingredientAdmin;
    private final RestTemplate rt;
    private final ObjectMapper om = new ObjectMapper();
    private final ExecutorService pool;
    private final Map<String, Long> recentFailures = new ConcurrentHashMap<>();

    public ResearchService(RestTemplateBuilder builder, AiConfigService aiConfig,
                           IngredientAdminService ingredientAdmin) {
        this.aiConfig = aiConfig;
        this.ingredientAdmin = ingredientAdmin;
        this.rt = builder.setConnectTimeout(Duration.ofSeconds(8))
                .setReadTimeout(Duration.ofSeconds(30)).build();
        this.pool = Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "ai-research");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean enabled() {
        return aiConfig.researchEnabled();
    }

    public String sourceName() {
        return aiConfig.researchSourceName();
    }

    public static class Result {
        public IngredientAdminService.Draft draft;
        public double confidence;
        public String evidence;
        public String model;
    }

    public static class BatchResult {
        public final List<String> researched = new ArrayList<>();
        public final List<String> failed = new ArrayList<>();
        public final Map<String, String> errors = new LinkedHashMap<>();
    }

    /** 单个成分研究，不写库。管理台「研究测试」也走这里。 */
    public Result research(String query) {
        return research(query, "");
    }

    public Result research(String query, String context) {
        String name = query == null ? "" : query.trim();
        if (name.isEmpty()) throw new ResearchException("要研究的成分名不能为空");
        VisionSettings cfg = aiConfig.researchEndpoint();
        if (!cfg.usable()) {
            throw new ResearchException("还没有配置研究 API。请到「API 与模型」配置总 API 或研究 API。");
        }
        return research(name, context == null ? "" : context, cfg);
    }

    // ---------------------------------------------------------------- 工具调用（Function Calling）

    /** 模型请求调用的工具。 */
    public static class ToolRequest {
        public String id;
        public String name;
        public String args;
    }

    /** 一次工具往返：模型要什么、我们返回什么。 */
    public static class ToolExchange {
        public String name;
        public String args;
        public String result;

        public ToolExchange() {
        }

        public ToolExchange(String name, String args, String result) {
            this.name = name;
            this.args = args;
            this.result = result;
        }
    }

    /** 一轮对话的结果：要么给出最终草稿，要么请求调用一个工具。 */
    public static class ToolTurn {
        public Result result;
        public ToolRequest request;
        /** 服务商不认 tools 字段时置 true，上层退回确定性计划 */
        public boolean toolsRejected;
        public String model;
    }

    /**
     * 带工具的一轮调用（OpenAI 兼容的 tools / tool_calls）。
     *
     * 两种可能：模型直接给出 JSON 草稿；或者它先要求查一次本地成分库。
     * 如果服务商不认 tools 字段（400 且报错里提到 tool/function），会自动去掉 tools 重试一次，
     * 并在返回值里标记 toolsRejected，让 Agent 退回确定性计划 —— 不因为工具不被支持就整条失败。
     */
    public static final int TOOL_OFF = 0;
    public static final int TOOL_AUTO = 1;
    public static final int TOOL_NONE = 2;

    public ToolTurn turn(String query, String context, List<ToolExchange> exchanges, int toolMode) {
        String name = query == null ? "" : query.trim();
        if (name.isEmpty()) throw new ResearchException("要研究的成分名不能为空");
        VisionSettings cfg = aiConfig.researchEndpoint();
        if (!cfg.usable()) {
            throw new ResearchException("还没有配置研究 API。请到「API 与模型」配置总 API 或研究 API。");
        }
        String ctx = context == null ? "" : context;
        if (toolMode == TOOL_OFF) return turnInternal(name, ctx, exchanges, cfg, TOOL_OFF, false);

        try {
            return turnInternal(name, ctx, exchanges, cfg, toolMode, false);
        } catch (ResearchException e) {
            if (isToolRejection(e)) {
                System.err.println("[研究] 服务商不支持 tools，退回不带工具的调用");
                return turnInternal(name, ctx, exchanges, cfg, TOOL_OFF, true);
            }
            // 空输出 / 不是 JSON：多半是模型把话放进了工具调用里。关掉工具再问一次，通常就能拿到正文。
            if (toolMode != TOOL_OFF && isEmptyOrUnparsable(e)) {
                System.err.println("[研究] 输出为空或不是 JSON，关掉工具重试一次");
                return turnInternal(name, ctx, exchanges, cfg, TOOL_NONE, true);
            }
            throw e;
        }
    }

    private boolean isEmptyOrUnparsable(ResearchException e) {
        String message = e.getMessage();
        if (message == null) return false;
        return message.contains("空内容") || message.contains("没有返回 JSON") || message.contains("JSON 无法解析");
    }

    /** 服务商是否在报错里明确说「不认识 tools / function」。 */
    private boolean isToolRejection(ResearchException e) {
        Throwable cause = e.getCause();
        if (!(cause instanceof HttpStatusCodeException)) return false;
        String body = ((HttpStatusCodeException) cause).getResponseBodyAsString();
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        return lower.contains("tool") || lower.contains("function") || lower.contains("unsupported");
    }

    /** 暴露给模型的三个只读工具。模型只能查，不能写。 */
    private static final String TOOLS_JSON =
            "[{\"type\":\"function\",\"function\":{\"name\":\"search_library\","
                    + "\"description\":\"在成分库中按名称或别名精确查询一个成分，返回是否已收录、标准名与角色\","
                    + "\"parameters\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}}},"
                    + "{\"type\":\"function\",\"function\":{\"name\":\"lookup_limit\","
                    + "\"description\":\"查询某成分在成分库中的法规限量原文；没有记录时返回未收录\","
                    + "\"parameters\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}}},"
                    + "{\"type\":\"function\",\"function\":{\"name\":\"list_role_examples\","
                    + "\"description\":\"列出某个角色下的若干成分子示例，用来判断这个角色是否合适\","
                    + "\"parameters\":{\"type\":\"object\",\"properties\":{\"role\":{\"type\":\"string\","
                    + "\"enum\":[\"基质\",\"功效\",\"防腐\",\"香精\",\"防晒\",\"表活\"]}},\"required\":[\"role\"]}}}]";

    private ToolTurn turnInternal(String query, String context, List<ToolExchange> exchanges,
                                  VisionSettings cfg, int toolMode, boolean rejected) {
        ObjectNode payload = om.createObjectNode();
        payload.put("model", cfg.model);
        payload.put("temperature", 0.1);
        payload.put("max_tokens", 900);

        ArrayNode messages = payload.putArray("messages");
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", SYSTEM_PROMPT + "\n你可以先调用工具核对成分库里的事实，再输出 JSON。");

        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userPrompt(query, context));

        if (exchanges != null) {
            for (int i = 0; i < exchanges.size(); i += 1) {
                ToolExchange ex = exchanges.get(i);
                if (ex == null || ex.name == null || ex.name.isEmpty()) continue;
                String callId = "call_" + i;
                ObjectNode assistant = messages.addObject();
                assistant.put("role", "assistant");
                assistant.putNull("content");
                ObjectNode call = assistant.putArray("tool_calls").addObject();
                call.put("id", callId);
                call.put("type", "function");
                ObjectNode fn = call.putObject("function");
                fn.put("name", ex.name);
                fn.put("arguments", ex.args == null || ex.args.isEmpty() ? "{}" : ex.args);

                ObjectNode toolMsg = messages.addObject();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", callId);
                toolMsg.put("content", ex.result == null ? "" : ex.result);
            }
        }

        if (toolMode != TOOL_OFF) {
            try {
                payload.set("tools", om.readTree(TOOLS_JSON));
                // none = 保留工具定义但禁止模型调用，用于「工具次数用完后强制它作答」
                payload.put("tool_choice", toolMode == TOOL_NONE ? "none" : "auto");
            } catch (Exception ignore) {
                // JSON 常量写错了也不该影响主流程
            }
        }

        String body = postChat(cfg, payload);
        JsonNode root;
        try {
            root = om.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            throw new ResearchException("研究服务返回的内容无法解析。", e);
        }

        JsonNode message = root.path("choices").path(0).path("message");

        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray() && toolCalls.size() > 0) {
            JsonNode call = toolCalls.get(0);
            String fnName = call.path("function").path("name").asText("");
            if (!fnName.isEmpty()) {
                ToolRequest req = new ToolRequest();
                req.id = call.path("id").asText("call_0");
                req.name = fnName;
                req.args = call.path("function").path("arguments").asText("{}");
                ToolTurn turn = new ToolTurn();
                turn.request = req;
                turn.model = cfg.model;
                turn.toolsRejected = rejected;
                return turn;
            }
        }

        // 老式 function_call 也兼容一下
        JsonNode legacy = message.path("function_call");
        if (legacy.isObject() && !legacy.path("name").asText("").isEmpty()) {
            ToolRequest req = new ToolRequest();
            req.id = "call_0";
            req.name = legacy.path("name").asText();
            req.args = legacy.path("arguments").asText("{}");
            ToolTurn turn = new ToolTurn();
            turn.request = req;
            turn.model = cfg.model;
            turn.toolsRejected = rejected;
            return turn;
        }

        String content = extractText(message.path("content"));
        if (content.isEmpty()) {
            throw new ResearchException("研究模型返回了空内容。请确认模型名是否正确，或换一个文本模型。");
        }
        JsonNode json = parseJson(content);
        Result r = toResult(query, json, cfg.model);
        if (r.draft == null || r.draft.name == null || r.draft.name.trim().isEmpty()) {
            throw new ResearchException("研究模型没有返回可用的成分名。");
        }
        ToolTurn turn = new ToolTurn();
        turn.result = r;
        turn.model = cfg.model;
        turn.toolsRejected = rejected;
        return turn;
    }

    /** 管理台批量研究已有成分：结果回到待研究 */
    public BatchResult researchExisting(List<String> names) {
        BatchResult result = new BatchResult();
        List<String> targets = cleanNames(names, 10);
        if (targets.isEmpty()) {
            throw new ResearchException("请选择至少一个成分");
        }
        if (!enabled()) {
            throw new ResearchException("还没有配置研究 API。请到「API 与模型」配置总 API 或研究 API。");
        }

        Map<String, Result> outcomes = runParallel(targets);
        for (String name : targets) {
            Result r = outcomes.get(name);
            if (r == null) {
                result.failed.add(name);
                result.errors.put(name, "研究超时或没有返回");
                continue;
            }
            try {
                ingredientAdmin.updateResearched(name, r.draft, r.confidence, r.evidence, r.model);
                result.researched.add(name);
                recentFailures.remove(IngredientRepository.norm(name));
            } catch (Exception e) {
                result.failed.add(name);
                result.errors.put(name, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 扫描后自动研究未知成分：新建为待研究，成功后返回可重新分析的名称。
     * 失败会缓存 10 分钟，避免每次分析都重复消耗额度。
     */
    public List<String> researchUnknowns(List<String> unknown, String context, int max) {
        if (!enabled() || unknown == null || unknown.isEmpty()) return Collections.emptyList();

        List<String> targets = cleanNames(unknown, Math.max(1, Math.min(max, 5)));
        targets.removeIf(name -> {
            Long until = recentFailures.get(IngredientRepository.norm(name));
            return until != null && until > System.currentTimeMillis();
        });
        if (targets.isEmpty()) return Collections.emptyList();

        Map<String, Result> outcomes = runParallel(targets);
        List<String> done = new ArrayList<>();
        for (String name : targets) {
            Result r = outcomes.get(name);
            if (r == null) {
                recentFailures.put(IngredientRepository.norm(name),
                        System.currentTimeMillis() + 10L * 60 * 1000);
                continue;
            }
            try {
                ingredientAdmin.createResearched(name, r.draft, r.confidence, r.evidence, r.model);
                done.add(name);
                recentFailures.remove(IngredientRepository.norm(name));
                System.out.println("[研究] 自动补全：" + name + " → " + r.draft.name + "（" + r.confidence + "）");
            } catch (Exception e) {
                System.err.println("[研究] 自动补全写入失败 " + name + "：" + e.getMessage());
                recentFailures.put(IngredientRepository.norm(name),
                        System.currentTimeMillis() + 10L * 60 * 1000);
            }
        }
        return done;
    }

    /** 管理台单条研究：更新已有成分，返回可展示的结果 */
    public Map<String, Object> researchAndUpdate(String name) {
        if (!enabled()) {
            throw new ResearchException("还没有配置研究 API。请到「API 与模型」配置总 API 或研究 API。");
        }
        Result r = research(name);
        try {
            ingredientAdmin.updateResearched(name, r.draft, r.confidence, r.evidence, r.model);
        } catch (Exception e) {
            throw new ResearchException("研究成功，但写回成分库失败：" + e.getMessage());
        }
        return resultView(name, r);
    }

    /** 管理台「研究测试」：不写库，直接把模型结构化结果返回 */
    public Map<String, Object> test(String name) {
        Result r = research(name);
        return resultView(name, r);
    }

    // ---------------------------------------------------------------- 模型调用

    private Result research(String query, String context, VisionSettings cfg) {
        ObjectNode payload = om.createObjectNode();
        payload.put("model", cfg.model);
        payload.put("temperature", 0.1);
        payload.put("max_tokens", 900);

        ArrayNode messages = payload.putArray("messages");
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", SYSTEM_PROMPT);

        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userPrompt(query, context));

        String body = postChat(cfg, payload);
        JsonNode root;
        try {
            root = om.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            throw new ResearchException("研究服务返回的内容无法解析。", e);
        }

        String content = extractText(root.path("choices").path(0).path("message").path("content"));
        if (content.isEmpty()) {
            throw new ResearchException("研究模型返回了空内容。请确认模型名是否正确，或换一个文本模型。");
        }

        JsonNode json = parseJson(content);
        Result r = toResult(query, json, cfg.model);
        // 让失败信息更明确：字段完全为空时视为研究失败
        if (r.draft == null || r.draft.name == null || r.draft.name.trim().isEmpty()) {
            throw new ResearchException("研究模型没有返回可用的成分名。");
        }
        return r;
    }

    private String userPrompt(String query, String context) {
        String ctx = context == null ? "" : context.trim();
        if (ctx.length() > 600) ctx = ctx.substring(0, 600);
        return "请研究这个化妆品成分：" + query + "\n"
                + (ctx.isEmpty() ? "" : "它出现在这份成分表中（仅供上下文，不要研究其他成分）：" + ctx + "\n")
                + "只输出一个 JSON 对象，字段如下：\n"
                + "{\n"
                + "  \"name\": \"标准中文名（最多40字）\",\n"
                + "  \"aliases\": [\"INCI名\", \"常见别名\"],\n"
                + "  \"role\": \"基质|功效|防腐|香精|防晒|表活\",\n"
                + "  \"cat\": \"分类，如保湿/美白/防腐/溶剂\",\n"
                + "  \"function\": \"一句话作用，最多200字\",\n"
                + "  \"description\": \"2–4句、80–220字的深度描述：它是什么、在配方里的实际作用、常见搭配/体系、适合或不适合谁、常见宣传误区或使用注意；不要广告腔，不要编造限量\",\n"
                + "  \"safety\": \"low|medium|high\",\n"
                + "  \"comedo\": 0,\n"
                + "  \"irrit\": \"low|medium|high\",\n"
                + "  \"preg\": \"safe|caution|avoid\",\n"
                + "  \"sensitive\": \"safe|caution|avoid\",\n"
                + "  \"limit\": \"法规限量；不确定必须留空\",\n"
                + "  \"note\": \"一句使用提示，最多200字\",\n"
                + "  \"confidence\": 0.0,\n"
                + "  \"evidence\": \"依据来源或推断理由，最多300字\"\n"
                + "}\n"
                + "规则：不要编造法规限量；不确定的字段用空字符串或保守值；confidence 0–1；只输出 JSON。";
    }

    private String postChat(VisionSettings cfg, ObjectNode payload) {
        String json;
        try {
            json = om.writeValueAsString(payload);
        } catch (Exception e) {
            throw new ResearchException("构造研究请求失败", e);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + cfg.apiKey);

        String[] urls = { cfg.baseUrl + "/chat/completions", cfg.baseUrl + "/v1/chat/completions" };
        for (int i = 0; i < urls.length; i++) {
            try {
                return rt.postForObject(urls[i], new HttpEntity<>(json, headers), String.class);
            } catch (HttpStatusCodeException e) {
                int status = e.getStatusCode().value();
                String body = e.getResponseBodyAsString();
                boolean modelMissing = body.contains("model_not_found") || body.contains("No available channel");
                if (status == 404 && i == 0 && !modelMissing) continue;

                System.err.println("[研究] 上游返回 " + status + "：" + body);
                throw new ResearchException(friendlyMessage(status, body, cfg.model), e);
            } catch (ResourceAccessException e) {
                String detail = (String.valueOf(e.getMessage()) + " "
                        + (e.getCause() == null ? "" : String.valueOf(e.getCause().getMessage()))).toLowerCase(Locale.ROOT);
                if (detail.contains("read timed out") || detail.contains("readtimeout")) {
                    throw new ResearchException("研究模型超过 30 秒没有返回，已记为失败；可稍后重试，或换一个更快的文本模型。", e);
                }
                throw new ResearchException("连不上研究模型服务，请检查网络、总 API 或研究 API 地址。", e);
            }
        }
        throw new ResearchException("服务地址下没有找到对话接口，请确认 Base URL。");
    }

    private Result toResult(String query, JsonNode json, String model) {
        IngredientAdminService.Draft d = new IngredientAdminService.Draft();
        String name = json.path("name").asText(query).trim();
        if (name.isEmpty()) name = query;
        if (name.length() > 40) name = name.substring(0, 40);
        d.name = name;

        List<String> aliases = new ArrayList<>();
        JsonNode aliasNode = json.path("aliases");
        if (aliasNode.isArray()) {
            for (JsonNode a : aliasNode) {
                String alias = a.asText("").trim();
                if (!alias.isEmpty()) aliases.add(alias);
            }
        } else if (aliasNode.isTextual()) {
            for (String a : aliasNode.asText("").split("[,，、]")) {
                if (!a.trim().isEmpty()) aliases.add(a.trim());
            }
        }
        if (!query.trim().isEmpty()
                && !IngredientRepository.norm(query).equals(IngredientRepository.norm(name))) {
            aliases.add(query.trim());
        }
        d.aliases = aliases;

        String role = json.path("role").asText("").trim();
        d.role = ROLES.contains(role) ? role : "基质";
        d.cat = cutoff(json.path("cat").asText("其他").trim(), 20, "其他");
        d.function = cutoff(json.path("function").asText("AI 研究结果，待人工复核").trim(), 200, "AI 研究结果，待人工复核");
        d.description = cutoff(json.path("description").asText("").trim(), 600, "");
        d.safety = RISK.contains(json.path("safety").asText("")) ? json.path("safety").asText("") : "low";
        d.comedo = Math.max(0, Math.min(5, json.path("comedo").asInt(0)));
        d.irrit = RISK.contains(json.path("irrit").asText("")) ? json.path("irrit").asText("") : "low";
        d.preg = PROFILE.contains(json.path("preg").asText("")) ? json.path("preg").asText("") : "safe";
        d.sensitive = PROFILE.contains(json.path("sensitive").asText("")) ? json.path("sensitive").asText("") : "safe";
        d.limit = cutoff(json.path("limit").asText("").trim(), 200, "");
        d.note = cutoff(json.path("note").asText("").trim(), 200, "");

        Result r = new Result();
        r.draft = d;
        r.confidence = clamp(json.path("confidence").asDouble(0.5));
        r.evidence = cutoff(json.path("evidence").asText("").trim(), 300, "");
        r.model = model;
        return r;
    }

    private JsonNode parseJson(String content) {
        String s = content.trim();
        if (s.startsWith("```")) {
            int line = s.indexOf('\n');
            if (line > 0) s = s.substring(line + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        int left = s.indexOf('{');
        int right = s.lastIndexOf('}');
        if (left < 0 || right <= left) {
            throw new ResearchException("研究模型没有返回 JSON。请换一个支持 JSON 输出的文本模型。");
        }
        try {
            return om.readTree(s.substring(left, right + 1));
        } catch (Exception e) {
            throw new ResearchException("研究模型返回的 JSON 无法解析。", e);
        }
    }

    private Map<String, Result> runParallel(List<String> names) {
        Map<String, CompletableFuture<Result>> futures = new LinkedHashMap<>();
        for (String name : names) {
            futures.put(name, CompletableFuture.supplyAsync(() -> research(name), pool));
        }
        try {
            CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                    .get(32, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 超时也保留已经完成的结果
        }

        Map<String, Result> out = new LinkedHashMap<>();
        for (Map.Entry<String, CompletableFuture<Result>> e : futures.entrySet()) {
            CompletableFuture<Result> f = e.getValue();
            if (f.isDone() && !f.isCompletedExceptionally() && !f.isCancelled()) {
                Result r = f.getNow(null);
                if (r != null) out.put(e.getKey(), r);
            }
        }
        return out;
    }

    private List<String> cleanNames(List<String> names, int max) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (names != null) {
            for (String raw : names) {
                String name = raw == null ? "" : raw.trim();
                if (name.isEmpty() || name.length() > 80) continue;
                set.add(name);
                if (set.size() >= max) break;
            }
        }
        return new ArrayList<>(set);
    }

    private Map<String, Object> resultView(String query, Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("query", query);
        out.put("confidence", r.confidence);
        out.put("evidence", r.evidence);
        out.put("model", r.model);
        out.put("draft", r.draft);
        return out;
    }

    private String extractText(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) return "";
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (part.isTextual()) sb.append(part.asText());
                else if (part.has("text")) sb.append(part.path("text").asText(""));
            }
            return sb.toString().trim();
        }
        return content.asText("").trim();
    }

    private String friendlyMessage(int status, String body, String model) {
        String b = body == null ? "" : body;
        String m = model == null || model.trim().isEmpty() ? "当前模型" : model;
        if (b.contains("model_not_found") || b.contains("No available channel") || b.contains("model not found")) {
            return "研究模型「" + m + "」在当前服务不可用，请检查总 API / 研究 API 的模型名与分组权限。";
        }
        if (status == 401 || status == 403) return "研究 API 的 Key 无效或没有权限。";
        if (status == 429) return "研究 API 的额度或频率已用完，请稍后再试。";
        if (status == 400 || status == 422) return "研究服务不接受这个请求，请检查模型是否支持对话接口。";
        return "研究服务暂时不可用（" + status + "），请稍后重试。";
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private String cutoff(String value, int max, String fallback) {
        String s = value == null ? "" : value.trim();
        if (s.isEmpty()) return fallback;
        return s.length() > max ? s.substring(0, max) : s;
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }
}
