package com.cosme.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * 研究队列 worker。
 *
 * 每次认领一条任务：调用研究 API，成功后写回成分库并标记 done；
 * 失败则保留错误与重试时间，管理台可见、可手动重试。
 */
@Service
public class ResearchTaskWorker {

    private final ResearchTaskStore store;
    private final ResearchService research;
    private final IngredientAdminService ingredientAdmin;
    private final IngredientRepository repository;

    public ResearchTaskWorker(ResearchTaskStore store, ResearchService research,
                              IngredientAdminService ingredientAdmin, IngredientRepository repository) {
        this.store = store;
        this.research = research;
        this.ingredientAdmin = ingredientAdmin;
        this.repository = repository;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 8000)
    public void tick() {
        if (!research.enabled()) return;
        ResearchTask task = store.claimNext();
        if (task == null) return;

        try {
            JsonNode known = repository.match(task.query);
            if (known != null) {
                store.complete(task.id, known.path("n").asText(task.query), null, "", "already in library");
                return;
            }

            ResearchService.Result result = research.research(task.query, task.context);
            JsonNode after = repository.match(task.query);
            if (after != null) {
                store.complete(task.id, after.path("n").asText(result.draft.name), result.confidence,
                        result.model, result.evidence);
                return;
            }

            JsonNode created;
            try {
                created = ingredientAdmin.createResearched(
                        task.query, result.draft, result.confidence, result.evidence, result.model);
            } catch (IllegalArgumentException conflict) {
                // "(日用)香精" 被模型归一成已有的 "香精"：不是新成分，直接并成别名
                if (mergeParentheticalAlias(task, result)) return;
                throw conflict;
            }
            store.complete(task.id, created.path("n").asText(result.draft.name),
                    result.confidence, result.model, result.evidence);
            System.out.println("[研究队列] 完成 " + task.query + " → " + created.path("n").asText());
        } catch (Exception e) {
            JsonNode after = repository.match(task.query);
            if (after != null) {
                store.complete(task.id, after.path("n").asText(task.query), null, "", "already in library");
            } else {
                store.fail(task.id, e.getMessage());
                System.err.println("[研究队列] 失败 " + task.query + "：" + e.getMessage());
            }
        }
    }

    private boolean mergeParentheticalAlias(ResearchTask task, ResearchService.Result result) throws IOException {
        if (result == null || result.draft == null) return false;
        String canonical = result.draft.name == null ? "" : result.draft.name.trim();
        if (canonical.isEmpty()) return false;

        JsonNode existing = repository.match(canonical);
        if (existing == null) return false;

        String existingName = existing.path("n").asText("");
        if (existingName.isEmpty() || !isParentheticalVariant(task.query, canonical)) return false;
        if (!ingredientAdmin.addAlias(existingName, task.query)) return false;

        store.complete(task.id, existingName, result.confidence, result.model,
                "已合并为已有成分「" + existingName + "」的别名：" + task.query);
        System.out.println("[研究队列] 合并别名 " + task.query + " → " + existingName);
        return true;
    }

    private boolean isParentheticalVariant(String query, String canonical) {
        String q = IngredientRepository.norm(query);
        String c = IngredientRepository.norm(canonical);
        if (q.isEmpty() || c.isEmpty()) return false;
        if (q.equals(c)) return true;
        String stripped = stripParenthetical(query);
        return c.equals(IngredientRepository.norm(stripped));
    }

    private String stripParenthetical(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\(（\\[【][^)）\\]】]*[\\)）\\]】]", "");
    }
}
