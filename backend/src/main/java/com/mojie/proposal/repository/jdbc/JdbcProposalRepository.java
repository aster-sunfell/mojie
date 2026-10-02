package com.mojie.proposal.repository.jdbc;

import com.mojie.common.repository.jdbc.JdbcAccess;
import com.mojie.proposal.model.*;
import com.mojie.proposal.repository.ProposalRepository;
import java.util.*;
import javax.sql.DataSource;

public final class JdbcProposalRepository implements ProposalRepository {
  private final JdbcAccess db;

  public JdbcProposalRepository(DataSource source) {
    this.db = new JdbcAccess(source);
  }

  @Override
  public EditProposal lockOwned(String owner, String id) {
    Map<String, Object> p =
        db.first(
            """
            SELECT p.id, p.project_id, p.target_chapter_id, p.base_version, p.proposed_content,
                   p.status, p.applied_version, p.base_content, p.review_decisions
              FROM tb_edit_proposal p JOIN tb_agent_run r ON r.id = p.run_id
             WHERE p.id = ?::uuid AND r.owner_id = ? AND r.status = 'SUCCEEDED' FOR UPDATE OF p
            """,
            id,
            owner);
    return p == null
        ? null
        : new EditProposal(
            p.get("id").toString(),
            p.get("projectId").toString(),
            p.get("targetChapterId").toString(),
            ((Number) p.get("baseVersion")).intValue(),
            (String) p.get("proposedContent"),
            (String) p.get("status"),
            (Integer) p.get("appliedVersion"),
            (String) p.get("baseContent"),
            p.get("reviewDecisions").toString());
  }

  @Override
  public String projectId(String owner, String id) {
    Map<String, Object> p =
        db.first(
            "SELECT p.project_id FROM tb_edit_proposal p JOIN tb_agent_run r ON r.id = p.run_id"
                + " WHERE p.id = ?::uuid AND r.owner_id = ? AND r.status = 'SUCCEEDED'",
            id,
            owner);
    return p == null ? null : p.get("projectId").toString();
  }

  @Override
  public ProposalReview.Locked lockReview(String owner, String chapterId, String id) {
    Map<String, Object> p =
        db.first(
            "SELECT p.base_content, p.proposed_content, p.status, p.review_decisions FROM"
                + " tb_edit_proposal p JOIN tb_agent_run r ON r.id = p.run_id WHERE p.id = ?::uuid"
                + " AND p.target_chapter_id = ?::uuid AND r.owner_id = ? AND r.status = 'SUCCEEDED'"
                + " FOR UPDATE OF p",
            id,
            chapterId,
            owner);
    return p == null
        ? null
        : new ProposalReview.Locked(
            (String) p.get("baseContent"),
            (String) p.get("proposedContent"),
            (String) p.get("status"),
            p.get("reviewDecisions").toString());
  }

  @Override
  public int updateReview(String id, String decisions, String status, Integer appliedVersion) {
    return db.update(
        "UPDATE tb_edit_proposal SET review_decisions = ?::jsonb, status = ?, applied_version = ?,"
            + " updated_at = now() WHERE id = ?::uuid",
        decisions,
        status,
        appliedVersion,
        id);
  }

  @Override
  public int markConflicted(String id) {
    return db.update(
        "UPDATE tb_edit_proposal SET status = 'CONFLICTED', updated_at = now() WHERE id = ?::uuid"
            + " AND status = 'PENDING'",
        id);
  }

  @Override
  public int markApplied(String id, int version) {
    return db.update(
        "UPDATE tb_edit_proposal SET status = 'APPLIED', applied_version = ?, updated_at = now()"
            + " WHERE id = ?::uuid AND status = 'PENDING'",
        version,
        id);
  }

  @Override
  public ProposalDetail detail(String owner, String id) {
    return db.firstRecord(
        ProposalDetail.class,
        """
        SELECT p.id, p.run_id, p.project_id, p.target_chapter_id, p.base_version,
               p.base_content, p.proposed_content, p.reason, p.status, p.applied_version,
               p.created_at, p.updated_at, p.review_decisions
          FROM tb_edit_proposal p JOIN tb_agent_run r ON r.id = p.run_id
         WHERE p.id = ?::uuid AND r.owner_id = ?
        """,
        id,
        owner);
  }

  @Override
  public int insert(
      String id,
      String runId,
      String projectId,
      String targetChapterId,
      int baseVersion,
      String baseContent,
      String proposedContent,
      String reason) {
    return db.update(
        "INSERT INTO tb_edit_proposal(id, run_id, project_id, target_chapter_id, base_version,"
            + " base_content, proposed_content, reason, status) VALUES (?::uuid, ?::uuid, ?::uuid,"
            + " ?::uuid, ?, ?, ?, ?, 'PENDING')",
        id,
        runId,
        projectId,
        targetChapterId,
        baseVersion,
        baseContent,
        proposedContent,
        reason);
  }
}
