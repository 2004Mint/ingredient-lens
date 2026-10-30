package com.cosme.controller;

import com.cosme.service.FeedbackStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** 产品反馈：只存本机 data/feedback.jsonl，方便迭代时集中看问题。 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final FeedbackStore feedbackStore;

    public FeedbackController(FeedbackStore feedbackStore) {
        this.feedbackStore = feedbackStore;
    }

    @PostMapping
    public ResponseEntity<?> submit(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) return bad("缺少反馈内容");

        String message = value(body.get("message")).trim();
        if (message.length() < 5) return bad("请至少写 5 个字");
        if (message.length() > 1000) return bad("反馈太长了，请精简到 1000 字以内");

        String contact = value(body.get("contact")).trim();
        if (contact.length() > 80) return bad("联系方式太长");

        String page = value(body.get("page")).trim();
        if (page.length() > 200) return bad("页面参数过长");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message);
        payload.put("contact", contact);
        payload.put("page", page);

        try {
            feedbackStore.append(payload);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            return ResponseEntity.ok(out);
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(err("反馈保存失败，请稍后再试"));
        }
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private ResponseEntity<?> bad(String message) {
        return ResponseEntity.badRequest().body(err(message));
    }

    private Map<String, Object> err(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }
}
