package com.mojie.proposal.repository.jdbc;

import com.mojie.common.repository.jdbc.JdbcAccess;
import com.mojie.proposal.model.ProposalInbox.*;
import com.mojie.proposal.repository.ProposalInboxRepository;
import java.util.List;
import javax.sql.DataSource;

public final class JdbcProposalInboxRepository implements ProposalInboxRepository {
  private final JdbcAccess db;

  public JdbcProposalInboxRepository(DataSource source) {
    db = new JdbcAccess(source);
  }

  @Override
  public Counts counts(String projectId) {
    return db.firstRecord(
        Counts.class,
        """
        SELECT count(*) FILTER (WHERE p.status IN ('PENDING','PARTIALLY_APPLIED')) AS pending_count,
               count(*) FILTER (WHERE p.status='CONFLICTED') AS conflicted_count,
               count(DISTINCT (p.target_type,COALESCE(p.target_chapter_id,p.target_setting_id,p.id))) AS target_count
          FROM tb_edit_proposal p JOIN tb_agent_run r ON r.id=p.run_id
         WHERE p.project_id=?::uuid AND r.status='SUCCEEDED'
           AND p.status IN ('PENDING','PARTIALLY_APPLIED','CONFLICTED')
        """,
        projectId);
  }

  @Override
  public List<Entry> page(
      String projectId, String filter, String beforeTime, String beforeId, int size) {
    return db.records(
        Entry.class,
        """
        SELECT p.id,p.run_id,r.conversation_id,v.title AS conversation_title,
               p.target_type,p.operation,p.target_chapter_id,p.target_setting_id,
               COALESCE(c.title,s.title,p.proposed_title,p.base_title) AS target_title,
               c.chapter_order,p.base_version,p.status,p.reason,p.created_at,
               p.base_content,p.proposed_content,p.review_decisions
          FROM tb_edit_proposal p JOIN tb_agent_run r ON r.id=p.run_id
          LEFT JOIN tb_conversation v ON v.id=r.conversation_id
          LEFT JOIN tb_chapter c ON c.id=p.target_chapter_id
          LEFT JOIN tb_setting s ON s.id=p.target_setting_id
         WHERE p.project_id=?::uuid AND r.status='SUCCEEDED'
           AND p.status IN ('PENDING','PARTIALLY_APPLIED','CONFLICTED')
           AND (?='all' OR (?='pending' AND p.status IN ('PENDING','PARTIALLY_APPLIED'))
                       OR (?='conflicted' AND p.status='CONFLICTED'))
           AND (?::timestamptz IS NULL OR (p.created_at,p.id)<(?::timestamptz,?::uuid))
         ORDER BY p.created_at DESC,p.id DESC LIMIT ?
        """,
        projectId,
        filter,
        filter,
        filter,
        beforeTime,
        beforeTime,
        beforeId,
        size);
  }
}
