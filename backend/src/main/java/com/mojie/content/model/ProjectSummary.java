package com.mojie.content.model;

import com.mojie.common.model.QueryResult;
import java.util.*;

public record ProjectSummary(String id, String title, String updatedAt) implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("id", id);
    fields.put("title", title);
    fields.put("updatedAt", updatedAt);
    return fields;
  }
}
