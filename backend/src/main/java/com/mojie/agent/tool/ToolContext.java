package com.mojie.agent.tool;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Mutable state is owned by exactly one run; shared tool instances remain stateless. */
public final class ToolContext {
    private final String runId, projectId, targetChapterId, baseContent;
    private final long baseVersion;
    private final Map<String, Chapter> readChapters = new HashMap<>();
    private Map<String, Long> chapterView;
    private String chapterViewId;
    private Candidate candidate;
    private boolean targetRead;
    private Instant deadline = Instant.MAX;
    private Clock clock = Clock.systemUTC();

    public ToolContext(String runId, String projectId, String targetChapterId, long baseVersion, String baseContent) {
        this.runId = runId; this.projectId = projectId; this.targetChapterId = targetChapterId;
        this.baseVersion = baseVersion; this.baseContent = baseContent;
    }
    public String runId() { return runId; }
    public String projectId() { return projectId; }
    public String targetChapterId() { return targetChapterId; }
    public long baseVersion() { return baseVersion; }
    public String baseContent() { return baseContent; }
    public boolean targetRead() { return targetRead; }
    public Candidate candidate() { return candidate; }
    public Map<String, Chapter> readChapters() { return readChapters; }
    public Map<String, Long> chapterView() { return chapterView; }
    public String chapterViewId() { return chapterViewId; }
    public void executionClock(Clock value, Instant deadline) { this.clock = value; this.deadline = deadline; }
    public long remainingMillis() {
        if (Instant.MAX.equals(deadline)) return Long.MAX_VALUE;
        return java.time.Duration.between(clock.instant(), deadline).toMillis();
    }
    public void setChapterView(Map<String, Long> view, String viewId) {
        if (chapterView == null) { chapterView = Map.copyOf(view); chapterViewId = viewId; }
    }
    public void markTargetRead() { targetRead = true; }
    public void setCandidate(Candidate value) { candidate = value; }
    public record Chapter(String id, String title, int order, long version, String content) {}
    public record Candidate(String proposedContent, String reason) {}
}
