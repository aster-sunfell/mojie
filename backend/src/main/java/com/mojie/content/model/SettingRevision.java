package com.mojie.content.model;

public record SettingRevision(
    int version,
    String type,
    String title,
    String content,
    String source,
    String proposalId,
    String createdAt) {}
