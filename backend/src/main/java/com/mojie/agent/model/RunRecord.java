package com.mojie.agent.model;

import java.time.Instant;

public record RunRecord(
    String status,
    boolean cancelRequested,
    Instant deadline,
    String targetChapterId,
    int baseVersion,
    String baseContent,
    int modelCalls,
    int toolCalls,
    int toolText) {}
