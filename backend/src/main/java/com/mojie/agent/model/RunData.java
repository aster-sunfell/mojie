package com.mojie.agent.model;

import java.time.Instant;

public record RunData(
    String runId,
    String projectId,
    String targetChapterId,
    int baseVersion,
    String baseContent,
    String instruction,
    String systemPrompt,
    String modelId,
    int maxOutputTokens,
    int contextWindowTokens,
    Instant deadline) {}
