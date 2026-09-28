package com.mojie.agent.tool;

import java.util.List;
import java.util.Map;

/** Scoped content service adapter. Implementations must enforce project ownership and immutable revisions. */
public interface ContentRepository {
    List<ToolContext.Chapter> chapters(String projectId);
    ToolContext.Chapter chapterRevision(String projectId, String chapterId, long version);
    /** Captured in one consistent read, then durably saved by the repository before returning. */
    ChapterView captureChapterView(String runId, String projectId, String targetId, long baseVersion,
                                   Map<String, ToolContext.Chapter> alreadyRead);
    List<Setting> settings(String projectId);
    record ChapterView(String viewId, Map<String, Long> versions) {
        public ChapterView { versions = Map.copyOf(versions); }
    }
    record Setting(String id, String type, String title, String content) {}
}
