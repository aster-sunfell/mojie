package com.mojie.agent.service.tool;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Mutable state is owned by exactly one run; shared tool instances remain stateless. */
public final class ToolContext implements com.mojie.common.cancellation.QueryCancellation {
  private final String runId, projectId, targetChapterId, baseContent;
  private final long baseVersion;
  private final Map<String, Chapter> readChapters = new HashMap<>();
  private final Map<String, SettingInput> readSettings = new HashMap<>();

  public Map<String, SettingInput> readSettings() {
    return readSettings;
  }

  public record SettingInput(String title, String content, String hash) {}

  private Map<String, Long> chapterView;
  private String chapterViewId;
  private Candidate candidate;
  private String toolExecutionId;

  public String toolExecutionId() {
    return toolExecutionId;
  }

  public void toolExecutionId(String value) {
    toolExecutionId = value;
  }

  private boolean targetRead, unified;
  private String preferredChapterId, preferredContent;
  private long preferredVersion;
  private final Map<String, Long> providedChapters = new HashMap<>();

  public boolean unified() {
    return unified;
  }

  public void unified(boolean value) {
    unified = value;
  }

  public void preferredChapter(String id, long version, String content) {
    preferredChapterId = id;
    preferredVersion = version;
    preferredContent = content;
  }

  public String preferredChapterId() {
    return preferredChapterId;
  }

  public long preferredVersion() {
    return preferredVersion;
  }

  public String preferredContent() {
    return preferredContent;
  }

  public void markReadsProvided() {
    readChapters.forEach((id, chapter) -> providedChapters.put(id, chapter.version()));
  }

  public boolean chapterProvided(String id, long version) {
    return Objects.equals(providedChapters.get(id), version);
  }

  private Instant deadline = Instant.MAX;
  private Clock clock = Clock.systemUTC();
  private BooleanSupplier cancelled = () -> false;
  private final AtomicReference<Runnable> activeSearchCancellation = new AtomicReference<>();

  public ToolContext(
      String runId,
      String projectId,
      String targetChapterId,
      long baseVersion,
      String baseContent) {
    this.runId = runId;
    this.projectId = projectId;
    this.targetChapterId = targetChapterId;
    this.baseVersion = baseVersion;
    this.baseContent = baseContent;
  }

  public String runId() {
    return runId;
  }

  public String projectId() {
    return projectId;
  }

  public String targetChapterId() {
    return targetChapterId;
  }

  public long baseVersion() {
    return baseVersion;
  }

  public String baseContent() {
    return baseContent;
  }

  public boolean targetRead() {
    return targetRead;
  }

  public Candidate candidate() {
    return candidate;
  }

  public Map<String, Chapter> readChapters() {
    return readChapters;
  }

  public Map<String, Long> chapterView() {
    return chapterView;
  }

  public String chapterViewId() {
    return chapterViewId;
  }

  public void executionClock(Clock value, Instant deadline, BooleanSupplier cancelled) {
    this.clock = value;
    this.deadline = deadline;
    this.cancelled = cancelled;
  }

  public boolean cancelled() {
    return cancelled.getAsBoolean();
  }

  public interface CancellationRegistration
      extends com.mojie.common.cancellation.QueryCancellation.Registration {
    @Override
    void close();
  }

  public CancellationRegistration registerSearchCancellation(Runnable action) {
    Objects.requireNonNull(action);
    if (!activeSearchCancellation.compareAndSet(null, action))
      throw new IllegalStateException("A search query is already registered");
    if (cancelled()) action.run();
    return () -> activeSearchCancellation.compareAndSet(action, null);
  }

  @Override
  public CancellationRegistration register(Runnable action) {
    return registerSearchCancellation(action);
  }

  public void cancelActiveSearch() {
    Runnable action = activeSearchCancellation.get();
    if (action != null) action.run();
  }

  public long remainingMillis() {
    if (Instant.MAX.equals(deadline)) return Long.MAX_VALUE;
    return java.time.Duration.between(clock.instant(), deadline).toMillis();
  }

  public void setChapterView(Map<String, Long> view, String viewId) {
    if (chapterView == null) {
      chapterView = Map.copyOf(view);
      chapterViewId = viewId;
    }
  }

  public void markTargetRead() {
    targetRead = true;
  }

  public void setCandidate(Candidate value) {
    candidate = value;
  }

  public record Chapter(String id, String title, int order, long version, String content) {}

  public record Candidate(
      String proposedContent,
      String reason,
      String chapterId,
      int baseVersion,
      String baseContent) {
    public Candidate(String proposedContent, String reason) {
      this(proposedContent, reason, null, 0, null);
    }
  }
}
