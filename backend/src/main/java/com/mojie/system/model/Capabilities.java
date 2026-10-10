package com.mojie.system.model;

public record Capabilities(
    boolean agentConfigured,
    String modelId,
    boolean modelMetadataAvailable,
    int maxInstructionCodePoints,
    int maxChapterCodePoints,
    int maxSettingCodePoints,
    int maxModelCalls,
    int maxToolCalls) {}
