package com.mojie.conversation.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public final class ConversationModels {
  private ConversationModels() {}

  public record Conversation(
      String id,
      String projectId,
      String title,
      long lastSeq,
      long version,
      String activeRunId,
      String modelId,
      JsonNode modelConfig,
      String createdAt,
      String updatedAt) {}

  public record DisplayMessage(
      String id,
      String conversationId,
      long seq,
      String role,
      String content,
      JsonNode skillMentions,
      String runId,
      String status,
      String createdAt) {}

  public record Protocol(
      String id,
      String runId,
      long messageSeq,
      String role,
      JsonNode content,
      String toolCallId,
      String toolExecutionId,
      String runStatus,
      String finishReason,
      long turnSeq) {}

  public record RunContext(
      String conversationId,
      String projectId,
      String taskType,
      long inputBoundary,
      String triggerMessageId,
      JsonNode configSnapshot,
      String contextChapterId,
      Long contextVersion,
      String contextContent,
      boolean analysisOnly) {}

  public record Summary(String id, long coveredSeq, String summary) {}

  public record SkillMention(String skillId, String name) {
    public SkillMention(String skillId) {
      this(skillId, null);
    }
  }

  public record Send(
      String content,
      String clientRequestId,
      Long expectedConversationSeq,
      String taskType,
      String targetChapterId,
      Long expectedVersion,
      List<SkillMention> skillMentions,
      String contextChapterId,
      Long contextVersion,
      boolean analysisOnly) {
    public Send(
        String content,
        String clientRequestId,
        Long expectedConversationSeq,
        String taskType,
        String targetChapterId,
        Long expectedVersion,
        List<SkillMention> skillMentions,
        String contextChapterId,
        Long contextVersion) {
      this(
          content,
          clientRequestId,
          expectedConversationSeq,
          taskType,
          targetChapterId,
          expectedVersion,
          skillMentions,
          contextChapterId,
          contextVersion,
          false);
    }

    public Send(
        String content,
        String clientRequestId,
        Long expectedConversationSeq,
        String taskType,
        String targetChapterId,
        Long expectedVersion,
        List<SkillMention> skillMentions) {
      this(
          content,
          clientRequestId,
          expectedConversationSeq,
          taskType,
          targetChapterId,
          expectedVersion,
          skillMentions,
          null,
          null);
    }
  }

  public record Receipt(String messageId, long conversationSeq, String runId, String status) {}

  public record Existing(
      String id,
      String requestDigest,
      String triggerMessageId,
      String status,
      long seq,
      String conversationId,
      String retryOf) {}
}
