package com.mojie.agent.model;

import com.mojie.common.model.QueryResult;
import java.util.*;

public record RunSnapshot(
    String id,
    String projectId,
    String targetChapterId,
    int baseVersion,
    String instruction,
    String status,
    boolean cancelRequested,
    String queuedAt,
    String startedAt,
    String finishedAt,
    String analysis,
    String errorCode,
    String errorDetail,
    int modelCallCount,
    int toolCallCount,
    int toolTextCodepoints,
    long lastEventSeq,
    String proposalId,
    String conversationId,
    String taskType,
    com.fasterxml.jackson.databind.JsonNode configSnapshot,
    String contextChapterId,
    Long contextVersion,
    String targetType,
    String targetSettingId,
    String targetTitle)
    implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("id", id);
    fields.put("conversationId", conversationId);
    fields.put("taskType", taskType);
    fields.put("contextChapterId", contextChapterId);
    fields.put("contextVersion", contextVersion);
    if (configSnapshot != null)
      fields.put(
          "budget",
          Map.of(
              "maxModelCalls",
              configSnapshot.path("maxModelCalls").asInt(8),
              "maxToolCalls",
              configSnapshot.path("maxToolCalls").asInt(12),
              "maxToolTextCodePoints",
              configSnapshot.path("maxToolTextCodePoints").asInt(30000)));
    fields.put("projectId", projectId);
    fields.put("targetChapterId", targetChapterId);
    fields.put("targetType", targetType);
    fields.put("targetSettingId", targetSettingId);
    fields.put("targetTitle", targetTitle);
    fields.put("baseVersion", baseVersion);
    fields.put("instruction", instruction);
    fields.put("status", status);
    fields.put("cancelRequested", cancelRequested);
    fields.put("queuedAt", queuedAt);
    fields.put("startedAt", startedAt);
    fields.put("finishedAt", finishedAt);
    fields.put("analysis", analysis);
    fields.put("errorCode", errorCode);
    fields.put("errorDetail", errorDetail);
    fields.put("modelCallCount", modelCallCount);
    fields.put("toolCallCount", toolCallCount);
    fields.put("toolTextCodepoints", toolTextCodepoints);
    fields.put("lastEventSeq", lastEventSeq);
    fields.put("proposalId", proposalId);
    return fields;
  }
}
