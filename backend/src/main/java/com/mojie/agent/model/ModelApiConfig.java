package com.mojie.agent.model;

import java.net.URI;
import java.util.Map;

/** Validated, process-wide selection of one model wire protocol. */
public record ModelApiConfig(String apiPath, String baseUrl, String apiKey, String modelId,
                             int contextWindowTokens) {
    public static final String MESSAGES = "/v1/messages";
    public static final String CHAT_COMPLETIONS = "/v1/chat/completions";

    public static ModelApiConfig fromEnvironment() { return from(System.getenv()); }

    public static ModelApiConfig from(Map<String, String> env) {
        String path = value(env, "MOJIE_MODEL_API_PATH");
        if (path == null) path = MESSAGES;
        if (!path.equals(MESSAGES) && !path.equals(CHAT_COMPLETIONS))
            throw new IllegalArgumentException("Unsupported MOJIE_MODEL_API_PATH: " + path
                    + "; supported: " + MESSAGES + ", " + CHAT_COMPLETIONS);

        String key = value(env, "MOJIE_MODEL_API_KEY");
        String base = value(env, "MOJIE_MODEL_BASE_URL");
        if (path.equals(MESSAGES)) {
            if (key == null) key = value(env, "ANTHROPIC_API_KEY");
            if (base == null) base = value(env, "MOJIE_ANTHROPIC_BASE_URL");
            if (base == null && key != null && value(env, "MOJIE_MODEL_API_KEY") == null)
                base = "https://api.anthropic.com";
        }
        if (key != null && base == null)
            throw new IllegalArgumentException("MOJIE_MODEL_BASE_URL is required with MOJIE_MODEL_API_KEY");
        if (key == null && path.equals(CHAT_COMPLETIONS) && value(env, "ANTHROPIC_API_KEY") != null)
            throw new IllegalArgumentException("MOJIE_MODEL_API_KEY is required for " + CHAT_COMPLETIONS);
        if (base != null) base = normalizeBaseUrl(base);
        String model = value(env, "MOJIE_MODEL_ID");
        if (model == null && value(env, "MOJIE_MODEL_API_KEY") == null && path.equals(MESSAGES))
            model = "claude-sonnet-4-5";
        if (key != null && model == null)
            throw new IllegalArgumentException("MOJIE_MODEL_ID is required for " + path);
        if (model == null) model = "unconfigured";
        String rawWindow = value(env, "MOJIE_MODEL_CONTEXT_TOKENS");
        int contextWindowTokens;
        if (rawWindow != null) {
            try { contextWindowTokens = Integer.parseInt(rawWindow); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("MOJIE_MODEL_CONTEXT_TOKENS must be a positive integer", e); }
            if (contextWindowTokens <= 0) throw new IllegalArgumentException("MOJIE_MODEL_CONTEXT_TOKENS must be a positive integer");
        } else if ("deepseek-ai/DeepSeek-V4-Flash".equals(model)) {
            contextWindowTokens = 1_000_000;
        } else if ("claude-sonnet-4-5".equals(model)) {
            contextWindowTokens = 200_000;
        } else if (key == null) {
            contextWindowTokens = 200_000;
        } else {
            throw new IllegalArgumentException("MOJIE_MODEL_CONTEXT_TOKENS is required for model " + model);
        }
        return new ModelApiConfig(path, base, key, model, contextWindowTokens);
    }

    public boolean configured() { return apiKey != null; }

    @Override public String toString() {
        return "ModelApiConfig[apiPath=" + apiPath + ", baseUrl=" + baseUrl
                + ", apiKey=<redacted>, modelId=" + modelId
                + ", contextWindowTokens=" + contextWindowTokens + "]";
    }

    private static String value(Map<String, String> env, String name) {
        String raw = env.get(name);
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private static String normalizeBaseUrl(String raw) {
        try {
            URI uri = URI.create(raw);
            if (!(uri.getScheme().equals("http") || uri.getScheme().equals("https"))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))
                    || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
            return uri.toString().replaceAll("/+$", "");
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("MOJIE_MODEL_BASE_URL must be an HTTP(S) origin without /v1 or an API path");
        }
    }
}
