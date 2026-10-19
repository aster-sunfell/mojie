package com.mojie.proposal.service;

import static com.mojie.content.model.ContentRules.*;
import static com.mojie.content.service.ProjectAccessService.required;

import com.mojie.common.pagination.*;
import com.mojie.common.transaction.TransactionRunner;
import com.mojie.content.repository.ProjectRepository;
import com.mojie.proposal.model.ProposalInbox.*;
import com.mojie.proposal.repository.ProposalInboxRepository;
import java.time.OffsetDateTime;
import java.util.*;

public final class ProposalInboxService {
  private final ProposalInboxRepository proposals;
  private final ProjectRepository projects;
  private final TransactionRunner tx;
  private final PageCursor cursors;

  public ProposalInboxService(
      ProposalInboxRepository proposals,
      ProjectRepository projects,
      TransactionRunner tx,
      PageCursor cursors) {
    this.proposals = proposals;
    this.projects = projects;
    this.tx = tx;
    this.cursors = cursors;
  }

  public Counts counts(String id) {
    String projectId = uuid(id);
    return tx.readSnapshot(
        () -> {
          required(projects.findVisibleId(projectId));
          return proposals.counts(projectId);
        });
  }

  public Page<Map<String, Object>> page(String id, String filter, String cursor, Integer requestedLimit) {
    String projectId = uuid(id);
    String selected = filter == null ? "all" : filter;
    if (!Set.of("all", "pending", "conflicted").contains(selected))
      throw error("VALIDATION_ERROR", "Invalid proposal filter");
    int size = limit(requestedLimit);
    String scope = "proposal-inbox:" + projectId + ":" + selected;
    String[] marker = pair(cursors.decode(scope, cursor));
    if (marker[0] != null) {
      OffsetDateTime.parse(marker[0]);
      uuid(marker[1]);
    }
    return tx.readSnapshot(
        () -> {
          required(projects.findVisibleId(projectId));
          var rows = proposals.page(projectId, selected, marker[0], marker[1], size + 1);
          return com.mojie.content.model.ContentRules.page(
              rows.stream().map(ProposalInboxService::summary).toList(),
              size,
              scope,
              item -> item.get("createdAt") + "|" + item.get("id"),
              cursors);
        });
  }

  private static Map<String, Object> summary(Entry row) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", row.id());
    result.put("runId", row.runId());
    result.put("conversationId", row.conversationId());
    result.put("conversationTitle", row.conversationTitle());
    result.put("targetType", row.targetType());
    result.put("operation", row.operation());
    result.put("targetChapterId", row.targetChapterId());
    result.put("targetSettingId", row.targetSettingId());
    result.put("targetTitle", row.targetTitle());
    result.put("chapterOrder", row.chapterOrder());
    result.put("baseVersion", row.baseVersion());
    result.put("status", row.status());
    result.put("reason", row.reason());
    result.put("createdAt", row.createdAt());
    // Diff only a bounded page; whole-chapter drafts never leave this list endpoint.
    result.put(
        "remainingItemCount",
        "UPDATE".equals(row.operation()) && "CHAPTER".equals(row.targetType())
            ? ProposalReviewService.items(
                    row.baseContent(), row.proposedContent(), row.status(), null, row.reviewDecisions())
                .stream()
                .filter(item -> "PENDING".equals(item.get("status")))
                .count()
            : null);
    return result;
  }
}
