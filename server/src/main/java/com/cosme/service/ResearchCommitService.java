package com.cosme.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把研究 Agent 的草稿并入成分库。
 *
 * 只有这一个地方能写库，自动收录和管理台「通过」都走它，保证两条路径行为一致：
 *   - 库里没有 → 新建（status=pending / source=ai，参与匹配，管理台可再确认）
 *   - 库里已有同名/同义 → 并成别名，不新增条目
 *   - 解析结果指向的其实是另一条已有成分、又没有同义证据 → 抛冲突，交给调用方决定
 */
@Service
public class ResearchCommitService {

    /** 着色剂编号：CI 77891、CI 77491。Colour Index 编号本身是唯一标识，不是自由名称。 */
    private static final Pattern COLOR_INDEX = Pattern.compile("(?i)CI\\s?[0-9]{4,5}(?::[0-9]{1,2})?");

    private final IngredientRepository repository;
    private final IngredientAdminService ingredientAdmin;

    public ResearchCommitService(IngredientRepository repository, IngredientAdminService ingredientAdmin) {
        this.repository = repository;
        this.ingredientAdmin = ingredientAdmin;
    }

    /** 返回实际写进成分库的标准名。 */
    public synchronized String commit(String query, ResearchService.Result draftResult) throws IOException {
        if (draftResult == null || draftResult.draft == null) {
            throw new IllegalArgumentException("没有可写入的草稿");
        }
        return commit(query, draftResult.draft, draftResult.confidence,
                draftResult.model, draftResult.evidence);
    }

    public synchronized String commit(String query, IngredientAdminService.Draft draft,
                                      Double confidence, String model, String evidence) throws IOException {
        String canonical = draft == null || draft.name == null ? "" : draft.name.trim();
        if (canonical.isEmpty()) canonical = query == null ? "" : query.trim();
        if (canonical.isEmpty()) throw new IllegalArgumentException("草稿没有可用的名称");

        // 标准名和别名都要查：CI 77891 这类编号，模型给出的标准名往往是「Titanium Dioxide」，
        // 库里早就以别名形式收录在「二氧化钛」下。只查标准名会判成「新成分」，
        // 然后被唯一性校验打回「重名」——研究任务就这样反复失败，用户永远看不到解释。
        JsonNode existing = repository.exact(canonical);
        if (existing == null && draft.aliases != null) {
            for (String alias : draft.aliases) {
                if (alias == null || alias.trim().isEmpty()) continue;
                JsonNode hit = repository.exact(alias);
                if (hit != null) {
                    existing = hit;
                    break;
                }
            }
        }

        if (existing != null) {
            String existingName = existing.path("n").asText("");
            if (existingName.isEmpty()) throw new IllegalArgumentException("成分库里已有同名成分，但读取失败");
            if (canMergeAlias(query, draft.aliases, existingName)) {
                if (ingredientAdmin.addAlias(existingName, query)) {
                    System.out.println("[研究] 归并别名 " + query + " → " + existingName
                            + "（置信度 " + (confidence == null ? "-" : confidence) + "）");
                }
                return existingName;
            }
            String hint = query != null && COLOR_INDEX.matcher(query.trim()).matches()
                    ? "（编号与库里这条不一致，多半是模型把两种颜料认混了）"
                    : "";
            throw new IllegalArgumentException(
                    "成分库已有「" + existingName + "」，与待入库名称不一致" + hint);
        }

        double score = confidence == null ? 0.5 : confidence;
        JsonNode created = ingredientAdmin.createResearched(
                query == null ? canonical : query, draft, score, evidence, model);
        String name = created.path("n").asText(canonical);
        System.out.println("[研究] 自动收录 " + query + " → " + name + "（置信度 " + score + "）");
        return name;
    }

    /**
     * 研究结论能不能并成已有成分的别名，而不是新建一条。
     *
     * <p>四种可以并的情况：查询词为空（等于没做）；查询词就是标准名；查询词是标准名的括号变体
     * （「(日用)香精」→「香精」）；以及模型自己把查询词写进了别名
     * （「二氧化钛」的草稿别名里有「CI 77891」），说明它认定两者是同一物质。
     * 着色剂编号按编号本身判断：条目里写着同一个编号、或者还没写编号，才并成别名；
     * 条目里写的是别的编号就拒绝——CI 77491 是氧化铁红，CI 77492 是氧化铁黄。</p>
     *
     * <p>其余一律拒绝：「透明质酸」和「透明质酸钠」在库里是两条不同记录，
     * 不能因为名字像就并掉，宁可报冲突让管理台来判。</p>
     */
    static boolean canMergeAlias(String query, List<String> draftAliases, String existingName) {
        String target = IngredientRepository.norm(existingName);
        if (target.isEmpty()) return false;

        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return true;
        String key = IngredientRepository.norm(q);
        if (key.equals(target)) return true;
        if (IngredientRepository.norm(stripParens(q)).equals(target)) return true;

        // 编号是唯一标识，先单独判：条目里写着另一个编号，说明模型认错了物质
        // （实测模型把 CI 77491 写成了「CI 77492（氧化铁黄）」，差一位就是红和黄的区别）。
        // 这一步必须放在别名判断之前，否则模型把错误编号列进别名也会被放行。
        Matcher code = COLOR_INDEX.matcher(q);
        if (code.matches()) {
            Matcher other = COLOR_INDEX.matcher(existingName);
            if (other.find()) return codeOf(code.group()).equals(codeOf(other.group()));
            return true;
        }

        if (draftAliases != null) {
            for (String alias : draftAliases) {
                if (alias != null && IngredientRepository.norm(alias).equals(key)) return true;
            }
        }
        return false;
    }

    /** 取出 CI 编号里的数字，忽略大小写、空格和 :1 后缀：CI 77491 → 77491 */
    private static String codeOf(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("[^0-9]", "");
        return digits;
    }

    /** 原始名称是不是已有成分的括号变体；只有这种情况才允许并成别名。 */
    public boolean isVariantOf(String query, String existingName) {
        String stripped = IngredientRepository.norm(stripParens(query));
        String exact = IngredientRepository.norm(query);
        String target = IngredientRepository.norm(existingName);
        if (target.isEmpty()) return false;
        return target.equals(stripped) || target.equals(exact);
    }

    private static String stripParens(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\(（\\[【][^)）\\]】]*[\\)）\\]】]", "");
    }
}
