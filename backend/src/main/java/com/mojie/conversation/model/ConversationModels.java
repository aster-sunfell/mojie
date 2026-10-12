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
      boolean discoveryEnabled,
      String createdAt,
      String updatedAt) {}

  public record DisplayMessage(
      String id,
      String conversationId,
      long seq,
      String role,
      String content,
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
      String contextContent) {}

  public record Summary(String id, long coveredSeq, String summary) {}

  public record Selection(String skillId, String versionId) {}

  public record Send(
      String content,
      String clientRequestId,
      Long expectedConversationSeq,
      String taskType,
      String targetChapterId,
      Long expectedVersion,
      List<Selection> selectedSkills,
      String contextChapterId,
      Long contextVersion) {
    public Send(
        String content,
        String clientRequestId,
        Long expectedConversationSeq,
        String taskType,
        String targetChapterId,
        Long expectedVersion,
        List<Selection> selectedSkills) {
      this(
          content,
          clientRequestId,
          expectedConversationSeq,
          taskType,
          targetChapterId,
          expectedVersion,
          selectedSkills,
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
