package com.mojie.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.mojie.common.model.QueryResult;
import java.util.*;

public record RunStep(
    String toolExecutionId,
    String toolCallId,
    String toolName,
    JsonNode args,
    JsonNode result,
    String summary,
    String status,
    String errorCode,
    String startedAt,
    String finishedAt,
    int modelCallIndex)
    implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("toolExecutionId", toolExecutionId);
    fields.put("toolCallId", toolCallId);
    fields.put("toolName", toolName);
    fields.put("args", args);
    fields.put("result", result);
    fields.put("summary", summary);
    fields.put("status", status);
    fields.put("errorCode", errorCode);
    fields.put("startedAt", startedAt);
    fields.put("finishedAt", finishedAt);
    fields.put("modelCallIndex", modelCallIndex);
    return fields;
  }
}
