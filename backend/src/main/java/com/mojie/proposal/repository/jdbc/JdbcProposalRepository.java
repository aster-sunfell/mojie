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
  public EditProposal lockVisible(String id) {
    Map<String, Object> p =
        db.first(
            """
            SELECT p.id, p.project_id, p.target_chapter_id, p.base_version, p.proposed_content,
                   p.status, p.applied_version, p.base_content, p.review_decisions,
                   p.target_type, p.target_setting_id, p.base_type, p.base_title, p.proposed_type, p.proposed_title, p.operation, p.created_chapter_id, p.created_setting_id
              FROM tb_edit_proposal p JOIN tb_agent_run r ON r.id = p.run_id
             WHERE p.id = ?::uuid AND r.status = 'SUCCEEDED' FOR UPDATE OF p
            """,
            id);
    return p == null
        ? null
        : new EditProposal(
            p.get("id").toString(),
            p.get("projectId").toString(),
            p.get("targetChapterId") == null ? null : p.get("targetChapterId").toString(),
            ((Number) p.get("baseVersion")).intValue(),
            (String) p.get("proposedContent"),
            (String) p.get("status"),
            (Integer) p.get("appliedVersion"),
            (String) p.get("baseContent"),
            p.get("reviewDecisions").toString(),
            (String) p.get("targetType"),
            (String) p.get("targetSettingId"),
            (String) p.get("baseType"),
            (String) p.get("baseTitle"),
            (String) p.get("proposedType"),
            (String) p.get("proposedTitle"),
            (String) p.get("operation"),
            (String) p.get("createdChapterId"),
            (String) p.get("createdSettingId"));
  }

  @Override
  public String projectId(String id) {
    Map<String, Object> p =
        db.first(
            "SELECT p.project_id FROM tb_edit_proposal p JOIN tb_agent_run r ON r.id = p.run_id"
                + " WHERE p.id = ?::uuid AND r.status = 'SUCCEEDED'",
            id);
    return p == null ? null : p.get("projectId").toString();
  }

  @Override
  public ProposalReview.Locked lockReview(String chapterId, String id) {
    Map<String, Object> p =
        db.first(
            "SELECT p.base_content, p.proposed_content, p.status, p.review_decisions FROM"
                + " tb_edit_proposal p JOIN tb_agent_run r ON r.id = p.run_id WHERE p.id = ?::uuid"
                + " AND p.target_chapter_id = ?::uuid AND r.status = 'SUCCEEDED'"
                + " FOR UPDATE OF p",
            id,
            chapterId);
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
  public ProposalDetail detail(String id) {
    return db.firstRecord(
        ProposalDetail.class,
        """
        SELECT p.id, p.run_id, p.project_id, p.target_chapter_id, p.base_version,
               p.base_content, p.proposed_content, p.reason, p.status, p.applied_version,
               p.created_at, p.updated_at, p.review_decisions,
               p.target_type, p.target_setting_id, p.base_type, p.base_title, p.proposed_type, p.proposed_title, p.operation, p.created_chapter_id, p.created_setting_id
          FROM tb_edit_proposal p JOIN tb_agent_run r ON r.id = p.run_id
         WHERE p.id = ?::uuid
        """,
        id);
  }

  @Override
  public int insertCreate(
      String id,
      String runId,
      String projectId,
      String targetType,
      String type,
      String title,
      String content,
      String reason) {
    return db.update(
        """
        INSERT INTO tb_edit_proposal(id,run_id,project_id,target_type,operation,base_version,
            base_content,proposed_type,proposed_title,proposed_content,reason,status,proposal_index)
        VALUES (?::uuid,?::uuid,?::uuid,?,'CREATE',0,'',?,?,?,?,'PENDING',(SELECT count(*)+1 FROM tb_edit_proposal WHERE run_id=?::uuid))
        """,
        id,
        runId,
        projectId,
        targetType,
        type,
        title,
        content,
        reason,
        runId);
  }

  @Override
  public int markCreated(String id, String targetType, String targetId) {
    String column =
        switch (targetType) {
          case "CHAPTER" -> "created_chapter_id";
          case "SETTING" -> "created_setting_id";
          default -> throw new IllegalArgumentException("Unknown target type");
        };
    return db.update(
        "UPDATE tb_edit_proposal SET "
            + column
            + "=?::uuid,status='APPLIED',applied_version=1,updated_at=now()"
            + " WHERE id=?::uuid AND status='PENDING' AND operation='CREATE'",
        targetId,
        id);
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
            + " base_content, proposed_content, reason, status, proposal_index) VALUES (?::uuid,"
            + " ?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, 'PENDING', (SELECT count(*)+1 FROM"
            + " tb_edit_proposal WHERE run_id=?::uuid))",
        id,
        runId,
        projectId,
        targetChapterId,
        baseVersion,
        baseContent,
        proposedContent,
        reason,
        runId);
  }

  @Override
  public int insertSetting(
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
      String reason) {
    return db.update(
        "INSERT INTO tb_edit_proposal(id, run_id, project_id, target_type, target_setting_id,"
            + " base_version, base_type, base_title, base_content, proposed_type, proposed_title,"
            + " proposed_content, reason, status, proposal_index) VALUES (?::uuid, ?::uuid,"
            + " ?::uuid, 'SETTING', ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', (SELECT count(*)+1"
            + " FROM tb_edit_proposal WHERE run_id=?::uuid))",
        id,
        runId,
        projectId,
        targetSettingId,
        baseVersion,
        baseType,
        baseTitle,
        baseContent,
        proposedType,
        proposedTitle,
        proposedContent,
        reason,
        runId);
  }
}
