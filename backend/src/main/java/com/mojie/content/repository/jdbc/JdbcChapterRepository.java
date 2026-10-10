package com.mojie.content.repository.jdbc;

import com.mojie.common.repository.jdbc.JdbcAccess;
import com.mojie.content.model.*;
import com.mojie.content.repository.ChapterRepository;
import java.util.*;
import javax.sql.DataSource;

public final class JdbcChapterRepository implements ChapterRepository {
  private final JdbcAccess db;

  public JdbcChapterRepository(DataSource source) {
    this.db = new JdbcAccess(source);
  }

  @Override
  public List<ChapterSummary> findPage(
      String projectId, Integer afterOrder, String afterId, int size) {
    return db.records(
        ChapterSummary.class,
        """
        SELECT id, project_id, chapter_order, title, version FROM tb_chapter
         WHERE project_id = ?::uuid AND deleted_at IS NULL
           AND (?::integer IS NULL OR (chapter_order, id) > (?, ?::uuid))
         ORDER BY chapter_order, id LIMIT ?
        """,
        projectId,
        afterOrder,
        afterOrder,
        afterId,
        size);
  }

  @Override
  public Chapter findVisible(String chapterId) {
    return db.firstRecord(
        Chapter.class,
        "SELECT ch.id, ch.project_id, ch.chapter_order, ch.title, ch.content, ch.version,"
            + " ch.updated_at FROM tb_chapter ch JOIN tb_project p ON p.id = ch.project_id WHERE"
            + " ch.id = ?::uuid AND ch.deleted_at IS NULL AND p.deleted_at IS"
            + " NULL",
        chapterId);
  }

  @Override
  public CreatedChapter findById(String chapterId) {
    return db.firstRecord(
        CreatedChapter.class,
        "SELECT id, project_id, chapter_order, title, content, version FROM tb_chapter WHERE id ="
            + " ?::uuid",
        chapterId);
  }

  @Override
  public String insert(String projectId, int chapterOrder, String title) {
    return db.first(
            "INSERT INTO tb_chapter(project_id, chapter_order, title) VALUES (?::uuid, ?, ?)"
                + " RETURNING id",
            projectId,
            chapterOrder,
            title)
        .get("id")
        .toString();
  }

  @Override
  public int rename(String chapterId, String title) {
    return db.update(
        "UPDATE tb_chapter SET title = ?, updated_at = now() WHERE id = ?::uuid AND deleted_at IS"
            + " NULL",
        title,
        chapterId);
  }

  @Override
  public ChapterProject findVisibleProject(String chapterId) {
    return db.firstRecord(
        ChapterProject.class,
        "SELECT ch.project_id FROM tb_chapter ch JOIN tb_project p ON p.id = ch.project_id WHERE"
            + " ch.id = ?::uuid AND ch.deleted_at IS NULL AND p.deleted_at IS"
            + " NULL",
        chapterId);
  }

  @Override
  public int softDelete(String chapterId) {
    return db.update(
        "UPDATE tb_chapter SET deleted_at = now(), updated_at = now() WHERE id = ?::uuid AND"
            + " deleted_at IS NULL",
        chapterId);
  }

  @Override
  public ChapterProject findVisibleProjectForDelete(String chapterId) {
    return db.firstRecord(
        ChapterProject.class,
        "SELECT ch.project_id FROM tb_chapter ch JOIN tb_project p ON p.id = ch.project_id WHERE"
            + " ch.id = ?::uuid AND ch.deleted_at IS NULL AND p.deleted_at IS"
            + " NULL",
        chapterId);
  }

  @Override
  public ChapterIdentity findRevisionChapter(String chapterId) {
    return db.firstRecord(
        ChapterIdentity.class,
        "SELECT ch.id FROM tb_chapter ch JOIN tb_project p ON p.id = ch.project_id WHERE ch.id ="
            + " ?::uuid",
        chapterId);
  }

  @Override
  public LockedChapter lockVisible(String chapterId) {
    return db.firstRecord(
        LockedChapter.class,
        "SELECT ch.id, ch.project_id, ch.version, ch.content FROM tb_chapter ch JOIN tb_project p"
            + " ON p.id = ch.project_id WHERE ch.id = ?::uuid AND ch.deleted_at IS NULL AND"
            + " p.deleted_at IS NULL FOR UPDATE OF ch",
        chapterId);
  }

  @Override
  public int updateContentIfVersionMatches(String chapterId, int expectedVersion, String content) {
    return db.update(
        "UPDATE tb_chapter SET content = ?, version = version + 1, updated_at = now() WHERE id ="
            + " ?::uuid AND version = ? AND deleted_at IS NULL",
        content,
        chapterId,
        expectedVersion);
  }
}
