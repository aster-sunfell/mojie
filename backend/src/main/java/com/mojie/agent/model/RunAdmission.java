package com.mojie.agent.model;

import java.util.Map;

public final class RunAdmission {
  private RunAdmission() {}

  public record Request(
      String projectId,
      String targetChapterId,
      Long expectedVersion,
      String instruction,
      String clientRequestId) {
    public Request(
        String projectId,
        String targetChapterId,
        long expectedVersion,
        String instruction,
        String clientRequestId) {
      this(projectId, targetChapterId, Long.valueOf(expectedVersion), instruction, clientRequestId);
    }
  }

  public record Config(
      String systemPrompt,
      String toolSchemaJson,
      String modelId,
      Map<String, Object> publicModelConfig) {}

  public record Receipt(String runId, String status, boolean created) {}

  public record Existing(String runId, String status, String digest) {}
}
