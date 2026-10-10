package com.mojie.proposal.service;

import static com.mojie.content.service.ProjectAccessService.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mojie.common.error.BusinessException;
import com.mojie.proposal.model.ProposalDiff;
import com.mojie.proposal.model.ProposalReview.*;
import com.mojie.proposal.repository.*;
import java.util.*;

public final class ProposalReviewService {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final ProposalRepository proposals;
  private final ProposalRevisionRepository revisions;

  public ProposalReviewService(ProposalRepository proposals, ProposalRevisionRepository revisions) {
    this.proposals = proposals;
    this.revisions = revisions;
  }

  public static List<Map<String, Object>> items(
      String base, String proposed, String status, Integer appliedVersion, JsonNode decisions) {
    List<Map<String, Object>> items = new ArrayList<>();
    for (ProposalDiff.Item item : ProposalDiff.between(base, proposed)) {
      JsonNode decision = decisions == null ? null : decisions.get(String.valueOf(item.id()));
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("id", item.id());
      value.put("start", item.start());
      value.put("end", item.end());
      value.put("lineNumber", item.lineNumber());
      value.put("before", item.before());
      value.put("after", item.after());
      value.put("contextBefore", item.contextBefore());
      value.put("contextAfter", item.contextAfter());
      value.put(
          "status",
          decision != null
              ? decision.path("status").asText()
              : "APPLIED".equals(status)
                  ? "APPLIED"
                  : "REJECTED".equals(status) ? "IGNORED" : "PENDING");
      value.put(
          "savedVersion",
          decision != null && decision.has("savedVersion")
              ? Integer.valueOf(decision.path("savedVersion").asInt())
              : appliedVersion);
      if (decision != null && decision.has("editedContent"))
        value.put("editedContent", decision.path("editedContent").asText());
      items.add(value);
    }
    return items;
  }

  public Map<String, Locked> lockReviews(String chapterId, List<Review> reviews) {
    if (reviews == null || reviews.isEmpty()) return Map.of();
    if (reviews.size() > 50) throw invalid();
    Map<String, Review> unique = new TreeMap<>();
    for (Review review : reviews) {
      if (review == null
          || review.proposalId() == null
          || review.decisions() == null
          || review.decisions().isEmpty()) throw invalid();
      try {
        UUID.fromString(review.proposalId());
      } catch (RuntimeException e) {
        throw invalid();
      }
      if (unique.putIfAbsent(review.proposalId(), review) != null) throw invalid();
    }
    Map<String, Locked> locked = new LinkedHashMap<>();
    for (String id : unique.keySet()) locked.put(id, required(proposals.lockReview(chapterId, id)));
    return locked;
  }

  public List<Saved> save(int version, List<Review> reviews, Map<String, Locked> locked) {
    if (reviews == null || reviews.isEmpty()) return List.of();
    List<Saved> saved = new ArrayList<>();
    for (Review review : reviews) {
      Locked proposal = required(locked.get(review.proposalId()));
      int count = ProposalDiff.between(proposal.baseContent(), proposal.proposedContent()).size();
      ObjectNode decisions = decode(proposal.reviewDecisions());
      ObjectNode delta = JSON.createObjectNode();
      boolean applied = false;
      Set<Integer> ids = new HashSet<>();
      for (Choice choice : review.decisions()) {
        if (choice == null
            || choice.itemId() == null
            || choice.itemId() < 0
            || choice.itemId() >= count
            || !ids.add(choice.itemId())
            || !("APPLIED".equals(choice.status()) || "IGNORED".equals(choice.status())))
          throw invalid();
        String key = String.valueOf(choice.itemId());
        String edited =
            choice.editedContent() == null
                ? null
                : choice.editedContent().replace("\r\n", "\n").replace('\r', '\n');
        if (edited != null
            && (!"APPLIED".equals(choice.status())
                || edited.codePointCount(0, edited.length()) > 6000)) throw invalid();
        JsonNode existing = decisions.get(key);
        if (existing != null
            && existing.path("status").asText().equals(choice.status())
            && (edited == null || edited.equals(existing.path("editedContent").asText()))) continue;
        if (existing != null || Set.of("APPLIED", "REJECTED").contains(proposal.status()))
          throw new BusinessException("PROPOSAL_STATE_CONFLICT", "Proposal choice changed");
        ObjectNode value =
            JSON.createObjectNode().put("status", choice.status()).put("savedVersion", version);
        if (edited != null) value.put("editedContent", edited);
        decisions.set(key, value);
        delta.set(key, value);
        applied |= "APPLIED".equals(choice.status());
      }
      if (delta.isEmpty()) continue;
      boolean anyApplied = false;
      for (JsonNode decision : decisions)
        anyApplied |= "APPLIED".equals(decision.path("status").asText());
      String status =
          decisions.size() == count
              ? anyApplied ? "APPLIED" : "REJECTED"
              : anyApplied ? "PARTIALLY_APPLIED" : "PENDING";

      requireOne(
          proposals.updateReview(
              review.proposalId(),
              decisions.toString(),
              status,
              "APPLIED".equals(status) ? version : null));
      saved.add(new Saved(review.proposalId(), delta.toString(), applied));
    }
    return saved;
  }

  public void link(String chapterId, int version, List<Saved> reviews) {
    for (Saved review : reviews)
      requireOne(revisions.link(chapterId, version, review.proposalId(), review.decisions()));
  }

  private static ObjectNode decode(String value) {
    try {
      return (ObjectNode) JSON.readTree(value);
    } catch (Exception e) {
      throw new IllegalStateException("Stored proposal review is invalid", e);
    }
  }

  private static BusinessException invalid() {
    return new BusinessException("VALIDATION_ERROR", "Invalid proposal review");
  }
}
