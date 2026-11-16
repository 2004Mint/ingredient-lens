package com.cosme.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 输出闸门与断流判定的回归测试。
 *
 * 这里锁住的是「AI 安全与可控」这一层真正会被改坏的地方：
 * 1. 模型一旦写出数字、百分比，必须被拦下（数字只允许来自规则引擎，不允许来自模型）；
 * 2. 九个医疗用语越界即拦（我们只做成分信息整理，不做医疗建议）；
 * 3. 过长 / 过短 / 空输出一律不放行；
 * 4. 中文数字不带百分号是正常表达（「这三种成分」），不能误杀；
 * 5. 断流判定只认句末标点，逗号结尾必须视为没说完。
 *
 * 不依赖网络与模型 Key，纯函数级测试，随时可跑：
 *   mvn -q test -Dtest=ExplainGuardTest
 */
class ExplainGuardTest {

    private static final String OK_TEXT =
            "柠檬烯是敏感肌的高风险项，建议直接避开，因为它氧化之后的致敏性会明显增强。";

    @Test
    @DisplayName("放行：正常解释不含数字与医疗用语")
    void passesCleanExplanation() {
        assertNull(ExplainService.guard(OK_TEXT));
    }

    @Test
    @DisplayName("放行：中文数字不带百分号属于正常表达")
    void passesChineseNumeralWithoutPercent() {
        assertNull(ExplainService.guard("这三种成分里，烟酰胺主要负责提亮肤色，但排在配方后段，不是主角。"));
    }

    @Test
    @DisplayName("拦截：阿拉伯数字、全角数字、小数与百分号")
    void blocksDigits() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("有 1 个成分建议避开，其余都需要你自己判断。", "阿拉伯数字");
        cases.put("浓度大概在 ０．１ 左右，基本可以忽略，不用太担心。", "全角数字");
        cases.put("大约百分之三的人会对这个成分过敏，需要留意。", "百分之");
        cases.put("敏感肌人群里约三%会出现不适，建议先小面积试用。", "中文数字加百分号");

        cases.forEach((text, why) -> {
            String violation = ExplainService.guard(text);
            assertNotNull(violation, "应当拦截：" + why);
            assertEquals("出现了数字或百分比", violation, why);
        });
    }

    @Test
    @DisplayName("拦截：九个医疗用语，一个都不放过")
    void blocksEveryMedicalTerm() {
        String[] banned = {"治疗", "治愈", "诊断", "处方", "根治", "疗效", "药效", "药用", "医美"};
        for (String word : banned) {
            String text = "这个成分在瓶子里" + word + "相关的效果比较明显，适合日常使用。";
            String violation = ExplainService.guard(text);
            assertNotNull(violation, "应当拦截医疗用语：" + word);
            assertTrue(violation.contains(word), "拦截原因应点名违规词：" + word);
        }
    }

    @Test
    @DisplayName("拦截：空输出、过短、过长")
    void blocksBadLength() {
        assertEquals("模型没有返回内容", ExplainService.guard(""));
        assertEquals("内容过短", ExplainService.guard("太短了，看不出来。"));
        assertEquals("内容过长", ExplainService.guard("好".repeat(521)));
    }

    @Test
    @DisplayName("放行：成分名里的数字不算浓度")
    void passesIngredientNameDigits() {
        assertNull(ExplainService.guard("柠檬烯和 1,2-戊二醇都在配方前段，二苯酮-3 则属于防晒剂，聚山梨醇酯-20 是表活。"));
        // 但同样的数字一旦脱离成分名，仍然要拦
        assertNotNull(ExplainService.guard("配方里一共有 3 个成分需要你留意，其余都比较温和。"));
        assertNotNull(ExplainService.guard("这个成分的法规限量是 3%，按说明使用即可。"));
    }

    @Test
    @DisplayName("数字改写：计数改成中文可以放行，小数量词不行")
    void salvageCountsOnly() {
        String raw = "烟酰胺排在第十位之后，属于配方后段，通常不是主要功效来源。";
        assertNull(ExplainService.guard(raw), "中文数字本身应当放行");

        String withDigit = "烟酰胺排在第 10 位，属于配方后段，通常不是主要功效来源。";
        assertNotNull(ExplainService.guard(withDigit), "带阿拉伯数字应当先被拦下");

        String fixed = ExplainService.salvage(withDigit);
        assertNotNull(fixed, "计数可以改写成中文后放行");
        assertTrue(fixed.contains("第十位"), "改写结果：" + fixed);
        assertFalse(fixed.contains("第第"), "不能把「第」重复一遍：" + fixed);
        assertNull(ExplainService.guard(fixed));

        // 带百分号或剂量单位的说明是在讲浓度，不允许靠改写蒙混过关
        assertNull(ExplainService.salvage("法规限量是 2.0%，日常使用不用太担心，按说明用就好。"));
        assertNull(ExplainService.salvage("建议每次用 0.5 克左右，薄薄一层就够，不要涂太厚。"));
    }

    @Test
    @DisplayName("数字改写：事实与文本里的计数一律转成中文数字")
    void neutralizesNumbers() {
        assertEquals("一", ExplainService.cn(1));
        assertEquals("十", ExplainService.cn(10));
        assertEquals("十二", ExplainService.cn(12));
        assertEquals("二十四", ExplainService.cn(24));
        assertEquals("一百五十", ExplainService.cn(150));

        String text = "共 12 个成分，其中 3 个需要留意，排在第五位的是烟酰胺。";
        String clean = ExplainService.neutralize(text);
        assertFalse(clean.matches("(?s).*[0-9].*"), "改写后不应再有阿拉伯数字：" + clean);
        assertTrue(clean.contains("十二个成分"));
        assertTrue(clean.contains("三个需要留意"));
    }

    @Test
    @DisplayName("放行：维生素B5 这类代号不是浓度，改写也不能破坏它")
    void keepsCodeNames() {
        String text = "泛醇进入皮肤后会转化成维生素B5，主要起保湿与屏障修护作用。";
        assertNull(ExplainService.guard(text), "B5 是名称的一部分，不该被当成数字");
        assertEquals(text, ExplainService.neutralize(text), "改写不应把 B5 变成 B五");

        String withCount = "这支精华里共有 3 个功效成分，泛醇会转化成维生素B5，排在第六位。";
        String fixed = ExplainService.salvage(withCount);
        assertNotNull(fixed, "只差计数时应当可以改写放行");
        assertTrue(fixed.contains("维生素B5"), "代号不能被改写：" + fixed);
        assertTrue(fixed.contains("三个功效成分"), fixed);
        assertTrue(fixed.contains("第六位"), fixed);
    }

    @Test
    @DisplayName("数字改写：量词前的「二」写成「两」，序数不动")
    void usesLiangBeforeMeasureWord() {
        assertEquals("有两个成分需要留意", ExplainService.neutralize("有 2 个成分需要留意"));
        assertEquals("十二个成分", ExplainService.neutralize("12 个成分"));
        assertEquals("第二款", ExplainService.neutralize("第 2 款"));
        assertEquals("排在第五位", ExplainService.neutralize("排在第 5 位"));
        // 小数是精度，不改写
        assertEquals("平均 1.5 个成分", ExplainService.neutralize("平均 1.5 个成分"));
    }

    @Test
    @DisplayName("settle：能过就原样用，只差数字就改写，其他一律回退")
    void settlePicksBest() {
        assertEquals(OK_TEXT, ExplainService.settle(OK_TEXT));
        assertNotNull(ExplainService.settle("这支洁面里有 2 个成分建议避开的，其余问题不大。"));
        assertNull(ExplainService.settle("这个成分有治疗效果，建议按处方使用，不要自己乱用。"));
    }

    @Test
    @DisplayName("断流判定：只有句末标点才算说完")
    void terminalPunctuation() {
        assertTrue(ExplainService.endsWithTerminal("这句话说完了。"));
        assertTrue(ExplainService.endsWithTerminal("真的吗？"));
        assertTrue(ExplainService.endsWithTerminal("别用！"));
        assertTrue(ExplainService.endsWithTerminal("还没说完…"));
        assertTrue(ExplainService.endsWithTerminal("他说「避开」」"));
        assertFalse(ExplainService.endsWithTerminal("这句话还没说完，"));
        assertFalse(ExplainService.endsWithTerminal("而且排在后面"));
        assertFalse(ExplainService.endsWithTerminal(""));
        assertFalse(ExplainService.endsWithTerminal(null));
    }
}
