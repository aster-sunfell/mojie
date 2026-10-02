package com.mojie.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.mojie.common.model.QueryResult;
import java.util.*;

public record TraceStep(
    String toolExecutionId,
    String modelCallId,
    String toolCallId,
    String toolName,
    JsonNode args,
    JsonNode result,
    String status,
    String errorCode,
    String startedAt,
    String finishedAt)
    implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("toolExecutionId", toolExecutionId);
    fields.put("modelCallId", modelCallId);
    fields.put("toolCallId", toolCallId);
    fields.put("toolName", toolName);
    fields.put("args", args);
    fields.put("result", result);
    fields.put("status", status);
    fields.put("errorCode", errorCode);
    fields.put("startedAt", startedAt);
    fields.put("finishedAt", finishedAt);
    return fields;
  }
}
