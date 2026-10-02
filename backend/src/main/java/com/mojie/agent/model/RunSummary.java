package com.mojie.agent.model;

import com.mojie.common.model.QueryResult;
import java.util.*;

public record RunSummary(
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
    long lastEventSeq,
    String proposalId,
    String createdAt)
    implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("id", id);
    fields.put("projectId", projectId);
    fields.put("targetChapterId", targetChapterId);
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
    fields.put("lastEventSeq", lastEventSeq);
    fields.put("proposalId", proposalId);
    fields.put("createdAt", createdAt);
    return fields;
  }
}
