package com.mojie.proposal.service;

import static com.mojie.content.service.ProjectAccessService.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mojie.common.error.BusinessException;
import com.mojie.common.transaction.TransactionRunner;
import com.mojie.content.service.*;
import com.mojie.proposal.model.*;
import com.mojie.proposal.repository.ProposalRepository;
import java.util.*;

public final class ProposalService {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final ProposalRepository proposals;
  private final ChapterService chapters;
  private final SettingService settings;
  private final ProjectAccessService access;
  private final TransactionRunner transactions;

  public ProposalService(
      ProposalRepository proposals,
      ChapterService chapters,
      SettingService settings,
      ProjectAccessService access,
      TransactionRunner transactions) {
    this.proposals = proposals;
    this.chapters = chapters;
    this.settings = settings;
    this.access = access;
    this.transactions = transactions;
  }

  public Map<String, Object> detail(String id) {
    return transactions.required(
        () -> {
          var row = proposals.detail(id);
          if (row == null)
            throw new BusinessException("RESOURCE_NOT_FOUND", "Run or proposal unavailable");
          Map<String, Object> value = row.fields();
          value.put(
              "items",
              ProposalReviewService.items(
                  (String) value.get("baseContent"),
                  (String) value.get("proposedContent"),
                  (String) value.get("status"),
                  (Integer) value.get("appliedVersion"),
                  decode(value.remove("reviewDecisions").toString())));
          return value;
        });
  }

  public ProposalDecision accept(String id) {
    return transactions.required(
        () -> {
          String projectId = required(proposals.projectId(id));
          access.lock(projectId, false);
          EditProposal proposal = required(proposals.lockVisible(id));
          if ("APPLIED".equals(proposal.status())) return applied(proposal);
          if (!"PENDING".equals(proposal.status()) || !"{}".equals(proposal.reviewDecisions()))
            throw stateConflict();
          if ("CREATE".equals(proposal.operation())) {
            access.lock(projectId, true);
            return createLocked(
                proposal,
                proposal.proposedType(),
                proposal.proposedTitle(),
                proposal.proposedContent());
          }
          if ("SETTING".equals(proposal.targetType())) {
            access.lock(projectId, true);
            return saveSettingLocked(
                proposal,
                proposal.proposedType(),
                proposal.proposedTitle(),
                proposal.proposedContent(),
                proposal.baseVersion());
          }
          Map<String, Object> chapter = chapters.lock(proposal.targetChapterId());
          int version = ((Number) chapter.get("version")).intValue();
          if (version != proposal.baseVersion()) {
            requireOne(proposals.markConflicted(id));
            return new ProposalDecision("CONFLICTED", null);
          }
          int next =
              chapters.persist(
                  proposal.targetChapterId(), proposal.proposedContent(), version, "AGENT", id);
          requireOne(proposals.markApplied(id, next));
          return new ProposalDecision("APPLIED", next);
        });
  }

  public ProposalDecision saveSetting(
      String id, String type, String title, String content, Integer expectedVersion) {
    return transactions.required(
        () -> {
          String projectId = required(proposals.projectId(id));
          access.lock(projectId, true);
          EditProposal proposal = required(proposals.lockVisible(id));
          if (!"SETTING".equals(proposal.targetType()))
            throw new IllegalArgumentException("A setting proposal is required");
          if ("APPLIED".equals(proposal.status())) return applied(proposal);
          if (!"PENDING".equals(proposal.status())) throw stateConflict();
          if ("CREATE".equals(proposal.operation())) {
            if (expectedVersion != null)
              throw new IllegalArgumentException("CREATE must omit expectedVersion");
            return createLocked(proposal, type, title, content);
          }
          if (expectedVersion == null || expectedVersion < 1)
            throw new IllegalArgumentException("expectedVersion required");
          return saveSettingLocked(proposal, type, title, content, expectedVersion);
        });
  }

  public ProposalDecision saveChapterCreate(String id, String title, String content) {
    return transactions.required(
        () -> {
          String projectId = required(proposals.projectId(id));
          access.lock(projectId, true);
          var proposal = required(proposals.lockVisible(id));
          if (!"CREATE".equals(proposal.operation()) || !"CHAPTER".equals(proposal.targetType()))
            throw new IllegalArgumentException("A new chapter proposal is required");
          if ("APPLIED".equals(proposal.status())) return applied(proposal);
          if (!"PENDING".equals(proposal.status())) throw stateConflict();
          return createLocked(proposal, null, title, content);
        });
  }

  private ProposalDecision createLocked(
      EditProposal proposal, String type, String title, String content) {
    boolean setting = "SETTING".equals(proposal.targetType());
    String id =
        setting
            ? settings.createFromProposal(proposal.projectId(), type, title, content, proposal.id())
            : chapters.createFromProposal(proposal.projectId(), title, content, proposal.id());
    requireOne(proposals.markCreated(proposal.id(), proposal.targetType(), id));
    return new ProposalDecision("APPLIED", 1, setting ? null : id, setting ? id : null);
  }

  private static ProposalDecision applied(EditProposal proposal) {
    return new ProposalDecision(
        "APPLIED",
        proposal.appliedVersion(),
        proposal.createdChapterId(),
        proposal.createdSettingId());
  }

  private ProposalDecision saveSettingLocked(
      EditProposal proposal, String type, String title, String content, int expectedVersion) {
    var setting = settings.lock(proposal.targetSettingId());
    if (!proposal.projectId().equals(setting.projectId()))
      throw new BusinessException("RESOURCE_NOT_FOUND", "Setting unavailable");
    if (setting.version() != proposal.baseVersion() || expectedVersion != proposal.baseVersion()) {
      requireOne(proposals.markConflicted(proposal.id()));
      return new ProposalDecision("CONFLICTED", null);
    }
    int next =
        settings.persist(
            setting.id(), type, title, content, expectedVersion, "AGENT", proposal.id());
    requireOne(proposals.markApplied(proposal.id(), next));
    return new ProposalDecision("APPLIED", next);
  }

  public ProposalDecision reject(String id) {
    return transactions.required(
        () -> {
          String projectId = required(proposals.projectId(id));
          access.lock(projectId, false);
          EditProposal proposal = required(proposals.lockVisible(id));
          if ("REJECTED".equals(proposal.status())) return new ProposalDecision("REJECTED", null);
          if (!Set.of("PENDING", "PARTIALLY_APPLIED", "CONFLICTED").contains(proposal.status()))
            throw stateConflict();
          ObjectNode decisions = decode(proposal.reviewDecisions());
          Integer appliedVersion = null;
          for (var entry : decisions)
            if ("APPLIED".equals(entry.path("status").asText()))
              appliedVersion =
                  Math.max(
                      appliedVersion == null ? 0 : appliedVersion,
                      entry.path("savedVersion").asInt());
          for (var item : ProposalDiff.between(proposal.baseContent(), proposal.proposedContent()))
            if (!decisions.has(String.valueOf(item.id())))
              decisions.set(
                  String.valueOf(item.id()), JSON.createObjectNode().put("status", "IGNORED"));
          String status = appliedVersion == null ? "REJECTED" : "APPLIED";
          requireOne(proposals.updateReview(id, decisions.toString(), status, appliedVersion));
          return new ProposalDecision(status, appliedVersion);
        });
  }

  private static ObjectNode decode(String value) {
    try {
      return (ObjectNode) JSON.readTree(value);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid review decisions", e);
    }
  }

  private static BusinessException stateConflict() {
    return new BusinessException("PROPOSAL_STATE_CONFLICT", "Proposal is no longer pending");
  }
}
