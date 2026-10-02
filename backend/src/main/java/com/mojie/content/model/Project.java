package com.mojie.content.model;

import com.mojie.common.model.QueryResult;
import java.util.*;

public record Project(
    String id, String title, String activeRunId, String createdAt, String updatedAt)
    implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("id", id);
    fields.put("title", title);
    fields.put("activeRunId", activeRunId);
    fields.put("createdAt", createdAt);
    fields.put("updatedAt", updatedAt);
    return fields;
  }
}
