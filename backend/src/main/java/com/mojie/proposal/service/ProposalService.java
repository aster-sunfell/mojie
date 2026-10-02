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
  private final ProjectAccessService access;
  private final TransactionRunner transactions;

  public ProposalService(
      ProposalRepository proposals,
      ChapterService chapters,
      ProjectAccessService access,
      TransactionRunner transactions) {
    this.proposals = proposals;
    this.chapters = chapters;
    this.access = access;
    this.transactions = transactions;
  }

  public Map<String, Object> detail(String owner, String id) {
    return transactions.required(
        () -> {
          var row = proposals.detail(owner, id);
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

  public ProposalDecision accept(String owner, String id) {
    return transactions.required(
        () -> {
          String projectId = required(proposals.projectId(owner, id));
          access.lock(owner, projectId, false);
          EditProposal proposal = required(proposals.lockOwned(owner, id));
          if ("APPLIED".equals(proposal.status()))
            return new ProposalDecision("APPLIED", proposal.appliedVersion());
          if (!"PENDING".equals(proposal.status()) || !"{}".equals(proposal.reviewDecisions()))
            throw stateConflict();
          Map<String, Object> chapter = chapters.lock(owner, proposal.targetChapterId());
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

  public ProposalDecision reject(String owner, String id) {
    return transactions.required(
        () -> {
          String projectId = required(proposals.projectId(owner, id));
          access.lock(owner, projectId, false);
          EditProposal proposal = required(proposals.lockOwned(owner, id));
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
