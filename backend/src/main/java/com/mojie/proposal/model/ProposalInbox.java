package com.mojie.proposal.model;

import com.fasterxml.jackson.databind.JsonNode;

public final class ProposalInbox {
  private ProposalInbox() {}

  public record Counts(long pendingCount, long conflictedCount, long targetCount) {}

  public record Entry(
      String id,
      String runId,
      String conversationId,
      String conversationTitle,
      String targetType,
      String operation,
      String targetChapterId,
      String targetSettingId,
      String targetTitle,
      Integer chapterOrder,
      int baseVersion,
      String status,
      String reason,
      String createdAt,
      String baseContent,
      String proposedContent,
      JsonNode reviewDecisions) {}
}
