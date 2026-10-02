package com.mojie.content.model;

import com.mojie.common.model.QueryResult;
import java.util.*;

public record ChapterIdentity(String id) implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("id", id);
    return fields;
  }
}
