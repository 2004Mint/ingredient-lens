package com.cosme.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 研究 Agent 的决策逻辑回归测试。
 *
 * 锁住这几件事：
 * 1. 库里已有的成分不调模型（省一次钱，也避免模型把已有条目改坏）；
 * 2. 归一化能把「（日用）香精」这种写法还原成「香精」；
 * 3. 模型请求工具时，Agent 会真的去执行并把结果回灌给它；
 * 4. 字段不合法时先换一种写法重试，仍不合法才转人工；
 * 5. 置信度低于阈值不进库，转人工 —— 这是人机协同的那道闸门；
 * 6. 工具调用有上限，模型绕圈不会把服务拖死。
 *
 * 模型用一个假函数替代，不联网、不需要 Key：
 *   mvn -q test -Dtest=ResearchAgentTest
 */
class ResearchAgentTest {

    private static IngredientRepository repository;
    private static Path testDataDir;

    @BeforeAll
    static void setUp() throws Exception {
        testDataDir = Files.createTempDirectory("cosme-agent-test-");
        System.setProperty("cosme.data.dir", testDataDir.toString());
        repository = new IngredientRepository();
        repository.load();
    }

    @AfterAll
    static void tearDown() throws Exception {
        System.clearProperty("cosme.data.dir");
        if (testDataDir != null) Files.deleteIfExists(testDataDir);
    }

    // ---------------------------------------------------------------- 构造假模型

    private static ResearchService.Result result(String name, String role, String function, double confidence) {
        IngredientAdminService.Draft draft = new IngredientAdminService.Draft();
        draft.name = name;
        draft.role = role;
        draft.function = function;
        ResearchService.Result r = new ResearchService.Result();
        r.draft = draft;
        r.confidence = confidence;
        r.model = "stub-model";
        r.evidence = "测试用";
        return r;
    }

    private static ResearchService.ToolTurn draftTurn(ResearchService.Result r) {
        ResearchService.ToolTurn t = new ResearchService.ToolTurn();
        t.result = r;
        t.model = "stub-model";
        return t;
    }

    private static ResearchService.ToolTurn toolTurn(String name, String args, String id) {
        ResearchService.ToolRequest req = new ResearchService.ToolRequest();
        req.name = name;
        req.args = args;
        req.id = id;
        ResearchService.ToolTurn t = new ResearchService.ToolTurn();
        t.request = req;
        t.model = "stub-model";
        return t;
    }

    @Test
    @DisplayName("库里已有的成分：直接命中，一次模型都不调")
    void libraryHitSkipsModel() {
        AtomicInteger calls = new AtomicInteger();
        ResearchAgent agent = new ResearchAgent(repository, (q, ctx, ex, toolMode) -> {
            calls.incrementAndGet();
            return draftTurn(result("不该被调用", "功效", "测试", 0.99));
        });

        ResearchAgent.Run run = agent.run("烟酰胺", "水、烟酰胺、香精");

        assertEquals(ResearchAgent.LIBRARY_HIT, run.outcome);
        assertEquals("烟酰胺", run.name);
        assertEquals(0, calls.get(), "命中成分库时不应调用模型");
        assertTrue(run.trace.stream().anyMatch(s -> "search_library".equals(s.tool) && "ok".equals(s.status)));
    }

    @Test
    @DisplayName("归一化：括号注释被去掉后能命中已有成分")
    void normalizeStripsParenthetical() {
        assertEquals("香精", ResearchAgent.normalize("（日用）香精"));
        assertEquals("甘油", ResearchAgent.normalize("1. 甘油"));
        assertEquals("烟酰胺", ResearchAgent.normalize("①烟酰胺"));

        ResearchAgent agent = new ResearchAgent(repository, (q, ctx, ex, toolMode) ->
                draftTurn(result("x", "功效", "y", 0.9)));
        ResearchAgent.Run run = agent.run("（日用）香精", "（日用）香精");
        assertEquals(ResearchAgent.LIBRARY_HIT, run.outcome, "归一后应命中「香精」");
    }

    @Test
    @DisplayName("字段齐全且置信度够高：草稿可入库")
    void draftReadyWhenConfident() {
        ResearchAgent agent = new ResearchAgent(repository, (q, ctx, ex, toolMode) ->
                draftTurn(result("测试新成分", "功效", "一句话作用", 0.92)));

        ResearchAgent.Run run = agent.run("测试新成分XYZ", "水、测试新成分XYZ");

        assertEquals(ResearchAgent.DRAFT_READY, run.outcome);
        assertEquals("测试新成分", run.name);
        assertEquals(0.92, run.confidence, 0.001);
        assertTrue(run.trace.size() >= 4, "轨迹至少包含 归一/查库/问模型/校验");
        assertTrue(run.trace.stream().anyMatch(s -> "validate".equals(s.tool) && "ok".equals(s.status)));
    }

    @Test
    @DisplayName("工具调用：模型先查限量，Agent 执行后回灌，再拿到草稿")
    void executesToolAndFeedsBack() {
        List<Integer> exchangeSeen = new ArrayList<>();
        ResearchAgent agent = new ResearchAgent(repository, (q, ctx, ex, toolMode) -> {
            exchangeSeen.add(ex == null ? 0 : ex.size());
            if (ex == null || ex.isEmpty()) {
                return toolTurn("lookup_limit", "{\"name\":\"烟酰胺\"}", "call_1");
            }
            return draftTurn(result("工具测试成分", "功效", "一句话作用", 0.9));
        });

        ResearchAgent.Run run = agent.run("工具测试成分", "");

        assertEquals(ResearchAgent.DRAFT_READY, run.outcome);
        assertEquals(2, run.modelCalls, "第一次请求工具，第二次给草稿");
        assertEquals(0, exchangeSeen.get(0));
        assertEquals(1, exchangeSeen.get(1), "第二次调用应带上工具结果");
        assertTrue(run.trace.stream().anyMatch(s -> "tool_call".equals(s.tool)));
        assertTrue(run.trace.stream().anyMatch(s -> "tool:lookup_limit".equals(s.tool)));
    }

    @Test
    @DisplayName("未知工具：Agent 回一句「未知工具」，仍继续把活干完")
    void unknownToolDoesNotCrash() {
        ResearchAgent agent = new ResearchAgent(repository, (q, ctx, ex, toolMode) -> {
            if (ex == null || ex.isEmpty()) {
                return toolTurn("delete_everything", "{}", "call_1");
            }
            return draftTurn(result("未知工具成分", "功效", "一句话作用", 0.88));
        });

        ResearchAgent.Run run = agent.run("未知工具成分", "");

        assertEquals(ResearchAgent.DRAFT_READY, run.outcome);
        assertTrue(run.trace.stream()
                .anyMatch(s -> "tool:delete_everything".equals(s.tool) && s.result.contains("未知工具")));
    }

    @Test
    @DisplayName("模型不停要工具：达到上限后关掉工具要求直接作答，仍要就判失败")
    void stopsAfterToolLimit() {
        AtomicInteger calls = new AtomicInteger();
        ResearchAgent agent = new ResearchAgent(repository, (q, ctx, ex, toolMode) -> {
            calls.incrementAndGet();
            return toolTurn("search_library", "{\"name\":\"烟酰胺\"}", "call_" + calls.get());
        });

        ResearchAgent.Run run = agent.run("绕圈成分", "");

        assertEquals(ResearchAgent.FAILED, run.outcome);
        assertTrue(run.reason.contains("工具"), run.reason);
        assertTrue(calls.get() >= 6, "4 轮工具 + 一次强制作答 + 一次仍要工具，实际 " + calls.get());
        assertTrue(run.trace.stream()
                .anyMatch(s -> s.result != null && s.result.contains("达到工具调用上限")));
    }

    @Test
    @DisplayName("工具用满后关掉工具，模型给出草稿就正常收尾")
    void finishesAfterToolCap() {
        AtomicInteger calls = new AtomicInteger();
        ResearchAgent agent = new ResearchAgent(repository, (q, ctx, ex, toolMode) -> {
            int n = calls.incrementAndGet();
            if (n <= 4) return toolTurn("search_library", "{\"name\":\"烟酰胺\"}", "call_" + n);
            return draftTurn(result("限额后成分", "功效", "一句话作用", 0.9));
        });

        ResearchAgent.Run run = agent.run("限额后成分", "");

        assertEquals(ResearchAgent.DRAFT_READY, run.outcome);
        assertEquals(5, calls.get());
    }

    @Test
    @DisplayName("服务商不支持 tools：自动退回不带工具的调用")
    void fallsBackWhenToolsRejected() {
        AtomicInteger calls = new AtomicInteger();
        ResearchAgent agent = new ResearchAgent(repository, (q, ctx, ex, toolMode) -> {
            calls.incrementAndGet();
            if (toolMode == ResearchService.TOOL_AUTO) {
                ResearchService.ToolTurn rejected = new ResearchService.ToolTurn();
                rejected.toolsRejected = true;
                return rejected;
            }
            return draftTurn(result("降级成分", "功效", "一句话作用", 0.9));
        });

        ResearchAgent.Run run = agent.run("降级成分", "");

        assertEquals(ResearchAgent.DRAFT_READY, run.outcome);
        assertEquals(2, calls.get());
        assertTrue(run.trace.stream()
                .anyMatch(s -> s.result != null && s.result.contains("退回确定性计划")));
    }

    @Test
    @DisplayName("置信度不足：不自动入库，转人工")
    void lowConfidenceGoesToReview() {
        ResearchAgent agent = new ResearchAgent(repository, (q, ctx, ex, toolMode) ->
                draftTurn(result("低置信成分", "功效", "一句话作用", 0.4)));

        ResearchAgent.Run run = agent.run("低置信成分ABC", "");

        assertEquals(ResearchAgent.NEEDS_REVIEW, run.outcome);
        assertTrue(run.reason.contains("置信度"), "原因应说明置信度不足：" + run.reason);
        assertNotNull(run.draft, "转人工时草稿要保留，管理员才能判断");
    }

    @Test
    @DisplayName("角色缺失：先按词根推断，再校验通过")
    void inferRoleFillsMissingRole() {
        ResearchAgent agent = new ResearchAgent(repository, (q, ctx, ex, toolMode) ->
                draftTurn(result("Sodium Coco-Sulfate", "", "温和表活，起泡与清洁", 0.85)));

        ResearchAgent.Run run = agent.run("Sodium Coco-Sulfate", "水、Sodium Coco-Sulfate");

        assertEquals(ResearchAgent.DRAFT_READY, run.outcome);
        assertEquals("表活", run.draft.role);
        assertTrue(run.trace.stream().anyMatch(s -> "infer_role".equals(s.tool) && "ok".equals(s.status)));
    }

    @Test
    @DisplayName("字段始终不合法：换写法重试一次，仍失败则转人工")
    void invalidDraftRetriesOnceThenReview() {
        AtomicInteger calls = new AtomicInteger();
        ResearchAgent agent = new ResearchAgent(repository, (q, ctx, ex, toolMode) -> {
            calls.incrementAndGet();
            return draftTurn(result("某成分", "不存在的角色", "一句话作用", 0.9));
        });

        ResearchAgent.Run run = agent.run("（日用）某成分XYZ", "");

        assertEquals(ResearchAgent.NEEDS_REVIEW, run.outcome);
        assertEquals(2, calls.get(), "归一化写法与原始写法各问一次");
        assertTrue(run.reason.contains("角色"), run.reason);
    }

    @Test
    @DisplayName("模型抛异常：整条任务记失败，轨迹里留下错误")
    void modelFailureIsRecorded() {
        ResearchAgent agent = new ResearchAgent(repository, (q, ctx, ex, toolMode) -> {
            throw new IllegalStateException("上游 502");
        });

        ResearchAgent.Run run = agent.run("异常成分", "");

        assertEquals(ResearchAgent.FAILED, run.outcome);
        assertTrue(run.reason.contains("502"), run.reason);
        assertTrue(run.trace.stream().anyMatch(s -> "ask_model".equals(s.tool) && "error".equals(s.status)));
    }

    @Test
    @DisplayName("词根规则：认得的给角色，认不得的返回 null")
    void inferRoleRules() {
        assertEquals("防腐", ResearchAgent.inferRole("Propylparaben"));
        assertEquals("香精", ResearchAgent.inferRole("Fragrance"));
        assertEquals("表活", ResearchAgent.inferRole("Sodium Laureth Sulfate"));
        assertEquals("防晒", ResearchAgent.inferRole("Titanium Dioxide"));
        assertNull(ResearchAgent.inferRole("某种没听过的提取物"));
    }
}
