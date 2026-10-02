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
    String reviewDecisions) {}
