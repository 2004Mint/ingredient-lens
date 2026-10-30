package com.cosme.service;

import com.cosme.AppMeta;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * 管理台的成分数据编辑与审核。
 *
 * 状态：pending（待研究）/ confirmed（已确认）/ rejected（已拒绝）。
 * pending + confirmed 参与匹配；rejected 只留在管理台审计。
 * 所有改动写回 data/ingredients.json 并立即重建索引。
 */
@Service
public class IngredientAdminService {

    private static final Set<String> ROLES = new LinkedHashSet<>(
            Arrays.asList("基质", "功效", "防腐", "香精", "防晒", "表活"));
    private static final Set<String> RISK = new LinkedHashSet<>(Arrays.asList("low", "medium", "high"));
    private static final Set<String> PROFILE = new LinkedHashSet<>(Arrays.asList("safe", "caution", "avoid"));
    private static final Set<String> STATUS = new LinkedHashSet<>(Arrays.asList("pending", "confirmed", "rejected"));

    private final IngredientRepository repository;
    private final ObjectMapper om = new ObjectMapper();

    public IngredientAdminService(IngredientRepository repository) {
        this.repository = repository;
    }

    /** 前端表单的完整字段；比最终 JSON 的长键更好读 */
    public static class Draft {
        public String name;
        public List<String> aliases;
        public String role;
        public String cat;
        public String function;
        /** AI 深度研究生成的长描述；前台成分卡片优先展示它 */
        public String description;
        public String safety;
        public Integer comedo;
        public String irrit;
        public String preg;
        public String sensitive;
        public String limit;
        public String note;

        /** pending / confirmed / rejected */
        public String status;
        /** builtin / ai / manual */
        public String source;
        public Double confidence;
        public String evidence;
        public String researchModel;
        public String researchedAt;
    }

    public synchronized ObjectNode create(Draft draft) throws IOException {
        ObjectNode node = toNode(draft, null);
        ArrayNode arr = repository.exportArray();
        ensureUnique(arr, node, null);
        arr.add(node);
        repository.save(arr);
        return node;
    }

    public synchronized ObjectNode update(String originalName, Draft draft) throws IOException {
        ArrayNode arr = repository.exportArray();
        int index = indexOf(arr, originalName);
        if (index < 0) throw new IllegalArgumentException("成分不存在或已被删除");
        ObjectNode node = toNode(draft, arr.get(index));
        ensureUnique(arr, node, originalName);
        arr.set(index, node);
        repository.save(arr);
        return node;
    }

    public synchronized void delete(String name) throws IOException {
        ArrayNode arr = repository.exportArray();
        if (arr.size() <= 1) throw new IllegalArgumentException("至少保留一个成分");
        int index = indexOf(arr, name);
        if (index < 0) throw new IllegalArgumentException("成分不存在或已被删除");
        arr.remove(index);
        repository.save(arr);
    }

    /** 批量审核：pending / confirmed / rejected */
    public synchronized void setStatus(List<String> names, String status) throws IOException {
        String next = text(status);
        if (!STATUS.contains(next)) throw new IllegalArgumentException("状态取值不合法");
        if (names == null || names.isEmpty()) throw new IllegalArgumentException("请选择至少一个成分");

        Set<String> keys = new HashSet<>();
        for (String name : names) {
            String key = IngredientRepository.norm(name);
            if (!key.isEmpty()) keys.add(key);
        }

        ArrayNode arr = repository.exportArray();
        int changed = 0;
        for (JsonNode n : arr) {
            if (keys.contains(IngredientRepository.norm(n.path("n").asText()))) {
                ((ObjectNode) n).put("st", next);
                ((ObjectNode) n).put("ut", Instant.now().toString());
                changed++;
            }
        }
        if (changed == 0) throw new IllegalArgumentException("没有找到要更新的成分");
        repository.save(arr);
    }

    /** 扫描到未知成分后，AI 研究成功：新建为待研究，并立即参与匹配 */
    public synchronized ObjectNode createResearched(String originalQuery, Draft aiDraft,
                                                    double confidence, String evidence, String model) throws IOException {
        Draft d = copy(aiDraft);
        String canonical = text(d.name);
        if (canonical.isEmpty()) canonical = text(originalQuery);
        d.name = canonical;

        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        if (d.aliases != null) {
            for (String a : d.aliases) {
                if (!text(a).isEmpty()) aliases.add(text(a));
            }
        }
        if (!text(originalQuery).isEmpty()) aliases.add(text(originalQuery));
        d.aliases = new ArrayList<>(aliases);

        d.status = "pending";
        d.source = "ai";
        d.confidence = confidence;
        d.evidence = evidence;
        d.researchModel = model;
        d.researchedAt = Instant.now().toString();
        return create(d);
    }

    /** 对已有成分做 AI 研究：保留原标准名，研究结果回到待研究供人工确认 */
    public synchronized ObjectNode updateResearched(String name, Draft aiDraft,
                                                    double confidence, String evidence, String model) throws IOException {
        Draft d = copy(aiDraft);
        String aiName = text(d.name);
        d.name = name;
        if (text(d.description).isEmpty()) d.description = null;

        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        if (d.aliases != null) {
            for (String a : d.aliases) {
                if (!text(a).isEmpty()) aliases.add(text(a));
            }
        }
        if (!aiName.isEmpty() && !IngredientRepository.norm(aiName).equals(IngredientRepository.norm(name))) {
            aliases.add(aiName);
        }
        d.aliases = new ArrayList<>(aliases);

        d.status = "pending";
        d.source = "ai";
        d.confidence = confidence;
        d.evidence = evidence;
        d.researchModel = model;
        d.researchedAt = Instant.now().toString();
        return update(name, d);
    }

    /** 管理台表格的一次性视图，包含审核状态与 AI 研究元信息 */
    public Map<String, Object> toView(JsonNode n) {
        List<String> aliases = new ArrayList<>();
        for (JsonNode a : n.path("a")) aliases.add(a.asText());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", n.path("n").asText());
        m.put("aliases", aliases);
        m.put("role", n.path("r").asText());
        m.put("cat", n.path("c").asText());
        m.put("function", n.path("f").asText());
        m.put("description", n.path("ds").asText(""));
        m.put("safety", n.path("s").asText("low"));
        m.put("comedo", n.path("cd").asInt());
        m.put("irrit", n.path("ir").asText("low"));
        m.put("preg", n.path("pg").asText("safe"));
        m.put("sensitive", n.path("sn").asText("safe"));
        m.put("limit", n.path("lm").asText(""));
        m.put("note", n.path("nt").asText(""));
        m.put("status", IngredientRepository.statusOf(n));
        m.put("source", n.path("sr").asText(n.has("st") ? "manual" : "builtin"));
        m.put("confidence", n.has("rc") ? n.path("rc").asDouble() : null);
        m.put("evidence", n.path("rv").asText(""));
        m.put("researchModel", n.path("rm").asText(""));
        m.put("researchedAt", n.path("ra").asText(""));

        String createdAt = n.path("ct").asText("");
        boolean createdFallback = false;
        if (createdAt.isEmpty()) {
            String researchedAt = n.path("ra").asText("");
            if (!researchedAt.isEmpty()) {
                createdAt = researchedAt;
            } else {
                createdAt = builtinCreatedAt();
                createdFallback = true;
            }
        }
        m.put("createdAt", createdAt);
        m.put("updatedAt", n.path("ut").asText(""));
        m.put("createdFallback", createdFallback);
        return m;
    }

    /**
     * 把扫描到的别名并入已有成分，用于 "(日用)香精" → "香精" 这类同一成分的括号变体。
     * 不新增记录，避免 AI 研究因为标准名重名而反复失败。
     */
    public synchronized boolean addAlias(String name, String alias) throws IOException {
        String target = text(name);
        String value = text(alias);
        if (target.isEmpty() || value.isEmpty() || value.length() > 60) return false;

        ArrayNode arr = repository.exportArray();
        int index = indexOf(arr, target);
        if (index < 0) return false;

        JsonNode occupied = repository.match(value);
        if (occupied != null
                && !IngredientRepository.norm(occupied.path("n").asText())
                .equals(IngredientRepository.norm(target))) {
            return false;
        }

        ObjectNode node = (ObjectNode) arr.get(index);
        ArrayNode aliases = node.has("a") && node.get("a").isArray()
                ? (ArrayNode) node.get("a") : node.putArray("a");
        String key = IngredientRepository.norm(value);
        for (JsonNode existing : aliases) {
            if (IngredientRepository.norm(existing.asText()).equals(key)) return true;
        }
        if (aliases.size() >= 20) return false;

        aliases.add(value);
        node.put("ut", Instant.now().toString());
        repository.save(arr);
        return true;
    }

    public void reset() throws IOException {
        repository.resetToBuiltin();
    }

    // ---------------------------------------------------------------- 校验

    private ObjectNode toNode(Draft draft, JsonNode existing) {
        if (draft == null) throw new IllegalArgumentException("缺少成分数据");

        String name = text(draft.name);
        if (name.isEmpty()) throw new IllegalArgumentException("标准名不能为空");
        if (name.length() > 40) throw new IllegalArgumentException("标准名最多 40 个字符");

        String role = oneOf(draft.role, ROLES, "基质", "角色");
        String cat = text(draft.cat);
        if (cat.isEmpty()) cat = "其他";
        if (cat.length() > 20) throw new IllegalArgumentException("分类最多 20 个字符");

        String function = text(draft.function);
        if (function.length() > 200) throw new IllegalArgumentException("作用说明最多 200 个字符");

        String description = draft.description;
        if (description == null && existing != null) description = existing.path("ds").asText("");
        description = text(description);
        if (description.length() > 600) throw new IllegalArgumentException("深度描述最多 600 个字符");

        String safety = oneOf(draft.safety, RISK, "low", "安全等级");
        String irrit = oneOf(draft.irrit, RISK, "low", "刺激性");
        String preg = oneOf(draft.preg, PROFILE, "safe", "孕期提示");
        String sensitive = oneOf(draft.sensitive, PROFILE, "safe", "敏感肌提示");

        int comedo = draft.comedo == null ? 0 : draft.comedo;
        if (comedo < 0 || comedo > 5) throw new IllegalArgumentException("致痘性需在 0–5 之间");

        String limit = text(draft.limit);
        if (limit.length() > 200) throw new IllegalArgumentException("法规限量最多 200 个字符");
        String note = text(draft.note);
        if (note.length() > 200) throw new IllegalArgumentException("备注最多 200 个字符");

        String status = text(draft.status);
        if (!STATUS.contains(status)) {
            status = existing == null ? "pending" : IngredientRepository.statusOf(existing);
        }

        String source = text(draft.source);
        if (source.isEmpty()) {
            source = existing == null ? "manual" : existing.path("sr").asText(
                    existing.has("st") ? "manual" : "builtin");
        }

        ObjectNode node = om.createObjectNode();
        node.put("n", name);

        ArrayNode aliases = node.putArray("a");
        Set<String> seen = new HashSet<>();
        seen.add(IngredientRepository.norm(name));
        if (draft.aliases != null) {
            for (String raw : draft.aliases) {
                String alias = text(raw);
                if (alias.isEmpty()) continue;
                if (alias.length() > 60) throw new IllegalArgumentException("单个别名最多 60 个字符");
                if (seen.add(IngredientRepository.norm(alias))) aliases.add(alias);
                if (aliases.size() >= 20) break;
            }
        }

        node.put("r", role);
        node.put("c", cat);
        node.put("f", function);
        if (!description.isEmpty()) node.put("ds", description);
        node.put("s", safety);
        node.put("cd", comedo);
        node.put("ir", irrit);
        node.put("pg", preg);
        node.put("sn", sensitive);
        node.put("lm", limit);
        node.put("nt", note);
        node.put("st", status);
        node.put("sr", source);

        String createdAt = existing == null ? "" : text(existing.path("ct").asText(""));
        if (createdAt.isEmpty() && existing != null) createdAt = text(existing.path("ra").asText(""));
        if (createdAt.isEmpty()) {
            createdAt = existing == null ? Instant.now().toString() : builtinCreatedAt();
        }
        node.put("ct", createdAt);
        node.put("ut", Instant.now().toString());

        Double confidence = draft.confidence;
        if (confidence == null && existing != null && existing.has("rc")) {
            confidence = existing.path("rc").asDouble();
        }
        String evidence = draft.evidence;
        if (evidence == null && existing != null) evidence = existing.path("rv").asText("");
        String researchModel = draft.researchModel;
        if (researchModel == null && existing != null) researchModel = existing.path("rm").asText("");
        String researchedAt = draft.researchedAt;
        if (researchedAt == null && existing != null) researchedAt = existing.path("ra").asText("");

        if (confidence != null) {
            node.put("rc", Math.max(0, Math.min(1, confidence)));
        }
        if (text(evidence).length() > 0) node.put("rv", text(evidence).substring(0, Math.min(300, text(evidence).length())));
        if (text(researchModel).length() > 0) node.put("rm", text(researchModel));
        if (text(researchedAt).length() > 0) node.put("ra", text(researchedAt));
        return node;
    }

    /** 名称与别名都参与唯一性检查，避免管理台造出「一个别名指向两个成分」 */
    private void ensureUnique(ArrayNode arr, ObjectNode node, String excludeName) {
        Map<String, String> owner = new HashMap<>();
        for (JsonNode n : arr) {
            String existing = n.path("n").asText("");
            if (excludeName != null
                    && IngredientRepository.norm(existing).equals(IngredientRepository.norm(excludeName))) {
                continue;
            }
            owner.putIfAbsent(IngredientRepository.norm(existing), existing);
            for (JsonNode alias : n.path("a")) {
                owner.putIfAbsent(IngredientRepository.norm(alias.asText()), existing);
            }
        }

        List<String> keys = new ArrayList<>();
        keys.add(node.path("n").asText());
        for (JsonNode alias : node.path("a")) keys.add(alias.asText());

        for (String key : keys) {
            String norm = IngredientRepository.norm(key);
            if (norm.isEmpty()) continue;
            String other = owner.get(norm);
            if (other != null) {
                throw new IllegalArgumentException("「" + key + "」与已有成分「" + other + "」重名");
            }
        }
    }

    private int indexOf(ArrayNode arr, String name) {
        String key = IngredientRepository.norm(name);
        for (int i = 0; i < arr.size(); i++) {
            if (IngredientRepository.norm(arr.get(i).path("n").asText()).equals(key)) return i;
        }
        return -1;
    }

    private Draft copy(Draft source) {
        Draft d = new Draft();
        d.name = source.name;
        d.aliases = source.aliases == null ? new ArrayList<>() : new ArrayList<>(source.aliases);
        d.role = source.role;
        d.cat = source.cat;
        d.function = source.function;
        d.description = source.description;
        d.safety = source.safety;
        d.comedo = source.comedo;
        d.irrit = source.irrit;
        d.preg = source.preg;
        d.sensitive = source.sensitive;
        d.limit = source.limit;
        d.note = source.note;
        return d;
    }

    private String oneOf(String value, Set<String> allowed, String fallback, String label) {
        String v = text(value);
        if (v.isEmpty()) return fallback;
        if (!allowed.contains(v)) throw new IllegalArgumentException(label + "取值不合法");
        return v;
    }

    /** 内置库没有真实录入时间，用库版本月份的第一天作为稳定回退值，避免每次启动都变 */
    private String builtinCreatedAt() {
        try {
            String[] parts = AppMeta.LIBRARY_VERSION.split("\\.");
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            return String.format("%04d-%02d-01T00:00:00+08:00", year, month);
        } catch (Exception e) {
            return "2026-10-01T00:00:00+08:00";
        }
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }
}
