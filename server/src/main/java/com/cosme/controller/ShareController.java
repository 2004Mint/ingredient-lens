package com.cosme.controller;

import com.cosme.service.ReportStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;

/**
 * 分享报告：生成一个不可猜测的短链接，读的时候不需要登录。
 * 写入时做基本大小/结构校验，避免把任意大对象塞进本地磁盘。
 */
@RestController
@RequestMapping("/api/reports")
public class ShareController {

    private final ReportStore reportStore;

    public ShareController(ReportStore reportStore) {
        this.reportStore = reportStore;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) return bad("缺少报告内容");

        Object text = body.get("text");
        if (!(text instanceof String) || ((String) text).trim().isEmpty()) {
            return bad("缺少成分表原文，无法分享");
        }
        String ingredientText = ((String) text).trim();
        if (ingredientText.length() > 5000) {
            return bad("成分表太长，无法分享");
        }

        Object report = body.get("report");
        if (!(report instanceof Map)) {
            return bad("缺少解读结果，无法分享");
        }

        Object name = body.get("name");
        String productName = name == null ? "" : name.toString().trim();
        if (productName.length() > 80) {
            return bad("产品名太长");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", productName);
        payload.put("text", ingredientText);
        payload.put("profile", body.get("profile") instanceof Map
                ? body.get("profile") : Collections.emptyMap());
        payload.put("report", report);
        payload.put("meta", body.get("meta") instanceof Map
                ? body.get("meta") : Collections.emptyMap());

        try {
            String id = reportStore.save(payload);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("id", id);
            return ResponseEntity.ok(out);
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(err("保存分享报告失败，请稍后重试"));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        try {
            Map<String, Object> data = reportStore.load(id);
            if (data == null) {
                return ResponseEntity.status(404).body(err("分享链接不存在或已过期"));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.putAll(data);
            return ResponseEntity.ok(out);
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(err("读取分享报告失败"));
        }
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
