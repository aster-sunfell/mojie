package com.mojie.content.model;

import com.mojie.common.model.QueryResult;
import java.util.*;

public record ChapterRevision(
    String chapterId,
    int version,
    String content,
    String source,
    String proposalId,
    String createdAt)
    implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("chapterId", chapterId);
    fields.put("version", version);
    fields.put("content", content);
    fields.put("source", source);
    fields.put("proposalId", proposalId);
    fields.put("createdAt", createdAt);
    return fields;
  }
}
