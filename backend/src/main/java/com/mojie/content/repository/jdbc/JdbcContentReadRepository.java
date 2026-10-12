package com.mojie.content.repository.jdbc;

import static com.mojie.common.repository.jdbc.SearchStatements.*;

import com.mojie.common.cancellation.QueryCancellation;
import com.mojie.common.repository.jdbc.JdbcAccess;
import com.mojie.content.model.ChapterVersionRow;
import com.mojie.content.model.ReadContent.*;
import com.mojie.content.repository.ContentReadRepository;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

public final class JdbcContentReadRepository implements ContentReadRepository {
  private final JdbcAccess db;

  public JdbcContentReadRepository(DataSource source) {
    this.db = new JdbcAccess(source);
  }

  public List<ChapterMetadata> chapters(
      String projectId, long timeoutMillis, QueryCancellation context) {
    return db.execute(
        c -> {
          timeout(c, timeoutMillis, false);
          try (PreparedStatement s =
                  JdbcAccess.statement(
                      c,
                      "SELECT c.id, c.title, c.chapter_order, c.version FROM tb_chapter c JOIN"
                          + " tb_project p ON p.id = c.project_id WHERE c.project_id = ?::uuid AND"
                          + " c.deleted_at IS NULL AND p.deleted_at IS NULL ORDER BY"
                          + " c.chapter_order, c.id",
                      projectId);
              var registration = cancel(context, s)) {
            check(context);
            try (ResultSet rows = s.executeQuery()) {
              List<ChapterMetadata> result = new ArrayList<>();
              while (rows.next())
                result.add(
                    new ChapterMetadata(
                        rows.getString(1), rows.getString(2), rows.getInt(3), rows.getLong(4)));
              return result;
            }
          }
        });
  }

  public ChapterText revision(
      String projectId, String chapterId, long version, long timeoutMillis) {
    return db.execute(
        c -> {
          timeout(c, timeoutMillis, false);
          try (PreparedStatement s =
                  JdbcAccess.statement(
                      c,
                      "SELECT c.id, c.title, c.chapter_order, r.version, r.content FROM tb_chapter"
                          + " c JOIN tb_project p ON p.id = c.project_id JOIN tb_chapter_revision r"
                          + " ON r.chapter_id = c.id WHERE c.project_id = ?::uuid AND c.id ="
                          + " ?::uuid AND r.version = ? AND c.deleted_at IS NULL AND p.deleted_at"
                          + " IS NULL",
                      projectId,
                      chapterId,
                      version);
              ResultSet rows = s.executeQuery()) {
            return rows.next() ? chapter(rows) : null;
          }
        });
  }

  public List<ChapterText> revisions(
      String projectId,
      List<ChapterVersion> chapters,
      long timeoutMillis,
      QueryCancellation context) {
    if (chapters.isEmpty()) return List.of();
    String placeholders =
        String.join(",", Collections.nCopies(chapters.size(), "(?::uuid, ?::bigint)"));
    String sql =
"""
SELECT c.id, c.title, c.chapter_order, r.version, r.content
  FROM (VALUES %s) requested(id, version)
  JOIN tb_chapter c ON c.id = requested.id JOIN tb_project p ON p.id = c.project_id
  JOIN tb_chapter_revision r ON r.chapter_id = c.id AND r.version = requested.version
 WHERE c.project_id = ?::uuid AND c.deleted_at IS NULL AND p.deleted_at IS NULL ORDER BY c.chapter_order, c.id
"""
            .formatted(placeholders);
    return db.execute(
        c -> {
          timeout(c, timeoutMillis, false);
          try (PreparedStatement s = c.prepareStatement(sql);
              var registration = cancel(context, s)) {
            int parameter = 1;
            for (ChapterVersion chapter : chapters) {
              s.setString(parameter++, chapter.id());
              s.setLong(parameter++, chapter.version());
            }
            s.setString(parameter, projectId);
            check(context);
            try (ResultSet rows = s.executeQuery()) {
              List<ChapterText> result = new ArrayList<>();
              while (rows.next()) result.add(chapter(rows));
              return result;
            }
          }
        });
  }

  public List<ChapterVersionRow> versions(
      String projectId, long timeoutMillis, QueryCancellation context) {
    return db.execute(
        c -> {
          timeout(c, timeoutMillis, false);
          try (PreparedStatement s =
                  JdbcAccess.statement(
                      c,
                      "SELECT c.id, c.chapter_order, c.version FROM tb_chapter c JOIN tb_project p"
                          + " ON p.id = c.project_id WHERE c.project_id = ?::uuid AND c.deleted_at"
                          + " IS NULL AND p.deleted_at IS NULL ORDER BY c.chapter_order, c.id",
                      projectId);
              var registration = cancel(context, s)) {
            check(context);
            try (ResultSet rows = s.executeQuery()) {
              List<ChapterVersionRow> result = new ArrayList<>();
              while (rows.next())
                result.add(
                    new ChapterVersionRow(rows.getString(1), rows.getInt(2), rows.getLong(3)));
              return result;
            }
          }
        });
  }

  public List<SettingText> settings(
      String projectId, long timeoutMillis, QueryCancellation context) {
    return db.execute(
        c -> {
          timeout(c, timeoutMillis, false);
          try (PreparedStatement s =
                  JdbcAccess.statement(
                      c,
                      "SELECT s.id, s.type, s.title, s.content, s.version FROM tb_setting s JOIN"
                          + " tb_project p ON p.id = s.project_id WHERE s.project_id = ?::uuid AND"
                          + " s.deleted_at IS NULL AND p.deleted_at IS NULL ORDER BY s.id",
                      projectId);
              var registration = cancel(context, s)) {
            check(context);
            try (ResultSet rows = s.executeQuery()) {
              List<SettingText> result = new ArrayList<>();
              while (rows.next())
                result.add(
                    new SettingText(
                        rows.getString(1),
                        rows.getString(2),
                        rows.getString(3),
                        rows.getString(4),
                        rows.getInt(5)));
              return result;
            }
          }
        });
  }

  private static ChapterText chapter(ResultSet row) throws SQLException {
    return new ChapterText(
        row.getString(1), row.getString(2), row.getInt(3), row.getLong(4), row.getString(5));
  }
}
