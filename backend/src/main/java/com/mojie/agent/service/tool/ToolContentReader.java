package com.mojie.agent.service.tool;

import com.mojie.agent.model.ChapterView;
import java.util.List;
import java.util.Map;

/**
 * Scoped content service adapter. Implementations must enforce project scope and immutable
 * revisions.
 */
public interface ToolContentReader {
  List<ChapterMetadata> chapters(String projectId);

  default List<ChapterMetadata> chapters(String projectId, long timeoutMillis) {
    return chapters(projectId);
  }

  default List<ChapterMetadata> chapters(
      String projectId, long timeoutMillis, ToolContext context) {
    return chapters(projectId, timeoutMillis);
  }

  ToolContext.Chapter chapterRevision(String projectId, String chapterId, long version);

  default ToolContext.Chapter chapterRevision(
      String projectId, String chapterId, long version, long timeoutMillis) {
    return chapterRevision(projectId, chapterId, version);
  }

  default List<ToolContext.Chapter> chapterRevisions(
      String projectId, List<ChapterVersion> chapters, long timeoutMillis) {
    return chapters.stream()
        .map(chapter -> chapterRevision(projectId, chapter.id(), chapter.version(), timeoutMillis))
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  default List<ToolContext.Chapter> chapterRevisions(
      String projectId, List<ChapterVersion> chapters, long timeoutMillis, ToolContext context) {
    return chapterRevisions(projectId, chapters, timeoutMillis);
  }

  /** Captured in one consistent read, then durably saved by the repository before returning. */
  ChapterView captureChapterView(
      String runId,
      String projectId,
      String targetId,
      long baseVersion,
      Map<String, ToolContext.Chapter> alreadyRead);

  default ChapterView captureChapterView(
      String runId,
      String projectId,
      String targetId,
      long baseVersion,
      Map<String, ToolContext.Chapter> alreadyRead,
      long timeoutMillis) {
    return captureChapterView(runId, projectId, targetId, baseVersion, alreadyRead);
  }

  default ChapterView captureChapterView(
      String runId,
      String projectId,
      String targetId,
      long baseVersion,
      Map<String, ToolContext.Chapter> alreadyRead,
      long timeoutMillis,
      ToolContext context) {
    return captureChapterView(runId, projectId, targetId, baseVersion, alreadyRead, timeoutMillis);
  }

  List<Setting> settings(String projectId);

  default List<Setting> settings(String projectId, long timeoutMillis) {
    return settings(projectId);
  }

  default List<Setting> settings(String projectId, long timeoutMillis, ToolContext context) {
    return settings(projectId, timeoutMillis);
  }

  final class SearchTimeoutException extends RuntimeException {
    public SearchTimeoutException(Throwable cause) {
      super("Search timed out", cause);
    }
  }

  record Setting(String id, String type, String title, String content) {}

  record ChapterMetadata(String id, String title, int order, long version) {}

  record ChapterVersion(String id, long version) {}
}
