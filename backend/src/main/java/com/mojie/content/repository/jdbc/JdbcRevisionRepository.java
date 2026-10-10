package com.mojie.content.repository.jdbc;

import com.mojie.common.repository.jdbc.JdbcAccess;
import com.mojie.content.model.*;
import com.mojie.content.repository.RevisionRepository;
import java.util.*;
import javax.sql.DataSource;

public final class JdbcRevisionRepository implements RevisionRepository {
  private final JdbcAccess db;

  public JdbcRevisionRepository(DataSource source) {
    this.db = new JdbcAccess(source);
  }

  @Override
  public int insertCreate(String chapterId) {
    return db.update(
        "INSERT INTO tb_chapter_revision(chapter_id, version, content, source) VALUES (?::uuid, 1,"
            + " '', 'CREATE')",
        chapterId);
  }

  @Override
  public List<RevisionSummary> findPage(String chapterId, Integer beforeVersion, int size) {
    return db.records(
        RevisionSummary.class,
        """
        SELECT version, source, proposal_id, created_at FROM tb_chapter_revision
         WHERE chapter_id = ?::uuid AND (?::integer IS NULL OR version < ?)
         ORDER BY version DESC LIMIT ?
        """,
        chapterId,
        beforeVersion,
        beforeVersion,
        size);
  }

  @Override
  public List<RevisionReview> reviewLinks(String chapterId, int version) {
    return db.records(
        RevisionReview.class,
        "SELECT proposal_id, decisions FROM tb_chapter_revision_review WHERE chapter_id = ?::uuid"
            + " AND version = ?",
        chapterId,
        version);
  }

  @Override
  public ChapterRevision findVisible(String chapterId, int version) {
    return db.firstRecord(
        ChapterRevision.class,
        "SELECT r.chapter_id, r.version, r.content, r.source, r.proposal_id, r.created_at FROM"
            + " tb_chapter_revision r JOIN tb_chapter ch ON ch.id = r.chapter_id JOIN tb_project p"
            + " ON p.id = ch.project_id WHERE r.chapter_id = ?::uuid AND r.version = ?",
        chapterId,
        version);
  }

  @Override
  public int insert(
      String chapterId, int version, String content, String source, String proposalId) {
    return db.update(
        "INSERT INTO tb_chapter_revision(chapter_id, version, content, source, proposal_id) VALUES"
            + " (?::uuid, ?, ?, ?, ?::uuid)",
        chapterId,
        version,
        content,
        source,
        proposalId);
  }
}
