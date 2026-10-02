package com.mojie.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.mojie.common.model.QueryResult;
import java.util.*;

public record RunInputRow(
    String id,
    String projectId,
    String targetChapterId,
    int baseVersion,
    String baseContent,
    String instruction,
    String content,
    String modelId,
    JsonNode configSnapshot,
    String deadlineAt)
    implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("id", id);
    fields.put("projectId", projectId);
    fields.put("targetChapterId", targetChapterId);
    fields.put("baseVersion", baseVersion);
    fields.put("baseContent", baseContent);
    fields.put("instruction", instruction);
    fields.put("content", content);
    fields.put("modelId", modelId);
    fields.put("configSnapshot", configSnapshot);
    fields.put("deadlineAt", deadlineAt);
    return fields;
  }
}
