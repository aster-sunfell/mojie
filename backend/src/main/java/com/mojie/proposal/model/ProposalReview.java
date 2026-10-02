package com.mojie.proposal.model;

import java.util.List;

public final class ProposalReview {
  private ProposalReview() {}

  public record Choice(Integer itemId, String status, String editedContent) {
    public Choice(Integer itemId, String status) {
      this(itemId, status, null);
    }
  }

  public record Review(String proposalId, List<Choice> decisions) {}

  public record Saved(String proposalId, String decisions, boolean applied) {}

  public record Locked(
      String baseContent, String proposedContent, String status, String reviewDecisions) {}
}
