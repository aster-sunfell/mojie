package com.mojie.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.mojie.common.model.QueryResult;
import java.util.*;

public record TraceMessage(
    long messageSeq,
    String role,
    JsonNode content,
    String modelCallId,
    String toolCallId,
    String toolExecutionId,
    String createdAt)
    implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("messageSeq", messageSeq);
    fields.put("role", role);
    fields.put("content", content);
    fields.put("modelCallId", modelCallId);
    fields.put("toolCallId", toolCallId);
    fields.put("toolExecutionId", toolExecutionId);
    fields.put("createdAt", createdAt);
    return fields;
  }
}
