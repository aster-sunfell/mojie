package com.mojie.content.model;

import com.mojie.common.model.QueryResult;
import java.util.*;

public record ChapterOrder(int chapterOrder) implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("chapterOrder", chapterOrder);
    return fields;
  }
}
