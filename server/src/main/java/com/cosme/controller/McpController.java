package com.cosme.controller;

import com.cosme.service.ResearchService;
import com.cosme.service.ResearchTask;
import com.cosme.service.ResearchTaskStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 给 MCP 用的写入口：把「这个成分我没见过」提交进研究队列。
 *
 * 为什么单独开一个控制器而不是复用管理台：
 * MCP 客户端是外部程序，不该拿管理台令牌；这里用一把独立的 MCP 令牌（环境变量 COSME_MCP_TOKEN），
 * 没配置就整个接口关闭，避免默认暴露一个会烧 token 的写接口。
 *
 * 还叠了一个滑动窗口限流：同一进程 10 分钟内最多 10 次，防止外部客户端把研究队列刷爆。
 */
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private static final int WINDOW_LIMIT = 10;
    private static final long WINDOW_MS = 10 * 60_000L;

    private final ResearchTaskStore store;
    private final ResearchService research;
    private final String token;

    private final Deque<Long> recent = new ArrayDeque<>();

    public McpController(ResearchTaskStore store, ResearchService research,
                         @Value("${COSME_MCP_TOKEN:}") String token) {
        this.store = store;
        this.research = research;
        this.token = token == null ? "" : token.trim();
    }

    @PostMapping("/research")
    public ResponseEntity<?> submit(
            @RequestHeader(value = "X-MCP-Token", required = false) String provided,
            @RequestBody(required = false) Map<String, Object> body) {
        if (token.isEmpty()) {
            return ResponseEntity.status(503).body(err(
                    "MCP 写入口未启用：请设置环境变量 COSME_MCP_TOKEN 后重启后端"));
        }
        if (provided == null || !token.equals(provided.trim())) {
            return ResponseEntity.status(401).body(err("MCP 令牌不正确"));
        }

        String name = body == null || body.get("name") == null ? "" : String.valueOf(body.get("name")).trim();
        if (name.isEmpty()) return bad("缺少 name");
        if (name.length() > 40) return bad("成分名过长（最多 40 字）");

        String context = body.get("context") == null ? "" : String.valueOf(body.get("context"));
        if (context.length() > 600) context = context.substring(0, 600);

        if (!allow()) {
            return ResponseEntity.status(429).body(err(
                    "提交太频繁：10 分钟内最多 " + WINDOW_LIMIT + " 次。研究要花模型调用，不能刷。"));
        }

        List<String> queries = Collections.singletonList(name);
        ResearchTaskStore.EnqueueResult result = store.enqueue(queries, context);
        ResearchTask task = store.findByQuery(name);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("name", name);
        out.put("status", task == null ? "unknown" : task.status);
        out.put("taskId", task == null ? "" : task.id);
        out.put("created", result.created);
        out.put("researchEnabled", research.enabled());
        out.put("message", research.enabled()
                ? "已进入研究队列，后台 Agent 会查证并自动收录；收录后重新分析即可看到"
                : "已入队，但研究 API 尚未配置，暂时不会自动处理");
        return ResponseEntity.ok(out);
    }

    /** 滑动窗口限流：只保留最近 10 分钟内的提交时间。 */
    private synchronized boolean allow() {
        long now = System.currentTimeMillis();
        while (!recent.isEmpty() && now - recent.peekFirst() > WINDOW_MS) {
            recent.pollFirst();
        }
        if (recent.size() >= WINDOW_LIMIT) return false;
        recent.addLast(now);
        return true;
    }

    private ResponseEntity<?> bad(String message) {
        return ResponseEntity.badRequest().body(err(message));
    }

    private Map<String, Object> err(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error", message);
        return Collections.unmodifiableMap(map);
    }
}
