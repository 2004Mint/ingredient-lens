package com.cosme.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 核心分析引擎：把一串成分名，变成一份「对你」的解读。
 *
 * 三个关键判断：
 * 1. 成分按含量降序排列（含量 ≤0.1% 者可任意排列），所以「位置」本身就是信息，
 *    排在第 12 位的功效成分基本等于概念添加。
 * 2. 确定性的事交给规则，解释交给模板/模型：限量、禁忌不让模型判断。
 * 3. 同类风险必须合并展示。同一个「敏感肌需留意」重复五遍，就是在制造它本该消除的噪音。
 */
@Service
public class AnalyzeService {

    private final IngredientRepository repo;

    public AnalyzeService(IngredientRepository repo) {
        this.repo = repo;
    }

    public Map<String, Object> analyze(String text, Map<String, Object> profile) {
        String skin = str(profile.get("skin"), "normal");
        boolean sensitive = bool(profile.get("sensitive"));
        boolean pregnant = bool(profile.get("pregnant"));

        List<String> tokens = split(text);
        List<Map<String, Object>> ladder = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        List<Map<String, Object>> raw = new ArrayList<>();
        List<Map<String, Object>> actives = new ArrayList<>();

        int pos = 0;
        for (String token : tokens) {
            String t = cleanToken(token);
            if (t.isEmpty()) continue;
            // 位置按成分表里的真实次序计算：未收录的成分也占一个位置，
            // 否则后面所有成分的排位都会错，而「排第几」正是本产品的核心依据
            int idx = pos++;
            JsonNode ing = repo.match(t);
            if (ing == null) {
                if (t.length() > 1) unknown.add(t);
                continue;
            }
            String name = ing.path("n").asText();
            String role = ing.path("r").asText();
            String cat = ing.path("c").asText();
            int comedo = ing.path("cd").asInt();
            String irrit = ing.path("ir").asText();
            String preg = ing.path("pg").asText();
            String sens = ing.path("sn").asText();
            String limit = ing.path("lm").asText("");
            String note = ing.path("nt").asText("");

            String tier = idx < 5 ? "core" : idx < 10 ? "middle" : "tail";

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("index", idx);
            row.put("tier", tier);
            row.put("role", role);
            row.put("cat", cat);
            row.put("fn", ing.path("f").asText());
            row.put("comedo", comedo);
            row.put("irrit", irrit);
            ladder.add(row);

            // 孕期
            if (pregnant && "avoid".equals(preg)) {
                raw.add(flag(name, "high", "孕期",
                        "该成分为维A类衍生物，口服高剂量有致畸风险，外用虽吸收有限，孕期与备孕期通常建议规避。", note, ""));
            } else if (pregnant && "caution".equals(preg)) {
                raw.add(flag(name, "medium", "孕期",
                        "该成分孕期属于需谨慎使用的类别，建议先咨询医师。",
                        note, limit.isEmpty() ? "" : "法规限量：" + limit));
            }

            // 敏感肌
            if (sensitive && "avoid".equals(sens)) {
                raw.add(flag(name, "high", "敏感肌",
                        "该成分是明确的致敏或强刺激成分，敏感肌建议直接避开。", note, ""));
            } else if (sensitive && "caution".equals(sens)) {
                raw.add(flag(name, "medium", "敏感肌",
                        "建议先在耳后小面积试用 2–3 天，无不适再上脸。", note, ""));
            }

            // 致痘
            if (comedo >= 3 && ("oily".equals(skin) || "combo".equals(skin))) {
                raw.add(flag(name, "medium", "致痘",
                        "致痘性 " + comedo + "/5，油性与混合性皮肤容易因此加重闭口。", note, ""));
            }

            // 法规限量（逐个展示，因为每条的限量值不同）
            if (!limit.isEmpty()) {
                raw.add(flag(name, "info", "法规",
                        "《化妆品安全技术规范》限量要求：" + limit, "", ""));
            }

            // 非敏感肌也提示强刺激
            if (!sensitive && "high".equals(irrit)) {
                raw.add(flag(name, "medium", "刺激性",
                        "刺激性较强，屏障受损期间建议暂停使用。", note, ""));
            }

            // 功效成分定位
            if ("功效".equals(role)) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("name", name);
                a.put("index", idx);
                a.put("tier", tier);
                a.put("cat", cat);
                if (idx >= 10) {
                    a.put("comment", "排在第 " + (idx + 1) + " 位，含量通常已很低，属于概念添加");
                } else if (idx >= 5) {
                    a.put("comment", "排在第 " + (idx + 1) + " 位，含量中等");
                } else {
                    a.put("comment", "排在第 " + (idx + 1) + " 位，是配方中的主要功效成分");
                }
                actives.add(a);
            }
        }

        List<Map<String, Object>> flags = groupFlags(raw);

        long high = flags.stream().filter(f -> "high".equals(f.get("level")))
                .mapToLong(f -> ((List<?>) f.get("ingredients")).size()).sum();
        long medium = flags.stream().filter(f -> "medium".equals(f.get("level")))
                .mapToLong(f -> ((List<?>) f.get("ingredients")).size()).sum();

        String level = high > 0 ? "avoid" : medium > 0 ? "caution" : "good";
        String headline = high > 0
                ? "有 " + high + " 个成分建议你避开"
                : medium > 0 ? "有 " + medium + " 个成分需要留意" : "这份配方对你没有明显风险";

        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("level", level);
        verdict.put("headline", headline);
        verdict.put("summary", summarize(level, high, medium, actives));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("total", ladder.size() + unknown.size());
        out.put("matched", ladder.size());
        out.put("unknownCount", unknown.size());
        out.put("unknown", unknown);
        out.put("verdict", verdict);
        out.put("flags", flags);
        out.put("actives", actives);
        out.put("ladder", ladder);
        out.put("insights", buildInsights(ladder, actives, sensitive));
        out.put("profile", profile);
        out.put("ingredientLibrarySize", repo.size());
        return out;
    }

    // ---------------------------------------------------------------- 合并同类风险

    /**
     * 同一等级、同一类型的风险合并成一条。
     * 五个「对敏感肌可能引起不适」并列展示，本质上是在制造用户本该被消除的焦虑。
     */
    private List<Map<String, Object>> groupFlags(List<Map<String, Object>> raw) {
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();

        for (Map<String, Object> f : raw) {
            String type = str(f.get("type"), "");
            String level = str(f.get("level"), "");
            String ingredient = str(f.get("ingredient"), "");

            // 法规限量的数值各不相同，单独成条
            if ("法规".equals(type)) {
                map.put(level + "|法规|" + ingredient, f);
                continue;
            }

            String key = level + "|" + type;
            Map<String, Object> g = map.get(key);
            if (g == null) {
                g = new LinkedHashMap<>();
                g.put("level", level);
                g.put("type", type);
                g.put("ingredients", new ArrayList<String>());
                g.put("basis", f.get("basis"));
                g.put("details", new ArrayList<Map<String, Object>>());
                g.put("message", "");
                map.put(key, g);
            }
            @SuppressWarnings("unchecked")
            List<String> names = (List<String>) g.get("ingredients");
            names.add(ingredient);

            String note = str(f.get("note"), "");
            if (!note.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> details = (List<Map<String, Object>>) g.get("details");
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("name", ingredient);
                d.put("note", note);
                details.add(d);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> g : map.values()) {
            String type = str(g.get("type"), "");
            String level = str(g.get("level"), "");

            // 法规类没有合并，补齐统一字段后直接输出
            if ("法规".equals(type)) {
                String ing = str(g.get("ingredient"), "");
                g.put("message", "「" + ing + "」属限用组分");
                g.put("ingredients", Collections.singletonList(ing));
                g.put("details", Collections.emptyList());
                out.add(g);
                continue;
            }

            @SuppressWarnings("unchecked")
            List<String> names = (List<String>) g.get("ingredients");

            String msg;
            if (names.size() == 1) {
                msg = typeMessage(type, level) + "「" + names.get(0) + "」";
            } else {
                msg = typeMessage(type, level) + "以下 " + names.size() + " 个成分";
            }
            g.put("message", msg);
            out.add(g);
        }

        out.sort((a, b) -> Integer.compare(rank(str(a.get("level"), "")), rank(str(b.get("level"), ""))));
        return out;
    }

    private int rank(String level) {
        if ("high".equals(level)) return 0;
        if ("medium".equals(level)) return 1;
        return 2;
    }

    private String typeMessage(String type, String level) {
        switch (type) {
            case "孕期":
                return "high".equals(level) ? "孕期建议避开：" : "孕期需谨慎：";
            case "敏感肌":
                return "high".equals(level) ? "敏感肌建议避开：" : "敏感肌需留意：";
            case "致痘":
                return "油皮需留意的致痘成分：";
            case "刺激性":
                return "刺激性较强的成分：";
            default:
                return "需要留意：";
        }
    }

    // ---------------------------------------------------------------- 文字生成

    private String summarize(String level, long high, long medium, List<Map<String, Object>> actives) {
        StringBuilder sb = new StringBuilder();
        if ("avoid".equals(level)) {
            sb.append("按你填的肤质与身体状况，这份配方里有 ").append(high).append(" 个成分建议避开。");
        } else if ("caution".equals(level)) {
            sb.append("这份配方没有硬伤，但有 ").append(medium).append(" 个成分值得你留意。");
        } else {
            sb.append("按你填的肤质与身体状况，这份配方没有需要特别回避的成分。");
        }
        long concept = actives.stream().filter(a -> "tail".equals(a.get("tier"))).count();
        if (concept > 0) {
            sb.append("另外有 ").append(concept).append(" 个功效成分排在较后位置，实际含量可能远低于宣传。");
        }
        return sb.toString();
    }

    private List<String> buildInsights(List<Map<String, Object>> ladder, List<Map<String, Object>> actives, boolean sensitive) {
        List<String> out = new ArrayList<>();
        if (!ladder.isEmpty()) {
            out.add("成分表第一位是「" + ladder.get(0).get("name") + "」，说明这是一瓶以"
                    + ladder.get(0).get("name") + "为基底的产品，质地与肤感都由它决定。");
        }
        for (Map<String, Object> a : actives) {
            if ("tail".equals(a.get("tier"))) {
                int i = (int) a.get("index");
                out.add("「" + a.get("name") + "」排在第 " + (i + 1) + " 位。按含量递减规则，它前面已有 "
                        + i + " 个成分，含量大概率低于 1%——如果广告主打这个成分，请对效果打折。");
                break;
            }
        }
        for (Map<String, Object> r : ladder) {
            if ("防腐".equals(r.get("role")) && ((int) r.get("index")) <= 6) {
                out.add("第一个防腐剂出现在第 " + ((int) r.get("index") + 1)
                        + " 位，说明配方里水的占比很高，其余有效物的空间被压缩了。");
                break;
            }
        }
        boolean hasFragrance = ladder.stream().anyMatch(r -> "香精".equals(r.get("role")));
        if (hasFragrance && sensitive) {
            out.add("配方含香精，且你标注了敏感肌——这是最常见的致敏来源之一，建议先做局部试用。");
        }
        out.add("共识别出 " + ladder.size() + " 个成分。成分表长不代表好，也不代表差，关键是排序。");
        return out;
    }

    // ---------------------------------------------------------------- 工具

    private Map<String, Object> flag(String ingredient, String level, String type,
                                     String basis, String note, String extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ingredient", ingredient);
        m.put("level", level);
        m.put("type", type);
        m.put("basis", basis + (extra.isEmpty() ? "" : " " + extra));
        m.put("note", note == null ? "" : note);
        return m;
    }

    /** 按顿号等切分；先把「1,2-戊二醇」这类数字间的逗号保护起来，避免被切断 */
    private List<String> split(String text) {
        if (text == null) return Collections.emptyList();
        String s = text.replace("成分表", "").replace("成分：", "").replace("成分:", "");
        s = s.replaceAll("(\\d)[,，](\\d)", "$1\u0001$2");
        String[] parts = s.split("[、，,；;｜|\\n\\r]+");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String t = p.replace('\u0001', ',').trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private String cleanToken(String t) {
        String s = t.trim();
        s = s.replaceAll("^[0-9０-９]{1,3}[.．、)）\\s]+", "");
        s = s.replaceAll("^其他微量成分[:：]?", "");
        return s.trim();
    }

    private String str(Object o, String def) {
        return o == null ? def : o.toString();
    }

    private boolean bool(Object o) {
        return o instanceof Boolean ? (Boolean) o : "true".equals(String.valueOf(o));
    }
}
