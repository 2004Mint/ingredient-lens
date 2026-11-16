package com.cosme.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配置回退的回归测试。
 *
 * 锁住一件事：地址和 Key 必须成对回退。功能块里填了新地址却没填 Key 时，
 * 绝不能拿总 API 的 Key 去请求这个地址——那等于把 Key 发给第三方。
 */
class AiSettingsTest {

    @Test
    @DisplayName("新地址 + 空 Key：不得继承总 API 的 Key")
    void doesNotSendDefaultKeyToAnotherHost() {
        VisionSettings def = new VisionSettings("https://api.example.com/v1", "sk-default", "model-a");
        VisionSettings own = new VisionSettings("https://other.example.com/v1", "", "model-b");

        VisionSettings merged = AiSettings.merge(own, def);

        assertEquals("https://other.example.com/v1", merged.baseUrl);
        assertEquals("", merged.apiKey, "换地址后不能沿用总 API 的 Key");
        assertFalse(merged.usable(), "没有 Key 就是不可用，而不是悄悄用错 Key");
    }

    @Test
    @DisplayName("空覆盖块：地址、Key、模型全部回退到总 API")
    void emptyOverrideFallsBackToDefault() {
        VisionSettings def = new VisionSettings("https://api.example.com/v1", "sk-default", "model-a");
        VisionSettings merged = AiSettings.merge(new VisionSettings(), def);

        assertEquals("https://api.example.com/v1", merged.baseUrl);
        assertEquals("sk-default", merged.apiKey);
        assertEquals("model-a", merged.model);
        assertTrue(merged.usable());
    }

    @Test
    @DisplayName("同一地址只改模型：可以沿用总 API 的 Key")
    void sameEndpointKeepsKey() {
        VisionSettings def = new VisionSettings("https://api.example.com/v1", "sk-default", "model-a");
        VisionSettings own = new VisionSettings("https://api.example.com/v1", "", "model-b");

        VisionSettings merged = AiSettings.merge(own, def);

        assertEquals("model-b", merged.model);
        assertEquals("sk-default", merged.apiKey);
        assertTrue(merged.usable());
    }
}
