package com.mojie.proposal.repository;

import com.mojie.proposal.model.*;
import java.util.*;

public interface ProposalRepository {
  int insertCreate(
      String id,
      String runId,
      String projectId,
      String targetType,
      String type,
      String title,
      String content,
      String reason);

  int markCreated(String id, String targetType, String targetId);

  EditProposal lockVisible(String id);

  String projectId(String id);

  ProposalReview.Locked lockReview(String chapterId, String id);

  int updateReview(String id, String decisions, String status, Integer appliedVersion);

  int markConflicted(String id);

  int markApplied(String id, int version);

  ProposalDetail detail(String id);

  int insert(
      String id,
      String runId,
      String projectId,
      String targetChapterId,
      int baseVersion,
      String baseContent,
      String proposedContent,
      String reason);

  int insertSetting(
      String id,
      String runId,
      String projectId,
      String targetSettingId,
      int baseVersion,
      String baseType,
      String baseTitle,
      String baseContent,
      String proposedType,
      String proposedTitle,
      String proposedContent,
      String reason);
}
