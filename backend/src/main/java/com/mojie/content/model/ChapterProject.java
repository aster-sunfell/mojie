package com.mojie.content.model;

import com.mojie.common.model.QueryResult;
import java.util.*;

public record ChapterProject(String projectId) implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("projectId", projectId);
    return fields;
  }
}
