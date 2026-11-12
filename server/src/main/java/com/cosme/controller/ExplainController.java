package com.cosme.controller;

import com.cosme.service.ExplainService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.Map;

/**
 * 生成式解释的流式入口。
 *
 * <p>用 POST + SSE 而不是 GET EventSource：报告结构较大，塞进 query 不合理；
 * 前端用 fetch + ReadableStream 读取，断开时 AbortController 直接取消。</p>
 */
@RestController
@RequestMapping("/api")
public class ExplainController {

    private final ExplainService explainService;

    public ExplainController(ExplainService explainService) {
        this.explainService = explainService;
    }

    /** A：结论解释。 */
    @PostMapping(value = "/explain/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter explainReport(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> source = body == null ? Collections.emptyMap() : body;
        return explainService.reportStream(asMap(source.get("report")), asMap(source.get("profile")));
    }

    /** B：单个成分解释，点开详情才调用。 */
    @PostMapping(value = "/explain/ingredient/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter explainIngredient(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> source = body == null ? Collections.emptyMap() : body;
        return explainService.ingredientStream(source);
    }

    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map)) return Collections.emptyMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        return map;
    }
}
