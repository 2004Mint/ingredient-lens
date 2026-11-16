package com.cosme.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * 把研究 Agent 的草稿并入成分库。
 *
 * 只有这一个地方能写库，自动收录和管理台「通过」都走它，保证两条路径行为一致：
 *   - 库里没有 → 新建（status=pending / source=ai，参与匹配，管理台可再确认）
 *   - 库里已有同名的括号变体 → 并成别名，不新增条目
 *   - 库里已有同名但不是变体 → 抛冲突，交给调用方决定
 */
@Service
public class ResearchCommitService {

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

        // 用精确匹配判断「是否已存在」：包含匹配会把新成分并成已有成分的别名
        JsonNode existing = repository.exact(canonical);
        if (existing != null) {
            String existingName = existing.path("n").asText("");
            if (existingName.isEmpty()) throw new IllegalArgumentException("成分库里已有同名成分，但读取失败");
            if (!isVariantOf(query, existingName)) {
                throw new IllegalArgumentException(
                        "成分库已有「" + existingName + "」，与待入库名称不一致");
            }
            ingredientAdmin.addAlias(existingName, query);
            return existingName;
        }

        double score = confidence == null ? 0.5 : confidence;
        JsonNode created = ingredientAdmin.createResearched(
                query == null ? canonical : query, draft, score, evidence, model);
        String name = created.path("n").asText(canonical);
        System.out.println("[研究] 自动收录 " + query + " → " + name + "（置信度 " + score + "）");
        return name;
    }

    /** 原始名称是不是已有成分的括号变体；只有这种情况才允许并成别名。 */
    public boolean isVariantOf(String query, String existingName) {
        String stripped = IngredientRepository.norm(stripParens(query));
        String exact = IngredientRepository.norm(query);
        String target = IngredientRepository.norm(existingName);
        if (target.isEmpty()) return false;
        return target.equals(stripped) || target.equals(exact);
    }

    private String stripParens(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\(（\\[【][^)）\\]】]*[\\)）\\]】]", "");
    }
}
