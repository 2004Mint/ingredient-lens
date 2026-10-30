package com.cosme.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.util.*;

/**
 * 成分知识库。
 *
 * 关键设计：成分匹配**不走向量检索**。
 * 「羟苯甲酯」与「羟苯丙酯」在向量空间里非常接近，但一个是常规防腐剂、一个孕期需谨慎，
 * 混淆就是安全事故。所以这里用：标准名精确匹配 → 别名表 → 包含匹配 → 编辑距离兜底。
 *
 * 预审版使用内存库（ingredients.json，60 个高频成分），正式版迁 MySQL 并扩充到 400+。
 */
@Service
public class IngredientRepository {

    private final ObjectMapper om = new ObjectMapper();
    private final List<JsonNode> all = new ArrayList<>();
    /** 归一化名称 -> 成分 */
    private final Map<String, JsonNode> index = new HashMap<>();
    /** 供包含匹配使用的名称列表，按长度降序，避免「水」抢先命中「去离子水」 */
    private final List<String> namesByLength = new ArrayList<>();

    @PostConstruct
    public void load() throws Exception {
        try (InputStream in = new ClassPathResource("ingredients.json").getInputStream()) {
            JsonNode arr = om.readTree(in);
            for (JsonNode n : arr) {
                all.add(n);
                put(n.path("n").asText(), n);
                for (JsonNode a : n.path("a")) put(a.asText(), n);
            }
            namesByLength.sort((x, y) -> y.length() - x.length());
            System.out.println("[成分库] 已加载 " + all.size() + " 个成分，" + index.size() + " 个名称索引");
        }
    }

    private void put(String rawName, JsonNode node) {
        String key = norm(rawName);
        if (!key.isEmpty()) index.putIfAbsent(key, node);
    }

    public int size() { return all.size(); }

    public List<JsonNode> all() { return all; }

    /** 把用户或 OCR 给出的一段文字匹配到成分 */
    public JsonNode match(String token) {
        if (token == null) return null;
        String raw = token.trim();
        if (raw.isEmpty()) return null;

        // 1) 整体精确匹配（含别名）
        JsonNode hit = index.get(norm(raw));
        if (hit != null) return hit;

        // 2) 拆括号：中文名（INCI）、INCI（中文名）
        for (String part : splitParens(raw)) {
            hit = index.get(norm(part));
            if (hit != null) return hit;
        }

        // 3) 包含匹配（要求候选名至少 3 个字符，避免单字误伤）
        String n = norm(raw);
        for (String name : namesByLength) {
            if (name.length() >= 3 && n.contains(name)) return index.get(name);
        }

        // 4) 编辑距离兜底：**只对纯拉丁名称生效**
        //    中文名一律不做模糊匹配。「1,2-丙二醇」与「1,2-戊二醇」、「羟苯甲酯」与「羟苯丙酯」
        //    只差一个字，但完全是两种物质、两种限量。宁可判「未收录」，也不能张冠李戴。
        if (n.length() >= 4 && isLatin(n)) {
            JsonNode best = null;
            int bestD = 2;
            for (Map.Entry<String, JsonNode> e : index.entrySet()) {
                String key = e.getKey();
                if (!isLatin(key)) continue;
                if (Math.abs(key.length() - n.length()) > 1) continue;
                int d = distance(key, n, 2);
                if (d < bestD) { bestD = d; best = e.getValue(); }
            }
            if (best != null) return best;
        }
        return null;
    }

    private boolean isLatin(String s) {
        if (s.isEmpty()) return false;
        boolean hasLetter = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 127) return false;
            if (Character.isLetter(c)) hasLetter = true;
        }
        return hasLetter;
    }

    public static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replace(" ", "").replace("\u3000", "")
                .replace("(", "（").replace(")", "）")
                .trim();
    }

    private List<String> splitParens(String raw) {
        List<String> out = new ArrayList<>();
        int l = raw.indexOf('（'), r = raw.indexOf('）');
        if (l > 0 && r > l) {
            out.add(raw.substring(0, l));
            out.add(raw.substring(l + 1, r));
        }
        int l2 = raw.indexOf('('), r2 = raw.indexOf(')');
        if (l2 > 0 && r2 > l2) {
            out.add(raw.substring(0, l2));
            out.add(raw.substring(l2 + 1, r2));
        }
        return out;
    }

    /** 带上限的编辑距离，超过 max 直接返回 max+1 */
    private int distance(String a, String b, int max) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > max) return max + 1;
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
