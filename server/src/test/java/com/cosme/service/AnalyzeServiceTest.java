package com.cosme.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 规则引擎的回归测试。
 * 锁住三件最容易被后续改动破坏的事：
 * 1. 概念添加的排位判断；2. 孕期禁忌；3. 未收录成分仍然占位；4. 中文名不做模糊匹配。
 */
class AnalyzeServiceTest {

    private static IngredientRepository repository;
    private static AnalyzeService service;
    private static Path testDataDir;

    @BeforeAll
    static void setUp() throws Exception {
        // 用临时 data 目录，避免本机管理台编辑过的 data/ingredients.json 影响回归结果
        testDataDir = Files.createTempDirectory("cosme-test-");
        System.setProperty("cosme.data.dir", testDataDir.toString());
        repository = new IngredientRepository();
        repository.load();
        service = new AnalyzeService(repository);
    }

    @AfterAll
    static void tearDown() throws Exception {
        System.clearProperty("cosme.data.dir");
        if (testDataDir != null) {
            Files.deleteIfExists(testDataDir);
        }
    }

    @Test
    void marksLateActiveAsConceptAddition() {
        String text = "水、甘油、丙二醇、丁二醇、1,2-戊二醇、透明质酸钠、泛醇、卡波姆、氢氧化钠、"
                + "苯氧乙醇、羟苯甲酯、烟酰胺、香精、柠檬烯";
        Map<String, Object> report = service.analyze(text, profile("oily", true, false));

        assertEquals(14, report.get("total"));
        assertEquals(13, report.get("matched"));

        List<Map<String, Object>> actives = list(report.get("actives"));
        Map<String, Object> niacinamide = actives.stream()
                .filter(a -> "烟酰胺".equals(a.get("name")))
                .findFirst().orElseThrow();
        assertEquals("tail", niacinamide.get("tier"));
        assertEquals(11, niacinamide.get("index"));
    }

    @Test
    void keepsUnknownIngredientSlotInSequence() {
        String text = "水、甘油、丙二醇、丁二醇、1,2-戊二醇、透明质酸钠";
        Map<String, Object> report = service.analyze(text, profile("oily", true, false));

        List<Map<String, Object>> sequence = list(report.get("sequence"));
        assertEquals(6, sequence.size());
        assertEquals(4, sequence.get(4).get("index"));
        assertEquals(false, sequence.get(4).get("known"));
        assertEquals("1,2-戊二醇", sequence.get(4).get("name"));
    }

    @Test
    void pregnancyAddsHighRiskForRetinol() {
        Map<String, Object> report = service.analyze("水、甘油、视黄醇", profile("normal", false, true));

        List<Map<String, Object>> flags = list(report.get("flags"));
        boolean found = flags.stream().anyMatch(f ->
                "high".equals(f.get("level"))
                        && "孕期".equals(f.get("type"))
                        && list(f.get("ingredients")).contains("视黄醇"));
        assertTrue(found, "孕期应给视黄醇一条高风险提示");

        assertEquals("avoid", map(report.get("verdict")).get("level"));
    }

    @Test
    void sensitiveProfileChangesRiskCount() {
        String text = "水、甘油、烟酰胺、香精、柠檬烯";
        long on = nonInfoIngredientCount(service.analyze(text, profile("oily", true, false)));
        long off = nonInfoIngredientCount(service.analyze(text, profile("oily", false, false)));

        assertTrue(on > off, "敏感肌开关应该改变风险数量");
    }

    @Test
    void stripsPackageLabelAndKeepsDigitComma() {
        Map<String, Object> report = service.analyze(
                "成分表：水、甘油、1,2-戊二醇、烟酰胺", profile("normal", false, false));

        List<Map<String, Object>> sequence = list(report.get("sequence"));
        assertEquals(4, sequence.size());
        assertEquals("水", sequence.get(0).get("name"));
        assertEquals("1,2-戊二醇", sequence.get(2).get("name"));
    }

    @Test
    void chineseFuzzyMatchIsNotAllowed() {
        assertNotNull(repository.match("烟酰胺"));
        assertNotNull(repository.match("Niacinamide"));
        assertNotNull(repository.match("羟苯甲酯"));
        assertNotNull(repository.match("羟苯丙酯"));

        // 「1,2-戊二醇」不在库里，必须显示未收录，绝不能模糊匹配成「1,2-丙二醇」
        assertNull(repository.match("1,2-戊二醇"));
    }

    @Test
    void capsAt150Tokens() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 160; i++) {
            if (i > 0) sb.append('、');
            sb.append("甘油");
        }
        Map<String, Object> report = service.analyze(sb.toString(), profile("normal", false, false));

        assertEquals(150, report.get("total"));
        assertEquals(true, report.get("truncated"));
    }

    // ---------------------------------------------------------------- 工具

    private static Map<String, Object> profile(String skin, boolean sensitive, boolean pregnant) {
        Map<String, Object> p = new HashMap<>();
        p.put("skin", skin);
        p.put("sensitive", sensitive);
        p.put("pregnant", pregnant);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static long nonInfoIngredientCount(Map<String, Object> report) {
        long count = 0;
        for (Map<String, Object> flag : list(report.get("flags"))) {
            if (!"info".equals(flag.get("level"))) {
                count += list(flag.get("ingredients")).size();
            }
        }
        return count;
    }
}
