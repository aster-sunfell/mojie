package com.mojie.agent.service.tool;

import com.mojie.agent.model.ChapterView;
import com.mojie.agent.repository.ChapterViewRepository;
import com.mojie.common.error.StorageException;
import com.mojie.common.transaction.TransactionRunner;
import com.mojie.content.model.ChapterVersionRow;
import com.mojie.content.model.ReadContent;
import com.mojie.content.service.ContentReadService;
import java.util.*;
import java.util.function.Supplier;

public final class ToolContentService implements ToolContentReader {
  private final ContentReadService content;
  private final ChapterViewRepository views;
  private final TransactionRunner transactions;

  public ToolContentService(
      ContentReadService content, ChapterViewRepository views, TransactionRunner transactions) {
    this.content = content;
    this.views = views;
    this.transactions = transactions;
  }

  private <T> T read(Supplier<T> work) {
    try {
      return work.get();
    } catch (StorageException e) {
      if (e.hasSqlState("57014")) throw new SearchTimeoutException(e);
      throw e;
    }
  }

  @Override
  public List<ChapterMetadata> chapters(String id) {
    return chapters(id, 5_000, null);
  }

  @Override
  public List<ChapterMetadata> chapters(String id, long timeout) {
    return chapters(id, timeout, null);
  }

  @Override
  public List<ChapterMetadata> chapters(String id, long timeout, ToolContext context) {
    return read(
        () ->
            content.chapters(id, timeout, context).stream()
                .map(c -> new ChapterMetadata(c.id(), c.title(), c.order(), c.version()))
                .toList());
  }

  @Override
  public ToolContext.Chapter chapterRevision(String project, String id, long version) {
    return chapterRevision(project, id, version, 5_000);
  }

  @Override
  public ToolContext.Chapter chapterRevision(
      String project, String id, long version, long timeout) {
    return read(() -> chapter(content.revision(project, id, version, timeout)));
  }

  @Override
  public List<ToolContext.Chapter> chapterRevisions(
      String project, List<ChapterVersion> chapters, long timeout) {
    return chapterRevisions(project, chapters, timeout, null);
  }

  @Override
  public List<ToolContext.Chapter> chapterRevisions(
      String project, List<ChapterVersion> chapters, long timeout, ToolContext context) {
    return read(
        () ->
            content
                .revisions(
                    project,
                    chapters.stream()
                        .map(c -> new ReadContent.ChapterVersion(c.id(), c.version()))
                        .toList(),
                    timeout,
                    context)
                .stream()
                .map(ToolContentService::chapter)
                .toList());
  }

  @Override
  public List<Setting> settings(String project) {
    return settings(project, 5_000, null);
  }

  @Override
  public List<Setting> settings(String project, long timeout) {
    return settings(project, timeout, null);
  }

  @Override
  public List<Setting> settings(String project, long timeout, ToolContext context) {
    return read(
        () ->
            content.settings(project, timeout, context).stream()
                .map(v -> new Setting(v.id(), v.type(), v.title(), v.content()))
                .toList());
  }

  @Override
  public ChapterView captureChapterView(
      String run,
      String project,
      String target,
      long base,
      Map<String, ToolContext.Chapter> prior) {
    return captureChapterView(run, project, target, base, prior, 5_000, null);
  }

  @Override
  public ChapterView captureChapterView(
      String run,
      String project,
      String target,
      long base,
      Map<String, ToolContext.Chapter> prior,
      long timeout) {
    return captureChapterView(run, project, target, base, prior, timeout, null);
  }

  @Override
  public ChapterView captureChapterView(
      String run,
      String project,
      String target,
      long base,
      Map<String, ToolContext.Chapter> prior,
      long timeout,
      ToolContext context) {
    List<ChapterVersionRow> snapshot;
    try {
      snapshot = content.versions(project, timeout, context);
    } catch (StorageException e) {
      if (e.hasSqlState("57014")) throw new SearchTimeoutException(e);
      throw e;
    }
    Map<String, Long> proposed = new LinkedHashMap<>();
    for (ChapterVersionRow chapter : snapshot) {
      ToolContext.Chapter seen = prior.get(chapter.id());
      proposed.put(
          chapter.id(),
          chapter.id().equals(target) ? base : seen == null ? chapter.version() : seen.version());
    }
    if (target != null && !proposed.containsKey(target))
      throw new IllegalStateException("Target chapter is unavailable");
    return read(
        () ->
            transactions.requiresNew(
                () -> {
                  if (!"RUNNING".equals(views.lockRun(run, project, timeout, context)))
                    throw new IllegalStateException("Run is no longer active");
                  ChapterView existing = views.find(run, context);
                  if (existing != null) return existing;
                  UUID id = UUID.randomUUID();
                  views.insert(run, id, snapshot, proposed, context);
                  return new ChapterView(id.toString(), proposed);
                }));
  }

  private static ToolContext.Chapter chapter(ReadContent.ChapterText value) {
    return value == null
        ? null
        : new ToolContext.Chapter(
            value.id(), value.title(), value.order(), value.version(), value.content());
  }
}
