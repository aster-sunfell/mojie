package com.mojie.content.model;

import com.mojie.common.model.QueryResult;
import java.util.*;

public record Setting(
    String id,
    String projectId,
    String type,
    String title,
    String content,
    int version,
    String updatedAt)
    implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("id", id);
    fields.put("projectId", projectId);
    fields.put("type", type);
    fields.put("title", title);
    fields.put("content", content);
    fields.put("version", version);
    fields.put("updatedAt", updatedAt);
    return fields;
  }
}
