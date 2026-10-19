package com.mojie.content.model;

import com.mojie.common.model.QueryResult;
import java.util.*;

public record ProjectSummary(
    String id, String title, String updatedAt, long pendingProposalCount, long conflictedProposalCount)
    implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("id", id);
    fields.put("title", title);
    fields.put("updatedAt", updatedAt);
    fields.put("pendingProposalCount", pendingProposalCount);
    fields.put("conflictedProposalCount", conflictedProposalCount);
    return fields;
  }
}
