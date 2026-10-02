package com.mojie.proposal.repository.jdbc;

import com.mojie.common.repository.jdbc.JdbcAccess;
import com.mojie.proposal.repository.ProposalRevisionRepository;
import java.util.*;
import javax.sql.DataSource;

public final class JdbcProposalRevisionRepository implements ProposalRevisionRepository {
  private final JdbcAccess db;

  public JdbcProposalRevisionRepository(DataSource source) {
    this.db = new JdbcAccess(source);
  }

  @Override
  public int link(String chapterId, int version, String proposalId, String decisions) {
    return db.update(
        """
        INSERT INTO tb_chapter_revision_review(chapter_id, version, proposal_id, decisions)
        VALUES (?::uuid, ?, ?::uuid, ?::jsonb)
        ON CONFLICT (chapter_id, version, proposal_id)
        DO UPDATE SET decisions = tb_chapter_revision_review.decisions || EXCLUDED.decisions
        """,
        chapterId,
        version,
        proposalId,
        decisions);
  }
}
