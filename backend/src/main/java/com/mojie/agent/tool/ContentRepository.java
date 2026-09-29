package com.mojie.agent.tool;

import java.util.List;
import java.util.Map;

/** Scoped content service adapter. Implementations must enforce project ownership and immutable revisions. */
public interface ContentRepository {
    List<ChapterMetadata> chapters(String projectId);
    default List<ChapterMetadata> chapters(String projectId, long timeoutMillis) { return chapters(projectId); }
    ToolContext.Chapter chapterRevision(String projectId, String chapterId, long version);
    default ToolContext.Chapter chapterRevision(String projectId, String chapterId, long version, long timeoutMillis) {
        return chapterRevision(projectId, chapterId, version);
    }
    /** Captured in one consistent read, then durably saved by the repository before returning. */
    ChapterView captureChapterView(String runId, String projectId, String targetId, long baseVersion,
                                   Map<String, ToolContext.Chapter> alreadyRead);
    default ChapterView captureChapterView(String runId, String projectId, String targetId, long baseVersion,
                                           Map<String, ToolContext.Chapter> alreadyRead, long timeoutMillis) {
        return captureChapterView(runId, projectId, targetId, baseVersion, alreadyRead);
    }
    List<Setting> settings(String projectId);
    default List<Setting> settings(String projectId, long timeoutMillis) { return settings(projectId); }
    final class SearchTimeoutException extends RuntimeException { public SearchTimeoutException(Throwable cause) { super("Search timed out", cause); } }
    record ChapterView(String viewId, Map<String, Long> versions) {
        public ChapterView { versions = Map.copyOf(versions); }
    }
    record Setting(String id, String type, String title, String content) {}
    record ChapterMetadata(String id, String title, int order, long version) {}
}
