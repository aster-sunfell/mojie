package com.mojie.proposal.repository;

import com.mojie.proposal.model.*;
import java.util.*;

public interface ProposalRepository {
  EditProposal lockOwned(String owner, String id);

  String projectId(String owner, String id);

  ProposalReview.Locked lockReview(String owner, String chapterId, String id);

  int updateReview(String id, String decisions, String status, Integer appliedVersion);

  int markConflicted(String id);

  int markApplied(String id, int version);

  ProposalDetail detail(String owner, String id);

  int insert(
      String id,
      String runId,
      String projectId,
      String targetChapterId,
      int baseVersion,
      String baseContent,
      String proposedContent,
      String reason);
}
