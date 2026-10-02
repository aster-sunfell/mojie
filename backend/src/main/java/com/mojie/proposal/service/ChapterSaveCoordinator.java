package com.mojie.proposal.service;

import static com.mojie.content.model.ContentRules.*;

import com.mojie.common.transaction.TransactionRunner;
import com.mojie.content.service.*;
import com.mojie.proposal.model.ProposalReview.*;
import java.util.*;

public final class ChapterSaveCoordinator {
  private final ChapterService chapters;
  private final ProposalReviewService reviews;
  private final ProjectAccessService access;
  private final TransactionRunner transactions;

  public ChapterSaveCoordinator(
      ChapterService chapters,
      ProposalReviewService reviews,
      ProjectAccessService access,
      TransactionRunner transactions) {
    this.chapters = chapters;
    this.reviews = reviews;
    this.access = access;
    this.transactions = transactions;
  }

  public Map<String, Object> saveChapter(
      String owner, String id, String content, int expectedVersion) {
    return saveChapter(owner, id, content, expectedVersion, null);
  }

  public Map<String, Object> saveChapter(
      String owner, String id, String content, int expectedVersion, List<Review> decisions) {
    String chapterId = uuid(id), body = text(content, "content", 0, 6000);
    if (expectedVersion < 1) throw error("VALIDATION_ERROR", "Invalid expectedVersion");
    return transactions.required(
        () -> {
          String projectId = chapters.projectForChapter(owner, chapterId);
          access.lock(owner, projectId, false);
          // Every writer locks project -> proposals in stable ID order -> chapter.
          var locked = reviews.lockReviews(owner, chapterId, decisions);
          Map<String, Object> old = chapters.lock(owner, chapterId);
          if (((Number) old.get("version")).intValue() != expectedVersion)
            throw error("VERSION_CONFLICT", "Chapter version changed");
          boolean changed = !body.equals(old.get("content"));
          int version = expectedVersion + (changed ? 1 : 0);
          List<Saved> saved = reviews.save(version, decisions, locked);
          if (changed) {
            String proposalId =
                saved.stream()
                    .filter(Saved::applied)
                    .map(Saved::proposalId)
                    .findFirst()
                    .orElse(null);
            chapters.persist(
                chapterId,
                body,
                expectedVersion,
                proposalId == null ? "MANUAL" : "MIXED",
                proposalId);
          }
          reviews.link(chapterId, version, saved);
          return chapters.chapter(owner, chapterId);
        });
  }
}
