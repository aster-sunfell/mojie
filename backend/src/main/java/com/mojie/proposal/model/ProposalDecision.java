package com.mojie.proposal.model;

public record ProposalDecision(
    String status, Integer appliedVersion, String targetChapterId, String targetSettingId) {
  public ProposalDecision(String status, Integer appliedVersion) {
    this(status, appliedVersion, null, null);
  }
}
