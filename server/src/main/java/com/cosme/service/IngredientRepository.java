package com.cosme.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;

/**
 * 成分知识库。
 *
 * 关键设计：成分匹配**不走向量检索**。
 * 「羟苯甲酯」与「羟苯丙酯」在向量空间里非常接近，但一个是常规防腐剂、一个孕期需谨慎，
 * 混淆就是安全事故。所以这里用：标准名精确匹配 → 别名表 → 包含匹配 → 编辑距离兜底。
 *
 * 数据源优先级：data/ingredients.json（管理台编辑后落盘） > 打包内置的 ingredients.json。
 * 管理台保存后立即重建内存索引，不需要重启。
 */
@Service
public class IngredientRepository {

    private final ObjectMapper om = new ObjectMapper();
    private final Path dataDir;
    private final Path overrideFile;

    private volatile LibraryState state = new LibraryState(
            Collections.emptyList(), Collections.emptyList(), Collections.emptyMap(), Collections.emptyList());
    private volatile String source = "builtin";

    public IngredientRepository() {
        this.dataDir = DataPaths.dir();
        this.overrideFile = dataDir.resolve("ingredients.json");
    }

    @PostConstruct
    public void load() {
        reload();
    }

    public synchronized void reload() {
        if (Files.exists(overrideFile)) {
            try {
                JsonNode arr = om.readTree(overrideFile.toFile());
                if (arr.isArray() && arr.size() > 0) {
                    publish(arr, "override");
                    return;
                }
                System.err.println("[成分库] 自定义库为空或格式不对，回退内置库");
            } catch (Exception e) {
                System.err.println("[成分库] 自定义库读取失败，回退内置库：" + e.getMessage());
            }
        }

        try (InputStream in = new ClassPathResource("ingredients.json").getInputStream()) {
            publish(om.readTree(in), "builtin");
        } catch (Exception e) {
            throw new IllegalStateException("成分库加载失败", e);
        }
    }

    private void publish(JsonNode arr, String sourceName) {
        List<JsonNode> all = new ArrayList<>();
        List<JsonNode> active = new ArrayList<>();
        Map<String, JsonNode> index = new HashMap<>();
        List<String> names = new ArrayList<>();

        for (JsonNode n : arr) {
            if (!n.isObject()) continue;
            String name = n.path("n").asText("").trim();
            if (name.isEmpty()) continue;

            all.add(n);
            // 已拒绝只留在管理台审计，不参与匹配与前台展示
            if ("rejected".equals(statusOf(n))) continue;

            active.add(n);
            put(index, names, name, n);
            for (JsonNode a : n.path("a")) put(index, names, a.asText(), n);
        }
        if (all.isEmpty()) {
            throw new IllegalArgumentException("成分库不能为空");
        }

        names.sort((x, y) -> y.length() - x.length());
        this.state = new LibraryState(
                Collections.unmodifiableList(all),
                Collections.unmodifiableList(active),
                Collections.unmodifiableMap(index),
                Collections.unmodifiableList(names));
        this.source = sourceName;

        System.out.println("[成分库] 已加载 " + all.size() + " 个成分（参与匹配 " + active.size()
                + "，拒绝 " + (all.size() - active.size()) + "），" + index.size()
                + " 个名称索引（" + sourceName + "）");
    }

    private void put(Map<String, JsonNode> index, List<String> names, String rawName, JsonNode node) {
        String key = norm(rawName);
        if (!key.isEmpty() && index.putIfAbsent(key, node) == null) {
            names.add(key);
        }
    }

    public int size() {
        return state.active.size();
    }

    public int totalCount() {
        return state.all.size();
    }

    /** 全部成分（含已拒绝），管理台用 */
    public List<JsonNode> all() {
        return state.all;
    }

    /** 参与匹配与前台展示的成分（待研究 + 已确认） */
    public List<JsonNode> active() {
        return state.active;
    }

    public Map<String, Integer> statusCounts() {
        int pending = 0, confirmed = 0, rejected = 0;
        for (JsonNode n : state.all) {
            String status = statusOf(n);
            if ("confirmed".equals(status)) confirmed++;
            else if ("rejected".equals(status)) rejected++;
            else pending++;
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("pending", pending);
        out.put("confirmed", confirmed);
        out.put("rejected", rejected);
        return out;
    }

    public static String statusOf(JsonNode node) {
        String status = node == null ? "" : node.path("st").asText("").trim();
        if ("confirmed".equals(status) || "rejected".equals(status)) return status;
        return "pending";
    }

    /** builtin / override */
    public String source() {
        return source;
    }

    public boolean isOverridden() {
        return "override".equals(source);
    }

    public Path overrideFile() {
        return overrideFile;
    }

    /** 深拷贝一份给管理台编辑，避免直接改到匹配中的节点 */
    public ArrayNode exportArray() {
        ArrayNode arr = om.createArrayNode();
        for (JsonNode n : state.all) arr.add(n.deepCopy());
        return arr;
    }

    /** 管理台保存：先落盘，再重建索引，分析请求立即使用新数据 */
    public synchronized void save(JsonNode array) throws IOException {
        if (array == null || !array.isArray() || array.size() == 0) {
            throw new IllegalArgumentException("成分库不能为空");
        }
        Files.createDirectories(dataDir);
        Path tmp = Files.createTempFile(dataDir, "ingredients-", ".tmp");
        om.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), array);
        try {
            Files.move(tmp, overrideFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, overrideFile, StandardCopyOption.REPLACE_EXISTING);
        }
        publish(array, "override");
    }

    public synchronized void resetToBuiltin() throws IOException {
        Files.deleteIfExists(overrideFile);
        reload();
    }

    /** 把用户或 OCR 给出的一段文字匹配到成分 */
    public JsonNode match(String token) {
        if (token == null) return null;
        String raw = token.trim();
        if (raw.isEmpty()) return null;

        LibraryState s = state;

        // 1) 整体精确匹配（含别名）
        JsonNode hit = s.index.get(norm(raw));
        if (hit != null) return hit;

        // 2) 拆括号：中文名（INCI）、INCI（中文名）
        for (String part : splitParens(raw)) {
            hit = s.index.get(norm(part));
            if (hit != null) return hit;
        }

        // 3) 包含匹配（要求候选名至少 3 个字符，避免单字误伤）
        String n = norm(raw);
        for (String name : s.namesByLength) {
            if (name.length() >= 3 && n.contains(name)) return s.index.get(name);
        }

        // 4) 编辑距离兜底：**只对纯拉丁名称生效**
        //    中文名一律不做模糊匹配。「1,2-丙二醇」与「1,2-戊二醇」、「羟苯甲酯」与「羟苯丙酯」
        //    只差一个字，但完全是两种物质、两种限量。宁可判「未收录」，也不能张冠李戴。
        if (n.length() >= 4 && isLatin(n)) {
            JsonNode best = null;
            int bestD = 2;
            for (Map.Entry<String, JsonNode> e : s.index.entrySet()) {
                String key = e.getKey();
                if (!isLatin(key)) continue;
                if (Math.abs(key.length() - n.length()) > 1) continue;
                int d = distance(key, n, 2);
                if (d < bestD) {
                    bestD = d;
                    best = e.getValue();
                }
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
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    private static class LibraryState {
        final List<JsonNode> all;
        final List<JsonNode> active;
        final Map<String, JsonNode> index;
        final List<String> namesByLength;

        LibraryState(List<JsonNode> all, List<JsonNode> active,
                     Map<String, JsonNode> index, List<String> namesByLength) {
            this.all = all;
            this.active = active;
            this.index = index;
            this.namesByLength = namesByLength;
        }
    }
}
