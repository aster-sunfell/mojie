package com.mojie.proposal.model;

public record EditProposal(
    String id,
    String projectId,
    String targetChapterId,
    int baseVersion,
    String proposedContent,
    String status,
    Integer appliedVersion,
    String baseContent,
    String reviewDecisions,
    String targetType,
    String targetSettingId,
    String baseType,
    String baseTitle,
    String proposedType,
    String proposedTitle) {}
