package com.mojie.proposal.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.mojie.common.model.QueryResult;
import java.util.*;

public record ProposalDetail(
    String id,
    String runId,
    String projectId,
    String targetChapterId,
    int baseVersion,
    String baseContent,
    String proposedContent,
    String reason,
    String status,
    Integer appliedVersion,
    String createdAt,
    String updatedAt,
    JsonNode reviewDecisions,
    String targetType,
    String targetSettingId,
    String baseType,
    String baseTitle,
    String proposedType,
    String proposedTitle,
    String operation,
    String createdChapterId,
    String createdSettingId)
    implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("id", id);
    fields.put("runId", runId);
    fields.put("projectId", projectId);
    fields.put("targetChapterId", targetChapterId);
    fields.put("targetType", targetType);
    fields.put("targetSettingId", targetSettingId);
    fields.put("baseType", baseType);
    fields.put("baseTitle", baseTitle);
    fields.put("proposedType", proposedType);
    fields.put("proposedTitle", proposedTitle);
    fields.put("operation", operation);
    fields.put("createdChapterId", createdChapterId);
    fields.put("createdSettingId", createdSettingId);
    fields.put("baseVersion", baseVersion);
    fields.put("baseContent", baseContent);
    fields.put("proposedContent", proposedContent);
    fields.put("reason", reason);
    fields.put("status", status);
    fields.put("appliedVersion", appliedVersion);
    fields.put("createdAt", createdAt);
    fields.put("updatedAt", updatedAt);
    fields.put("reviewDecisions", reviewDecisions);
    return fields;
  }
}
