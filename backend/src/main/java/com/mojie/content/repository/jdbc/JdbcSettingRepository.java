package com.mojie.content.repository.jdbc;

import com.mojie.common.repository.jdbc.JdbcAccess;
import com.mojie.content.model.*;
import com.mojie.content.repository.SettingRepository;
import java.util.*;
import javax.sql.DataSource;

public final class JdbcSettingRepository implements SettingRepository {
  private final JdbcAccess db;

  public JdbcSettingRepository(DataSource source) {
    this.db = new JdbcAccess(source);
  }

  @Override
  public List<Setting> findPage(
      String projectId, String type, String keyword, String afterId, int size) {
    return db.records(
        Setting.class,
        """
        SELECT id, project_id, type, title, content, version, updated_at FROM tb_setting
         WHERE project_id = ?::uuid AND deleted_at IS NULL
           AND (?::text IS NULL OR type = ?)
           AND (?::text IS NULL OR position(? in title) > 0 OR position(? in content) > 0)
           AND (?::uuid IS NULL OR id > ?::uuid)
         ORDER BY id LIMIT ?
        """,
        projectId,
        type,
        type,
        keyword,
        keyword,
        keyword,
        afterId,
        afterId,
        size);
  }

  @Override
  public Setting findById(String settingId) {
    return db.firstRecord(
        Setting.class,
        "SELECT id, project_id, type, title, content, version, updated_at FROM tb_setting WHERE id"
            + " = ?::uuid AND deleted_at IS NULL",
        settingId);
  }

  @Override
  public String insert(String projectId, String type, String title, String content) {
    return db.first(
            "INSERT INTO tb_setting(project_id, type, title, content) VALUES (?::uuid, ?, ?, ?)"
                + " RETURNING id",
            projectId,
            type,
            title,
            content)
        .get("id")
        .toString();
  }

  @Override
  public Setting lock(String settingId) {
    return db.firstRecord(
        Setting.class,
        "SELECT id, project_id, type, title, content, version, updated_at FROM tb_setting"
            + " WHERE id = ?::uuid AND deleted_at IS NULL FOR UPDATE",
        settingId);
  }

  @Override
  public int update(
      String settingId, String type, String title, String content, int expectedVersion) {
    return db.update(
        "UPDATE tb_setting SET type = ?, title = ?, content = ?, version = version + 1, updated_at"
            + " = now() WHERE id = ?::uuid AND deleted_at IS NULL AND version = ?",
        type,
        title,
        content,
        settingId,
        expectedVersion);
  }

  @Override
  public int insertRevision(
      String id,
      int version,
      String type,
      String title,
      String content,
      String source,
      String proposalId) {
    return db.update(
        "INSERT INTO tb_setting_revision(setting_id, version, type, title, content, source,"
            + " proposal_id) VALUES (?::uuid, ?, ?, ?, ?, ?, ?::uuid)",
        id,
        version,
        type,
        title,
        content,
        source,
        proposalId);
  }

  @Override
  public List<SettingRevision> revisions(String id) {
    return db.records(
        SettingRevision.class,
        "SELECT version, type, title, content, source, proposal_id, created_at FROM"
            + " tb_setting_revision WHERE setting_id = ?::uuid ORDER BY version DESC LIMIT 50",
        id);
  }

  @Override
  public SettingProject findVisibleProject(String settingId) {
    return db.firstRecord(
        SettingProject.class,
        "SELECT s.project_id FROM tb_setting s JOIN tb_project p ON p.id = s.project_id WHERE s.id"
            + " = ?::uuid AND s.deleted_at IS NULL AND p.deleted_at IS NULL",
        settingId);
  }

  @Override
  public int softDelete(String settingId) {
    return db.update(
        "UPDATE tb_setting SET deleted_at = now(), updated_at = now() WHERE id = ?::uuid AND"
            + " deleted_at IS NULL",
        settingId);
  }
}
