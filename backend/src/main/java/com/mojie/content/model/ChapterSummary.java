package com.mojie.content.model;

import com.mojie.common.model.QueryResult;
import java.util.*;

public record ChapterSummary(
    String id, String projectId, int chapterOrder, String title, int version)
    implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("id", id);
    fields.put("projectId", projectId);
    fields.put("chapterOrder", chapterOrder);
    fields.put("title", title);
    fields.put("version", version);
    return fields;
  }
}
