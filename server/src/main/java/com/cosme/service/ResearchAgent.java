package com.cosme.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 未收录成分的研究 Agent。
 *
 * 一次 run() 就是一次「计划 → 调工具 → 看结果 → 决策」的循环：
 *   1. normalize      归一化查询（括号、序号、全角）
 *   2. search_library 先查成分库，命中就直接结束，不浪费一次模型调用
 *   3. ask_model      让模型按 Schema 补全；模型可以先用 Function Calling 请求查库 / 查限量
 *   4. infer_role     模型没给角色时，用词根规则兜一个（paraben→防腐、sulfate→表活…）
 *   5. validate       字段校验；不合法就换一种写法重问一次
 *   6. 置信度门槛     低于阈值不自动入库，转人工复核
 *
 * 设计原则：Agent 可以自己决定「要不要调模型」「要不要重试」「能不能入库」，
 * 模型也可以决定「要不要先查一次工具」；但每一步都写进 trace，管理台可逐步回看，
 * 需要人判断的那一步永远留给管理员。
 */
@Service
public class ResearchAgent {

    public static final String LIBRARY_HIT = "library_hit";
    public static final String DRAFT_READY = "draft_ready";
    public static final String NEEDS_REVIEW = "needs_review";
    public static final String FAILED = "failed";

    /** 低于这个置信度不自动入库，交给人看。 */
    public static final double MIN_CONFIDENCE = 0.7;

    /** 模型连续要求调用工具的次数上限，防止死循环。 */
    private static final int MAX_TOOL_ROUNDS = 4;

    private static final Set<String> ROLES = new HashSet<>(Arrays.asList(
            "功效", "防腐", "香精", "防晒", "表活", "基质"));

    private final IngredientRepository repository;
    private final Model model;
    private final ObjectMapper om = new ObjectMapper();

    /** 模型这一层抽象出来专门为了测试：生产走 ResearchService，测试塞假函数。 */
    public interface Model {
        ResearchService.ToolTurn turn(String query, String context,
                                      List<ResearchService.ToolExchange> exchanges, int toolMode);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ResearchAgent(IngredientRepository repository, ResearchService research) {
        this(repository, research::turn);
    }

    ResearchAgent(IngredientRepository repository, Model model) {
        this.repository = repository;
        this.model = model;
    }

    public Run run(String query, String context) {
        long started = System.currentTimeMillis();
        Run run = new Run();
        run.query = query == null ? "" : query.trim();
        run.plan = Arrays.asList(
                "归一化查询",
                "查成分库（命中即结束）",
                "调用模型（可先用工具查库 / 查限量）",
                "校验字段并按需补全角色",
                "置信度门槛 → 入库或转人工");

        // 1. 归一化
        long t = System.currentTimeMillis();
        String normalized = normalize(run.query);
        run.normalized = normalized;
        mark(run, "normalize", run.query,
                normalized.equals(run.query) ? "无需改动" : "归一为「" + normalized + "」", t, "ok");

        // 2. 先查库：库里有的成分不需要研究
        t = System.currentTimeMillis();
        JsonNode hit = repository.exact(normalized);
        if (hit == null) hit = repository.exact(run.query);
        if (hit != null) {
            mark(run, "search_library", normalized, "命中「" + hit.path("n").asText() + "」", t, "ok");
            run.outcome = LIBRARY_HIT;
            run.name = hit.path("n").asText();
            run.reason = "成分库已有该成分，无需研究";
            return done(run, started);
        }
        mark(run, "search_library", normalized, "库内未收录", t, "miss");

        // 3. 依次尝试两种写法：归一后的、原始的
        List<String> candidates = new ArrayList<>(new LinkedHashSet<>(Arrays.asList(normalized, run.query)));
        String lastProblem = "";
        ResearchService.Result last = null;
        for (int i = 0; i < candidates.size() && i < 2; i += 1) {
            String candidate = candidates.get(i);
            ResearchService.Result result = ask(run, candidate, context);
            if (run.outcome != null && FAILED.equals(run.outcome)) return done(run, started);
            if (result == null) break;

            long tv = System.currentTimeMillis();
            String problem = validate(result);

            // 角色缺失时，先用词根规则兜一个，再校验一次
            if (problem != null && problem.startsWith("缺少角色")
                    && result.draft != null) {
                long ti = System.currentTimeMillis();
                String guess = inferRole(candidate);
                if (guess != null) {
                    result.draft.role = guess;
                    mark(run, "infer_role", candidate, "按词根推断为「" + guess + "」", ti, "ok");
                    problem = validate(result);
                } else {
                    mark(run, "infer_role", candidate, "词根无法判断", ti, "miss");
                }
            }

            if (problem == null) {
                mark(run, "validate", candidate,
                        "字段齐全，角色「" + result.draft.role + "」", tv, "ok");
                last = result;
                lastProblem = "";
                break;
            }

            mark(run, "validate", candidate, problem, tv, "error");
            lastProblem = problem;
            last = result;
        }

        if (last == null || last.draft == null) {
            run.outcome = FAILED;
            run.reason = run.reason == null ? "模型没有返回可用的草稿" : run.reason;
            return done(run, started);
        }
        if (!lastProblem.isEmpty()) {
            run.outcome = NEEDS_REVIEW;
            run.reason = "草稿字段不合法：" + lastProblem;
            run.draft = last.draft;
            run.confidence = last.confidence;
            run.model = last.model;
            run.evidence = last.evidence;
            return done(run, started);
        }

        // 4. 字段已通过校验：这份草稿可以被写进成分库（是否自动写由调用方决定）
        run.committable = true;

        // 5. 置信度门槛：够高才自动入库
        double confidence = last.confidence;
        run.draft = last.draft;
        run.confidence = confidence;
        run.model = last.model;
        run.evidence = last.evidence;
        run.name = last.draft.name;

        if (confidence < MIN_CONFIDENCE) {
            run.outcome = NEEDS_REVIEW;
            run.reason = "模型置信度 " + fmt(confidence) + " 低于自动入库阈值 " + fmt(MIN_CONFIDENCE);
            return done(run, started);
        }

        run.outcome = DRAFT_READY;
        run.reason = "草稿字段齐全，置信度 " + fmt(confidence) + "，可自动入库";
        return done(run, started);
    }

    // ------------------------------------------------------------------ 模型 + 工具循环

    /**
     * 调一次模型；如果它要求调用工具，就先执行工具、把结果回灌，再调一次。
     *
     * 工具轮次有上限（4 轮）；到上限后不再失败，而是「关掉工具、要求它直接作答」——
     * 模型通常已经查够了，只是还想再确认一次。真的关掉工具还要调工具才判失败。
     */
    private ResearchService.Result ask(Run run, String query, String context) {
        List<ResearchService.ToolExchange> exchanges = new ArrayList<>();
        boolean forcedFinal = false;
        int rounds = 0;

        while (true) {
            long t = System.currentTimeMillis();
            run.modelCalls += 1;
            ResearchService.ToolTurn turn;
            try {
                int mode = ResearchService.TOOL_AUTO;
                if (forcedFinal) mode = ResearchService.TOOL_NONE;
                turn = model.turn(query, context, exchanges, mode);
            } catch (Exception e) {
                mark(run, "ask_model", query, shorten(e.getMessage()), t, "error");
                run.outcome = FAILED;
                run.reason = "模型调用失败：" + shorten(e.getMessage());
                return null;
            }

            if (turn != null && turn.toolsRejected) {
                mark(run, "ask_model", query, "服务商不支持工具调用，退回确定性计划", t, "miss");
                forcedFinal = true;
                continue;
            }

            if (turn != null && turn.request != null) {
                ResearchService.ToolRequest req = turn.request;

                if (forcedFinal) {
                    mark(run, "tool_call", req.name, "已关闭工具仍请求工具，停止", t, "error");
                    run.outcome = FAILED;
                    run.reason = "模型在无工具模式下仍请求调用工具";
                    return null;
                }

                if (rounds >= MAX_TOOL_ROUNDS) {
                    mark(run, "tool_call", req.name,
                            "达到工具调用上限（" + MAX_TOOL_ROUNDS + " 次），改为直接作答", t, "miss");
                    forcedFinal = true;
                    continue;
                }

                mark(run, "tool_call", query, "模型请求 " + req.name + "(" + clip(req.args, 60) + ")", t, "ok");
                rounds += 1;
                long tx = System.currentTimeMillis();
                String observation = executeTool(req.name, req.args);
                mark(run, "tool:" + req.name, req.args, observation, tx, "ok");
                exchanges.add(new ResearchService.ToolExchange(req.name, req.args, observation));
                continue;
            }

            ResearchService.Result result = turn == null ? null : turn.result;
            if (result == null || result.draft == null) {
                mark(run, "ask_model", query, "模型没有返回草稿", t, "error");
                run.outcome = FAILED;
                run.reason = "模型没有返回可用的草稿";
                return null;
            }
            mark(run, "ask_model", query,
                    "草稿「" + safe(result.draft.name) + "」· 置信度 " + fmt(result.confidence), t, "ok");
            return result;
        }
    }

    /** 三个只读工具的执行：模型只能查，不能改任何东西。 */
    private String executeTool(String name, String argsJson) {
        String arg = argOf(argsJson, "name");
        if (arg.isEmpty()) arg = argOf(argsJson, "role");
        switch (name == null ? "" : name) {
            case "search_library": {
                JsonNode hit = repository.exact(arg);
                if (hit == null) return "未收录：" + arg;
                return "已收录：标准名「" + hit.path("n").asText() + "」，角色「" + hit.path("r").asText("") + "」";
            }
            case "lookup_limit": {
                JsonNode hit = repository.exact(arg);
                if (hit == null) return "未收录：" + arg;
                String limit = hit.path("lm").asText("");
                if (limit == null || limit.trim().isEmpty()) return "「" + arg + "」没有限量记录";
                return "「" + arg + "」限量：" + limit;
            }
            case "list_role_examples": {
                String role = argOf(argsJson, "role");
                List<String> names = new ArrayList<>();
                for (JsonNode node : repository.all()) {
                    if (role.equals(node.path("r").asText(""))) {
                        names.add(node.path("n").asText(""));
                        if (names.size() >= 5) break;
                    }
                }
                return names.isEmpty() ? "该角色下没有示例" : "「" + role + "」示例：" + String.join("、", names);
            }
            default:
                return "未知工具：" + name;
        }
    }

    private String argOf(String argsJson, String field) {
        if (argsJson == null || argsJson.trim().isEmpty()) return "";
        try {
            JsonNode node = om.readTree(argsJson);
            return node.path(field).asText("").trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** 词根规则：只在模型没给角色时兜底，判断不了就返回 null。 */
    static String inferRole(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.contains("paraben") || lower.contains("羟苯")) return "防腐";
        if (lower.contains("fragrance") || lower.contains("parfum") || lower.contains("香精")) return "香精";
        if (lower.contains("sulfate") || lower.contains("sls") || lower.contains("甜菜碱")) return "表活";
        if (lower.contains("oxide") || lower.contains("二氧化钛") || lower.contains("氧化锌")) return "防晒";
        if (lower.equals("water") || lower.equals("aqua") || lower.contains("去离子水")) return "基质";
        return null;
    }

    private String validate(ResearchService.Result result) {
        if (result == null || result.draft == null) return "没有草稿";
        IngredientAdminService.Draft d = result.draft;
        if (d.name == null || d.name.trim().isEmpty()) return "缺少名称";
        if (d.name.trim().length() > 40) return "名称过长";
        if (d.role == null || d.role.trim().isEmpty()) return "缺少角色";
        if (!ROLES.contains(d.role.trim())) return "角色「" + d.role + "」不在允许范围内";
        if (d.function == null || d.function.trim().isEmpty()) return "缺少一句话作用";
        if (result.confidence < 0 || result.confidence > 1) return "置信度超出 0–1";
        return null;
    }

    // ------------------------------------------------------------------ 工具方法

    /** 去掉括号注释、序号与前导符号；全角括号统一成半角。 */
    static String normalize(String value) {
        if (value == null) return "";
        String s = value.trim();
        s = s.replace('（', '(').replace('）', ')').replace('【', '[').replace('】', ']');
        s = s.replaceAll("[\\(\\[][^\\)\\]]*[\\)\\]]", "");
        s = s.replaceAll("^[0-9０-９]+[.、．)）\\s]+", "");
        s = s.replaceAll("^[①-⑳]", "");
        return s.trim();
    }

    private Run done(Run run, long started) {
        run.elapsedMs = System.currentTimeMillis() - started;
        return run;
    }

    private void mark(Run run, String tool, String args, String result, long start, String status) {
        Step step = new Step();
        step.n = run.trace.size() + 1;
        step.tool = tool;
        step.args = clip(args, 60);
        step.result = clip(result, 160);
        step.ms = Math.max(0, System.currentTimeMillis() - start);
        step.status = status;
        run.trace.add(step);
    }

    private static String clip(String value, int max) {
        String s = value == null ? "" : value.trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String shorten(String message) {
        return clip(message == null ? "未知错误" : message, 160);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    // ------------------------------------------------------------------ 数据结构

    public static class Run {
        public String query;
        public String normalized;
        public String outcome;
        public String name;
        public String reason;
        public String model;
        public String evidence;
        public Double confidence;
        public IngredientAdminService.Draft draft;
        public List<String> plan = new ArrayList<>();
        public List<Step> trace = new ArrayList<>();
        public int modelCalls;
        public long elapsedMs;
        /** 字段校验通过、可以写库（置信度高低另行判断） */
        public boolean committable;
    }

    public static class Step {
        public int n;
        public String tool;
        public String args;
        public String result;
        public String status;
        public long ms;

        public Step() {
        }
    }
}
