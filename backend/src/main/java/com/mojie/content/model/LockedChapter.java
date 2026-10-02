package com.mojie.content.model;

import com.mojie.common.model.QueryResult;
import java.util.*;

public record LockedChapter(String id, String projectId, int version, String content)
    implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("id", id);
    fields.put("projectId", projectId);
    fields.put("version", version);
    fields.put("content", content);
    return fields;
  }
}
