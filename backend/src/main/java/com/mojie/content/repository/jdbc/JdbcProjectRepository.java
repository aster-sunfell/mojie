package com.mojie.content.repository.jdbc;

import com.mojie.common.repository.jdbc.JdbcAccess;
import com.mojie.content.model.*;
import com.mojie.content.repository.ProjectRepository;
import java.util.*;
import javax.sql.DataSource;

public final class JdbcProjectRepository implements ProjectRepository {
  private final JdbcAccess db;

  public JdbcProjectRepository(DataSource source) {
    this.db = new JdbcAccess(source);
  }

  @Override
  public List<ProjectSummary> findPage(
      String owner, String keyword, String beforeTime, String beforeId, int size) {
    return db.records(
        ProjectSummary.class,
        """
        SELECT id, name AS title, updated_at FROM tb_project
         WHERE owner_id = ? AND deleted_at IS NULL
           AND strpos(lower(name), lower(?)) > 0
           AND (?::timestamptz IS NULL OR (updated_at, id) < (?::timestamptz, ?::uuid))
         ORDER BY updated_at DESC, id DESC LIMIT ?
        """,
        owner,
        keyword,
        beforeTime,
        beforeTime,
        beforeId,
        size);
  }

  @Override
  public Project findOwned(String owner, String projectId) {
    return db.firstRecord(
        Project.class,
        "SELECT id, name AS title, active_run_id, created_at, updated_at FROM tb_project WHERE id ="
            + " ?::uuid AND owner_id = ? AND deleted_at IS NULL",
        projectId,
        owner);
  }

  @Override
  public Project findById(String projectId) {
    return db.firstRecord(
        Project.class,
        "SELECT id, name AS title, active_run_id, created_at, updated_at FROM tb_project WHERE id ="
            + " ?::uuid",
        projectId);
  }

  @Override
  public String insert(String owner, String title) {
    return db.first(
            "INSERT INTO tb_project(owner_id, name) VALUES (?, ?) RETURNING id", owner, title)
        .get("id")
        .toString();
  }

  @Override
  public int rename(String projectId, String title) {
    return db.update(
        "UPDATE tb_project SET name = ?, updated_at = now() WHERE id = ?::uuid", title, projectId);
  }

  @Override
  public int softDelete(String projectId) {
    return db.update(
        "UPDATE tb_project SET deleted_at = now(), updated_at = now() WHERE id = ?::uuid",
        projectId);
  }

  @Override
  public ProjectIdentity findOwnedId(String owner, String projectId) {
    return db.firstRecord(
        ProjectIdentity.class,
        "SELECT id FROM tb_project WHERE id = ?::uuid AND owner_id = ? AND deleted_at IS NULL",
        projectId,
        owner);
  }

  @Override
  public ChapterOrder allocateChapterOrder(String projectId) {
    return db.firstRecord(
        ChapterOrder.class,
        "UPDATE tb_project SET next_chapter_order = next_chapter_order + 1, updated_at = now()"
            + " WHERE id = ?::uuid RETURNING next_chapter_order - 1 AS chapter_order",
        projectId);
  }

  @Override
  public ProjectLock lockOwned(String owner, String projectId) {
    return db.firstRecord(
        ProjectLock.class,
        "SELECT id, active_run_id FROM tb_project WHERE id = ?::uuid AND owner_id = ? AND"
            + " deleted_at IS NULL FOR UPDATE",
        projectId,
        owner);
  }

  @Override
  public boolean hasProjectProposals(String projectId) {
    return db.first(
            "SELECT 1 FROM tb_edit_proposal WHERE status IN ('PENDING', 'PARTIALLY_APPLIED') AND"
                + " project_id = ?::uuid LIMIT 1",
            projectId)
        != null;
  }

  @Override
  public boolean hasChapterProposals(String chapterId) {
    return db.first(
            "SELECT 1 FROM tb_edit_proposal WHERE status IN ('PENDING', 'PARTIALLY_APPLIED') AND"
                + " target_chapter_id = ?::uuid LIMIT 1",
            chapterId)
        != null;
  }
}
