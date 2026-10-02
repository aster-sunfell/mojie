package com.mojie.agent.model;

import com.mojie.common.model.QueryResult;
import java.util.*;

public record RunSource(
    String id,
    String kind,
    String chapterId,
    Integer chapterVersion,
    String settingId,
    Integer lineNumber,
    Integer codepointOffset,
    String contentHash,
    String toolExecutionId,
    boolean providedToModel)
    implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("id", id);
    fields.put("kind", kind);
    fields.put("chapterId", chapterId);
    fields.put("chapterVersion", chapterVersion);
    fields.put("settingId", settingId);
    fields.put("lineNumber", lineNumber);
    fields.put("codepointOffset", codepointOffset);
    fields.put("contentHash", contentHash);
    fields.put("toolExecutionId", toolExecutionId);
    fields.put("providedToModel", providedToModel);
    return fields;
  }
}
