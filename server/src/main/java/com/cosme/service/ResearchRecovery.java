package com.cosme.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时修复历史状态。
 *
 * 两件事：
 * 1. 旧版本留下的 review 任务（草稿已生成但没有自动收录）—— 直接收录；收录不了的记失败。
 *    不做这一步，报告页会永远卡在「还有成分在研究中」。
 * 2. 卡在 researching 的任务（进程被杀、上游挂住）—— 超过 3 分钟的放回队列重试。
 */
@Component
public class ResearchRecovery {

    private final ResearchTaskStore store;
    private final ResearchCommitService commit;

    public ResearchRecovery(ResearchTaskStore store, ResearchCommitService commit) {
        this.store = store;
        this.commit = commit;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        int requeued = store.requeueStaleResearching(3 * 60_000L);
        if (requeued > 0) {
            System.out.println("[研究队列] 启动回收 " + requeued + " 条卡住的任务");
        }

        List<ResearchTask> pending = store.list("review", 200);
        int committed = 0;
        int failed = 0;
        for (ResearchTask task : pending) {
            if (task.draft == null) {
                store.fail(task.id, "历史任务没有草稿，已作废");
                failed++;
                continue;
            }
            try {
                String name = commit.commit(task.query, task.draft, task.confidence,
                        task.model, task.evidence);
                store.complete(task.id, name, task.confidence, task.model, task.evidence);
                committed++;
            } catch (Exception e) {
                store.fail(task.id, "历史任务收录失败：" + e.getMessage());
                failed++;
            }
        }
        if (committed > 0 || failed > 0) {
            System.out.println("[研究队列] 历史待复核任务：" + committed + " 条已收录，" + failed + " 条失败");
        }
    }
}
