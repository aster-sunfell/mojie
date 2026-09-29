package com.mojie.agent.model;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ModelApiConfigTest {
    @Test void genericValuesTakePrecedenceIndependently() {
        ModelApiConfig genericBase = ModelApiConfig.from(Map.of("ANTHROPIC_API_KEY", "old-key",
                "MOJIE_MODEL_BASE_URL", "https://generic.example", "MOJIE_ANTHROPIC_BASE_URL", "https://legacy.example"));
        assertEquals("https://generic.example", genericBase.baseUrl());
        assertEquals("old-key", genericBase.apiKey());
        ModelApiConfig genericKey = ModelApiConfig.from(Map.of("MOJIE_MODEL_API_KEY", "new-key",
                "ANTHROPIC_API_KEY", "old-key", "MOJIE_ANTHROPIC_BASE_URL", "https://legacy.example",
                "MOJIE_MODEL_ID", "model", "MOJIE_MODEL_CONTEXT_TOKENS", "32768"));
        assertEquals("new-key", genericKey.apiKey());
        assertEquals("https://legacy.example", genericKey.baseUrl());
        assertEquals(32_768, genericKey.contextWindowTokens());
    }
    @Test void bothPathsUseTheSameGenericCredentials() {
        for (String path : new String[]{ModelApiConfig.MESSAGES, ModelApiConfig.CHAT_COMPLETIONS}) {
            ModelApiConfig config = ModelApiConfig.from(Map.of(
                    "MOJIE_MODEL_API_PATH", path,
                    "MOJIE_MODEL_BASE_URL", "https://api.siliconflow.cn/",
                    "MOJIE_MODEL_API_KEY", "secret",
                    "MOJIE_MODEL_ID", "deepseek-ai/DeepSeek-V4-Flash"));
            assertTrue(config.configured());
            assertEquals(path, config.apiPath());
            assertEquals("https://api.siliconflow.cn", config.baseUrl());
            assertEquals(1_000_000, config.contextWindowTokens());
            assertFalse(config.toString().contains("secret"));
        }
    }

    @Test void invalidPathAndBaseUrlFailBeforeAnyRun() {
        IllegalArgumentException path = assertThrows(IllegalArgumentException.class,
                () -> ModelApiConfig.from(Map.of("MOJIE_MODEL_API_PATH", "/v1/responses")));
        assertTrue(path.getMessage().contains("/v1/responses"));
        assertTrue(path.getMessage().contains(ModelApiConfig.CHAT_COMPLETIONS));
        assertThrows(IllegalArgumentException.class, () -> ModelApiConfig.from(Map.of(
                "MOJIE_MODEL_BASE_URL", "https://api.siliconflow.cn/v1")));
        assertThrows(IllegalArgumentException.class, () -> ModelApiConfig.from(Map.of(
                "MOJIE_MODEL_API_KEY", "secret", "MOJIE_MODEL_ID", "model")));
        assertThrows(IllegalArgumentException.class, () -> ModelApiConfig.from(Map.of(
                "MOJIE_MODEL_API_PATH", ModelApiConfig.CHAT_COMPLETIONS,
                "ANTHROPIC_API_KEY", "old-key")));
        assertThrows(IllegalArgumentException.class, () -> ModelApiConfig.from(Map.of(
                "MOJIE_MODEL_API_KEY", "secret", "MOJIE_MODEL_BASE_URL", "https://api.example",
                "MOJIE_MODEL_ID", "unknown-model")));
        assertThrows(IllegalArgumentException.class, () -> ModelApiConfig.from(Map.of(
                "MOJIE_MODEL_API_KEY", "secret", "MOJIE_MODEL_BASE_URL", "https://api.example",
                "MOJIE_MODEL_ID", "unknown-model", "MOJIE_MODEL_CONTEXT_TOKENS", "invalid")));
    }

    @Test void legacyMessagesConfigurationRemainsAvailable() {
        ModelApiConfig config = ModelApiConfig.from(Map.of("ANTHROPIC_API_KEY", "old-key",
                "MOJIE_ANTHROPIC_BASE_URL", "http://127.0.0.1:9000"));
        assertEquals(ModelApiConfig.MESSAGES, config.apiPath());
        assertEquals("old-key", config.apiKey());
        assertEquals("http://127.0.0.1:9000", config.baseUrl());
        assertEquals("claude-sonnet-4-5", config.modelId());
        assertEquals(200_000, config.contextWindowTokens());
        assertFalse(ModelApiConfig.from(Map.of("MOJIE_MODEL_API_PATH", ModelApiConfig.CHAT_COMPLETIONS)).configured());
    }
}
