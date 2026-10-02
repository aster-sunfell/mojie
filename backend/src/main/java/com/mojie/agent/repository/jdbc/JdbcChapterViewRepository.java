package com.mojie.agent.repository.jdbc;

import static com.mojie.common.repository.jdbc.SearchStatements.*;

import com.mojie.agent.model.ChapterView;
import com.mojie.agent.repository.ChapterViewRepository;
import com.mojie.common.cancellation.QueryCancellation;
import com.mojie.common.repository.jdbc.JdbcAccess;
import com.mojie.content.model.ChapterVersionRow;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

public final class JdbcChapterViewRepository implements ChapterViewRepository {
  private final JdbcAccess db;

  public JdbcChapterViewRepository(DataSource source) {
    this.db = new JdbcAccess(source);
  }

  public String lockRun(
      String runId, String projectId, long timeoutMillis, QueryCancellation context) {
    return db.execute(
        c -> {
          timeout(c, timeoutMillis, true);
          try (PreparedStatement s =
                  JdbcAccess.statement(
                      c,
                      "SELECT status FROM tb_agent_run WHERE id = ?::uuid AND project_id = ?::uuid"
                          + " FOR UPDATE",
                      runId,
                      projectId);
              var registration = cancel(context, s)) {
            check(context);
            try (ResultSet rows = s.executeQuery()) {
              return rows.next() ? rows.getString(1) : null;
            }
          }
        });
  }

  public ChapterView find(String runId, QueryCancellation context) {
    return db.execute(
        c -> {
          Map<String, Long> view = new LinkedHashMap<>();
          String viewId = null;
          try (PreparedStatement s =
                  JdbcAccess.statement(
                      c,
                      "SELECT chapter_id, version, view_id FROM tb_agent_run_chapter_view WHERE"
                          + " run_id = ?::uuid ORDER BY chapter_order, chapter_id",
                      runId);
              var registration = cancel(context, s)) {
            check(context);
            try (ResultSet rows = s.executeQuery()) {
              while (rows.next()) {
                view.put(rows.getString(1), rows.getLong(2));
                if (viewId == null) viewId = rows.getString(3);
                else if (!viewId.equals(rows.getString(3)))
                  throw new SQLException("Inconsistent chapter view ID");
              }
            }
          }
          return view.isEmpty() ? null : new ChapterView(viewId, view);
        });
  }

  public void insert(
      String runId,
      UUID viewId,
      List<ChapterVersionRow> chapters,
      Map<String, Long> versions,
      QueryCancellation context) {
    db.execute(
        c -> {
          try (PreparedStatement s =
                  c.prepareStatement(
                      "INSERT INTO tb_agent_run_chapter_view(run_id, chapter_id, chapter_order,"
                          + " version, view_id) VALUES (?::uuid, ?::uuid, ?, ?, ?)");
              var registration = cancel(context, s)) {
            for (ChapterVersionRow chapter : chapters) {
              s.setString(1, runId);
              s.setString(2, chapter.id());
              s.setInt(3, chapter.order());
              s.setLong(4, versions.get(chapter.id()));
              s.setObject(5, viewId);
              s.addBatch();
            }
            check(context);
            s.executeBatch();
            return null;
          }
        });
  }
}
