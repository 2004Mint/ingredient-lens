package com.cosme.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 喂给模型的事实必须「不含阿拉伯数字、不含没依据的字段」。
 *
 * 这是上一次线上问题的根因：事实里有数字、提示词又禁止数字，
 * 模型照抄事实就必然被闸门拦下。这里把事实层的约定锁死。
 */
class ExplainFactsTest {

    private final ExplainService service = new ExplainService(null);

    @Test
    @DisplayName("报告事实：计数与排位都用中文，标题里的数字被改写")
    void reportFactsAreDigitFree() {
        Map<String, Object> verdict = new HashMap<>();
        verdict.put("level", "avoid");
        verdict.put("headline", "有 2 个成分建议你避开");

        Map<String, Object> flag = new HashMap<>();
        flag.put("type", "敏感肌");
        flag.put("level", "high");
        flag.put("ingredients", List.of("柠檬烯"));
        flag.put("message", "敏感肌建议避开：「柠檬烯」");

        Map<String, Object> active = new HashMap<>();
        active.put("name", "烟酰胺");
        active.put("index", 11);
        active.put("tier", "tail");
        active.put("cat", "美白");

        Map<String, Object> report = new HashMap<>();
        report.put("verdict", verdict);
        report.put("total", 12);
        report.put("matched", 10);
        report.put("unknownCount", 2);
        report.put("flags", List.of(flag));
        report.put("actives", List.of(active));

        Map<String, Object> profile = new HashMap<>();
        profile.put("skin", "oily");
        profile.put("sensitive", true);

        Map<String, Object> facts = service.buildReportFacts(report, profile);

        assertEquals("十二", facts.get("成分总数"));
        assertEquals("十", facts.get("已识别"));
        assertEquals("二", facts.get("未识别"));
        assertEquals("有两个成分建议你避开", facts.get("结论标题"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actives = (List<Map<String, Object>>) facts.get("功效成分");
        assertEquals("第十二位", actives.get(0).get("排位"));

        String json = String.valueOf(facts);
        assertFalse(json.matches("(?s).*[0-9].*"), "事实里不应出现阿拉伯数字：" + json);
    }

    @Test
    @DisplayName("成分事实：没有排位就不写排位，没有致痘性就不写致痘性")
    void ingredientFactsOmitUnknownFields() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", "烟酰胺");
        payload.put("role", "功效");

        Map<String, Object> facts = service.buildIngredientFacts(payload);
        assertFalse(facts.containsKey("排位"), "不知道排位时不能给排位：" + facts);
        assertFalse(facts.containsKey("致痘性"), "不知道致痘性时不能给等级：" + facts);

        payload.put("index", 4);
        payload.put("comedo", 3);
        payload.put("function", "维生素B5 的前体，主要起保湿作用");
        facts = service.buildIngredientFacts(payload);

        assertEquals("第五位", facts.get("排位"));
        assertEquals("中等", facts.get("致痘性"));
        assertEquals("维生素B5 的前体，主要起保湿作用", facts.get("一句话作用"),
                "代号里的数字不能被改写");
    }

    @Test
    @DisplayName("成分事实：单个成分也能组装出中文事实")
    void ingredientFactsKeepDigitsOut() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", "柠檬烯");
        payload.put("index", 9);
        payload.put("comedo", 4);
        payload.put("limit", "不得用于 3 岁以下儿童用产品");
        payload.put("comment", "排在第 10 位，属配方后段");

        Map<String, Object> facts = service.buildIngredientFacts(payload);
        assertEquals("第十位", facts.get("排位"));
        assertEquals("较高", facts.get("致痘性"));
        assertEquals("有明确的法规限量要求", facts.get("法规限量"), "带数字的限量只给「有没有」");
        assertTrue(String.valueOf(facts.get("报告里的评价")).contains("第十位"));
        assertFalse(String.valueOf(facts).matches("(?s).*[0-9].*"), "事实里不应出现阿拉伯数字");
    }
}
