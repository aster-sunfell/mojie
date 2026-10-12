package com.mojie.agent.model;

import com.mojie.common.model.QueryResult;
import java.util.LinkedHashMap;
import java.util.Map;

public record RunProposal(
    String proposalId,
    String targetType,
    String operation,
    String targetChapterId,
    String targetSettingId,
    int baseVersion,
    String targetTitle,
    String proposalStatus,
    String createdChapterId,
    String createdSettingId)
    implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("proposalId", proposalId);
    value.put("targetType", targetType);
    value.put("operation", operation);
    value.put("targetChapterId", targetChapterId);
    value.put("targetSettingId", targetSettingId);
    value.put("baseVersion", baseVersion);
    value.put("targetTitle", targetTitle);
    value.put("proposalStatus", proposalStatus);
    value.put("createdChapterId", createdChapterId);
    value.put("createdSettingId", createdSettingId);
    return value;
  }
}
