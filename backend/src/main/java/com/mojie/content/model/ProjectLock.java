package com.mojie.content.model;

import com.mojie.common.model.QueryResult;
import java.util.*;

public record ProjectLock(String id, String activeRunId) implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("id", id);
    fields.put("activeRunId", activeRunId);
    return fields;
  }
}
