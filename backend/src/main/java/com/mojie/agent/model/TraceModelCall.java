package com.mojie.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.mojie.common.model.QueryResult;
import java.util.*;

public record TraceModelCall(
    String id,
    int callIndex,
    List<Long> inputMessageSeqs,
    String status,
    String finishReason,
    String rawFinishReason,
    String providerRequestId,
    String errorCode,
    String errorDetail,
    JsonNode usage,
    String startedAt,
    String finishedAt)
    implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("id", id);
    fields.put("callIndex", callIndex);
    fields.put("inputMessageSeqs", inputMessageSeqs);
    fields.put("status", status);
    fields.put("finishReason", finishReason);
    fields.put("rawFinishReason", rawFinishReason);
    fields.put("providerRequestId", providerRequestId);
    fields.put("errorCode", errorCode);
    fields.put("errorDetail", errorDetail);
    fields.put("usage", usage);
    fields.put("startedAt", startedAt);
    fields.put("finishedAt", finishedAt);
    return fields;
  }
}
