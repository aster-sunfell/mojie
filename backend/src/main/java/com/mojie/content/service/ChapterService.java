package com.mojie.content.service;

import static com.mojie.common.model.QueryResult.fields;
import static com.mojie.content.model.ContentRules.*;
import static com.mojie.content.service.ProjectAccessService.*;

import com.mojie.common.pagination.*;
import com.mojie.common.transaction.TransactionRunner;
import com.mojie.content.repository.*;
import java.util.*;

public final class ChapterService {
  private final ProjectRepository projects;
  private final ChapterRepository chapters;
  private final RevisionRepository revisions;
  private final ProjectAccessService access;
  private final TransactionRunner transactions;
  private final PageCursor cursors;

  public ChapterService(
      ProjectRepository projects,
      ChapterRepository chapters,
      RevisionRepository revisions,
      ProjectAccessService access,
      TransactionRunner transactions,
      PageCursor cursors) {
    this.projects = projects;
    this.chapters = chapters;
    this.revisions = revisions;
    this.access = access;
    this.transactions = transactions;
    this.cursors = cursors;
  }

  public List<Map<String, Object>> chapters(String owner, String id, Integer requestedLimit) {
    return chapterPage(owner, id, null, requestedLimit).items();
  }

  public Page<Map<String, Object>> chapterPage(
      String owner, String id, String cursor, Integer requestedLimit) {
    String projectId = uuid(id);
    int size = limit(requestedLimit);
    String scope = "chapters:" + owner + ":" + projectId;
    String[] marker = pair(cursors.decode(scope, cursor));
    Integer order = null;
    if (marker[0] != null) {
      try {
        order = Integer.parseInt(marker[0]);
        UUID.fromString(marker[1]);
        if (order < 1) throw new IllegalArgumentException();
      } catch (RuntimeException e) {
        throw new IllegalArgumentException("Invalid cursor");
      }
    }
    final Integer afterOrder = order;
    return transactions.required(
        () -> {
          required(fields(projects.findOwnedId(owner, projectId)));
          return page(
              fields(chapters.findPage(projectId, afterOrder, marker[1], size + 1)),
              size,
              scope,
              item -> item.get("chapterOrder") + "|" + item.get("id"),
              cursors);
        });
  }

  public Map<String, Object> chapter(String owner, String id) {
    String chapterId = uuid(id);
    return transactions.required(() -> required(fields(chapters.findOwned(owner, chapterId))));
  }

  public Map<String, Object> createChapter(String owner, String id, String title) {
    String projectId = uuid(id), name = text(title, "title", 1, 120);
    return transactions.required(
        () -> {
          access.lock(owner, projectId, true);
          Map<String, Object> order = required(fields(projects.allocateChapterOrder(projectId)));
          String chapterId =
              chapters.insert(projectId, ((Number) order.get("chapterOrder")).intValue(), name);
          requireOne(revisions.insertCreate(chapterId));
          return required(fields(chapters.findById(chapterId)));
        });
  }

  public Map<String, Object> renameChapter(String owner, String id, String title) {
    String chapterId = uuid(id), name = text(title, "title", 1, 120);
    return transactions.required(
        () -> {
          String projectId =
              String.valueOf(
                  required(fields(chapters.findOwnedProject(owner, chapterId))).get("projectId"));
          access.lock(owner, projectId, true);
          requireOne(chapters.rename(chapterId, name));
          return required(fields(chapters.findById(chapterId)));
        });
  }

  public void deleteChapter(String owner, String id) {
    String chapterId = uuid(id);
    transactions.required(
        () -> {
          String projectId =
              String.valueOf(
                  required(fields(chapters.findOwnedProjectForDelete(owner, chapterId)))
                      .get("projectId"));
          access.lock(owner, projectId, true);
          access.requireNoChapterProposals(chapterId);
          requireOne(chapters.softDelete(chapterId));
          return null;
        });
  }

  public List<Map<String, Object>> revisions(String owner, String id, Integer requestedLimit) {
    return revisionPage(owner, id, null, requestedLimit).items();
  }

  public Page<Map<String, Object>> revisionPage(
      String owner, String id, String cursor, Integer requestedLimit) {
    String chapterId = uuid(id);
    int size = limit(requestedLimit);
    String scope = "revisions:" + owner + ":" + chapterId;
    String marker = cursors.decode(scope, cursor);
    Integer before = null;
    if (marker != null) {
      try {
        before = Integer.parseInt(marker);
        if (before < 1) throw new IllegalArgumentException();
      } catch (RuntimeException e) {
        throw new IllegalArgumentException("Invalid cursor");
      }
    }
    final Integer beforeVersion = before;
    return transactions.required(
        () -> {
          required(fields(chapters.findRevisionChapter(owner, chapterId)));
          return page(
              fields(revisions.findPage(chapterId, beforeVersion, size + 1)),
              size,
              scope,
              item -> item.get("version").toString(),
              cursors);
        });
  }

  public Map<String, Object> revision(String owner, String id, int version) {
    String chapterId = uuid(id);
    return transactions.required(
        () -> {
          Map<String, Object> revision =
              required(fields(revisions.findOwned(owner, chapterId, version)));
          revision.put("proposalReviews", fields(revisions.reviewLinks(chapterId, version)));
          return revision;
        });
  }

  public String projectForChapter(String owner, String id) {
    return chapter(owner, id).get("projectId").toString();
  }

  public Map<String, Object> lock(String owner, String id) {
    return required(fields(chapters.lockOwned(owner, uuid(id))));
  }

  public int persist(
      String chapterId, String content, int expectedVersion, String source, String proposalId) {
    String body = text(content, "content", 0, 6000);
    if (chapters.updateContentIfVersionMatches(chapterId, expectedVersion, body) != 1)
      throw error("VERSION_CONFLICT", "Chapter version changed");
    int version = expectedVersion + 1;
    requireOne(revisions.insert(chapterId, version, body, source, proposalId));
    return version;
  }
}
