package com.mojie.content.model;

import com.mojie.common.model.QueryResult;
import java.util.*;

public record CreatedChapter(
    String id, String projectId, int chapterOrder, String title, String content, int version)
    implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("id", id);
    fields.put("projectId", projectId);
    fields.put("chapterOrder", chapterOrder);
    fields.put("title", title);
    fields.put("content", content);
    fields.put("version", version);
    return fields;
  }
}
