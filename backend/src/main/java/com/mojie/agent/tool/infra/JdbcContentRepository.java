package com.mojie.agent.tool.infra;

import com.mojie.agent.tool.ContentRepository;
import com.mojie.agent.tool.ToolContext;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** PostgreSQL adapter for the scoped content reads used by the seven tools. */
public final class JdbcContentRepository implements ContentRepository {
    private final DataSource dataSource;

    public JdbcContentRepository(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource); }

    @Override public List<ToolContext.Chapter> chapters(String projectId) {
        String sql = """
                SELECT c.id, c.title, c.chapter_order, c.version, c.content
                  FROM tb_chapter c JOIN tb_project p ON p.id = c.project_id
                 WHERE c.project_id = ?::uuid AND c.deleted_at IS NULL AND p.deleted_at IS NULL
                 ORDER BY c.chapter_order, c.id
                """;
        try (Connection connection = dataSource.getConnection()) {
            return loadChapters(connection, sql, projectId);
        } catch (SQLException e) { throw failure(e); }
    }

    @Override public List<ToolContext.Chapter> chapters(String projectId, long timeoutMillis) {
        try (Connection connection = dataSource.getConnection()) {
            searchTimeout(connection, timeoutMillis);
            List<ToolContext.Chapter> result = loadChapters(connection, """
                    SELECT c.id, c.title, c.chapter_order, c.version, c.content
                      FROM tb_chapter c JOIN tb_project p ON p.id = c.project_id
                     WHERE c.project_id = ?::uuid AND c.deleted_at IS NULL AND p.deleted_at IS NULL
                     ORDER BY c.chapter_order, c.id
                    """, projectId);
            connection.commit(); return result;
        } catch (SQLException e) { throw searchFailure(e); }
    }

    @Override public ToolContext.Chapter chapterRevision(String projectId, String chapterId, long version) {
        String sql = """
                SELECT c.id, c.title, c.chapter_order, r.version, r.content
                  FROM tb_chapter c
                  JOIN tb_project p ON p.id = c.project_id
                  JOIN tb_chapter_revision r ON r.chapter_id = c.id
                 WHERE c.project_id = ?::uuid AND c.id = ?::uuid AND r.version = ?
                   AND c.deleted_at IS NULL AND p.deleted_at IS NULL
                """;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            statement.setString(2, chapterId);
            statement.setLong(3, version);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? chapter(rows) : null; }
        } catch (SQLException e) { throw failure(e); }
    }

    @Override public ToolContext.Chapter chapterRevision(String projectId, String chapterId, long version, long timeoutMillis) {
        String sql = """
                SELECT c.id, c.title, c.chapter_order, r.version, r.content
                  FROM tb_chapter c JOIN tb_project p ON p.id = c.project_id
                  JOIN tb_chapter_revision r ON r.chapter_id = c.id
                 WHERE c.project_id = ?::uuid AND c.id = ?::uuid AND r.version = ?
                   AND c.deleted_at IS NULL AND p.deleted_at IS NULL
                """;
        try (Connection connection = dataSource.getConnection()) {
            searchTimeout(connection, timeoutMillis);
            ToolContext.Chapter result;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, projectId); statement.setString(2, chapterId); statement.setLong(3, version);
                try (ResultSet rows = statement.executeQuery()) { result = rows.next() ? chapter(rows) : null; }
            }
            connection.commit(); return result;
        } catch (SQLException e) { throw searchFailure(e); }
    }

    @Override public ChapterView captureChapterView(String runId, String projectId, String targetId,
                                                    long baseVersion, Map<String, ToolContext.Chapter> alreadyRead) {
        return captureChapterView(runId, projectId, targetId, baseVersion, alreadyRead, 5_000);
    }

    @Override public ChapterView captureChapterView(String runId, String projectId, String targetId,
                                                    long baseVersion, Map<String, ToolContext.Chapter> alreadyRead,
                                                    long timeoutMillis) {
        // The version list is read in one short repeatable-read transaction. No database write occurs there.
        List<ViewRow> snapshot;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            setLocalTimeout(connection, timeoutMillis);
            snapshot = loadViewRows(connection, """
                    SELECT c.id, c.chapter_order, c.version
                      FROM tb_chapter c JOIN tb_project p ON p.id = c.project_id
                     WHERE c.project_id = ?::uuid AND c.deleted_at IS NULL AND p.deleted_at IS NULL
                     ORDER BY c.chapter_order, c.id
                    """, projectId);
            connection.commit();
        } catch (SQLException e) { throw searchFailure(e); }

        Map<String, Long> proposed = new LinkedHashMap<>();
        for (ViewRow chapter : snapshot) {
            ToolContext.Chapter prior = alreadyRead.get(chapter.id());
            proposed.put(chapter.id(), chapter.id().equals(targetId) ? baseVersion
                    : prior == null ? chapter.version() : prior.version());
        }
        if (!proposed.containsKey(targetId)) throw new IllegalStateException("Target chapter is unavailable");

        // A separate short write transaction makes the view durable before any content scan begins.
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                setLocalTimeout(connection, timeoutMillis);
                String statusSql = "SELECT status FROM tb_agent_run WHERE id = ?::uuid AND project_id = ?::uuid FOR UPDATE";
                try (PreparedStatement statement = connection.prepareStatement(statusSql)) {
                    statement.setString(1, runId); statement.setString(2, projectId);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next() || !"RUNNING".equals(rows.getString(1)))
                            throw new IllegalStateException("Run is no longer active");
                    }
                }
                ChapterView existing = readView(connection, runId);
                if (existing != null) { connection.commit(); return existing; }
                UUID viewId = UUID.randomUUID();
                String insertSql = """
                        INSERT INTO tb_agent_run_chapter_view(run_id, chapter_id, chapter_order, version, view_id)
                        VALUES (?::uuid, ?::uuid, ?, ?, ?)
                        """;
                try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
                    for (ViewRow chapter : snapshot) {
                        statement.setString(1, runId);
                        statement.setString(2, chapter.id());
                        statement.setInt(3, chapter.order());
                        statement.setLong(4, proposed.get(chapter.id()));
                        statement.setObject(5, viewId);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
                return new ChapterView(viewId.toString(), proposed);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                if (e instanceof SQLException sql) throw searchFailure(sql);
                throw e;
            }
        } catch (SQLException e) { throw searchFailure(e); }
    }

    @Override public List<Setting> settings(String projectId) {
        String sql = """
                SELECT s.id, s.type, s.title, s.content
                  FROM tb_setting s JOIN tb_project p ON p.id = s.project_id
                 WHERE s.project_id = ?::uuid AND s.deleted_at IS NULL AND p.deleted_at IS NULL
                 ORDER BY s.id
                """;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            try (ResultSet rows = statement.executeQuery()) {
                List<Setting> result = new ArrayList<>();
                while (rows.next()) result.add(new Setting(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4)));
                return result;
            }
        } catch (SQLException e) { throw failure(e); }
    }

    @Override public List<Setting> settings(String projectId, long timeoutMillis) {
        try (Connection connection = dataSource.getConnection()) {
            searchTimeout(connection, timeoutMillis);
            List<Setting> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT s.id, s.type, s.title, s.content
                      FROM tb_setting s JOIN tb_project p ON p.id = s.project_id
                     WHERE s.project_id = ?::uuid AND s.deleted_at IS NULL AND p.deleted_at IS NULL
                     ORDER BY s.id
                    """)) {
                statement.setString(1, projectId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) result.add(new Setting(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4)));
                }
            }
            connection.commit(); return result;
        } catch (SQLException e) { throw searchFailure(e); }
    }

    private static void searchTimeout(Connection connection, long timeoutMillis) throws SQLException {
        connection.setAutoCommit(false);
        setLocalTimeout(connection, timeoutMillis);
    }
    private static void setLocalTimeout(Connection connection, long timeoutMillis) throws SQLException {
        long bounded = Math.max(1, Math.min(timeoutMillis, 5_000));
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('statement_timeout', ?, true)")) {
            statement.setString(1, bounded + "ms"); statement.execute();
        }
    }
    private static RuntimeException searchFailure(SQLException error) {
        return "57014".equals(error.getSQLState()) ? new ContentRepository.SearchTimeoutException(error) : failure(error);
    }

    private static ChapterView readView(Connection connection, String runId) throws SQLException {
        Map<String, Long> view = new LinkedHashMap<>();
        String viewId = null;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT chapter_id, version, view_id FROM tb_agent_run_chapter_view WHERE run_id = ?::uuid ORDER BY chapter_order, chapter_id")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    view.put(rows.getString(1), rows.getLong(2));
                    if (viewId == null) viewId = rows.getString(3);
                    else if (!viewId.equals(rows.getString(3))) throw new SQLException("Inconsistent chapter view ID");
                }
            }
        }
        return view.isEmpty() ? null : new ChapterView(viewId, view);
    }

    private static List<ToolContext.Chapter> loadChapters(Connection connection, String sql, String projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            try (ResultSet rows = statement.executeQuery()) {
                List<ToolContext.Chapter> result = new ArrayList<>();
                while (rows.next()) result.add(chapter(rows));
                return result;
            }
        }
    }
    private record ViewRow(String id, int order, long version) {}
    private static List<ViewRow> loadViewRows(Connection connection, String sql, String projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            try (ResultSet rows = statement.executeQuery()) {
                List<ViewRow> result = new ArrayList<>();
                while (rows.next()) result.add(new ViewRow(rows.getString(1), rows.getInt(2), rows.getLong(3)));
                return result;
            }
        }
    }
    private static ToolContext.Chapter chapter(ResultSet row) throws SQLException {
        return new ToolContext.Chapter(row.getString(1), row.getString(2), row.getInt(3), row.getLong(4), row.getString(5));
    }
    private static IllegalStateException failure(SQLException error) {
        return new IllegalStateException("Content database operation failed", error);
    }
}
