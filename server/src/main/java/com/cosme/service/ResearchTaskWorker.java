package com.cosme.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 研究队列 worker：每次认领一条任务，交给 {@link ResearchAgent} 跑一遍。
 *
 * Agent 给出草稿后<b>自动收录</b>进成分库（status=pending / source=ai）：
 * 不设人工审批闸门，但保留轨迹、置信度与理由，管理台可随时复核或删除。
 * 只有「草稿字段不合法」或「模型调用失败」才记失败，避免把坏数据写进库。
 */
@Service
public class ResearchTaskWorker {

    private final ResearchTaskStore store;
    private final ResearchService research;
    private final ResearchAgent agent;
    private final ResearchCommitService commit;

    public ResearchTaskWorker(ResearchTaskStore store, ResearchService research,
                              ResearchAgent agent, ResearchCommitService commit) {
        this.store = store;
        this.research = research;
        this.agent = agent;
        this.commit = commit;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 8000)
    public void tick() {
        if (!research.enabled()) return;
        ResearchTask task = store.claimNext();
        if (task == null) return;

        ResearchAgent.Run run;
        try {
            run = agent.run(task.query, task.context);
        } catch (Exception e) {
            store.fail(task.id, e.getMessage());
            System.err.println("[研究队列] Agent 异常 " + task.query + "：" + e.getMessage());
            return;
        }

        if (ResearchAgent.LIBRARY_HIT.equals(run.outcome)) {
            store.complete(task.id, run.name, null, "", run.reason);
            return;
        }

        // 字段校验通过就自动收录：高置信度直接收，低置信度也收（带警告），但不合法的草稿不收
        if (run.committable && run.draft != null) {
            try {
                String name = commit.commit(task.query, run.draft, run.confidence,
                        run.model, run.evidence);
                store.recordTrace(task.id, run.plan, run.trace, run.reason);
                store.complete(task.id, name, run.confidence, run.model, run.evidence);
                System.out.println("[研究队列] 自动收录 " + task.query + " → " + name
                        + "（置信度 " + run.confidence + "）");
            } catch (Exception e) {
                // 草稿和库里已有的成分打架（同名、编号对不上），属于「需要人看一眼」，
                // 不该记成失败：失败会进报告顶部变成一条红字，用户既看不懂也做不了什么。
                // 转人工复核后，草稿与轨迹都留着，管理台可以改一行就通过。
                store.recordTrace(task.id, run.plan, run.trace, run.reason);
                store.markReview(task.id, run.draft, run.confidence, run.model, run.evidence,
                        "草稿与成分库冲突：" + e.getMessage(), run.plan, run.trace);
                System.err.println("[研究队列] 转人工复核 " + task.query + "：" + e.getMessage());
            }
            return;
        }

        store.recordTrace(task.id, run.plan, run.trace, run.reason);
        store.fail(task.id, run.reason);
        System.err.println("[研究队列] 失败 " + task.query + "：" + run.reason);
    }
}
