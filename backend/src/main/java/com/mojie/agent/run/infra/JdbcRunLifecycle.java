package com.mojie.agent.run.infra;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** Database lifecycle transitions. Local executor handles are managed separately. */
public final class JdbcRunLifecycle {
    private final DataSource dataSource;
    private final JdbcRunTerminalStore terminalStore;

    public JdbcRunLifecycle(DataSource dataSource, JdbcRunTerminalStore terminalStore) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.terminalStore = Objects.requireNonNull(terminalStore);
    }
    public record CancelResult(String status, boolean cancelRequested) {}
    private record ActiveRun(String projectId, String runId) {}

    /** Claim a queued run exactly once. A false return requires the caller to inspect its persisted state. */
    public boolean start(String projectId, String runId, UUID processInstanceId) {
        return transaction(connection -> {
            lockProject(connection, projectId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE tb_agent_run SET status = 'RUNNING', started_at = clock_timestamp(),
                        deadline_at = clock_timestamp() + interval '180 seconds', updated_at = now()
                     WHERE id = ?::uuid AND project_id = ?::uuid AND status = 'QUEUED'
                       AND cancel_requested = false AND process_instance_id = ?
                       AND deadline_at > clock_timestamp()
                    """)) {
                statement.setString(1, runId); statement.setString(2, projectId);
                statement.setObject(3, processInstanceId);
                if (statement.executeUpdate() != 1) return false;
            }
            appendEvent(connection, runId, "run.started", "{\"status\":\"RUNNING\"}");
            return true;
        });
    }

    /** Queued work cancels immediately; running work records a request for the runner to acknowledge. */
    public CancelResult requestCancel(String projectId, String runId) {
        return transaction(connection -> {
            lockProject(connection, projectId);
            String status;
            boolean alreadyRequested;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT status, cancel_requested FROM tb_agent_run
                     WHERE id = ?::uuid AND project_id = ?::uuid FOR UPDATE
                    """)) {
                statement.setString(1, runId); statement.setString(2, projectId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("Run unavailable");
                    status = rows.getString(1); alreadyRequested = rows.getBoolean(2);
                }
            }
            if ("QUEUED".equals(status)) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE tb_agent_run SET status = 'CANCELLED', cancel_requested = true,
                            finished_at = now(), updated_at = now() WHERE id = ?::uuid
                        """)) {
                    statement.setString(1, runId); statement.executeUpdate();
                }
                appendEvent(connection, runId, "run.cancelled", "{\"status\":\"CANCELLED\"}");
                releaseSlot(connection, projectId, runId);
                return new CancelResult("CANCELLED", true);
            }
            if ("RUNNING".equals(status)) {
                if (!alreadyRequested) try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE tb_agent_run SET cancel_requested = true, updated_at = now() WHERE id = ?::uuid
                        """)) {
                    statement.setString(1, runId); statement.executeUpdate();
                }
                return new CancelResult("RUNNING", true);
            }
            return new CancelResult(status, alreadyRequested);
        });
    }

    /** The independent sweep runs every two seconds; callers signal matching local handles afterward. */
    public List<String> sweepTimeouts() {
        List<ActiveRun> overdue = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT project_id, id FROM tb_agent_run
                 WHERE status IN ('QUEUED', 'RUNNING') AND deadline_at <= clock_timestamp()
                 ORDER BY deadline_at, id
                """)) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) overdue.add(new ActiveRun(rows.getString(1), rows.getString(2)));
            }
        } catch (SQLException e) { throw new IllegalStateException("Timeout scan failed", e); }
        List<String> ended = new ArrayList<>();
        for (ActiveRun run : overdue) {
            JdbcRunTerminalStore.Result result = terminalStore.finish(run.projectId(), run.runId(),
                    new com.mojie.agent.loop.LoopOutcome.Failed("RUN_TIMEOUT", "Run deadline reached"));
            if ("FAILED".equals(result.status()) || "CANCELLED".equals(result.status())) ended.add(run.runId());
        }
        return ended;
    }

    /** Called after migrations and before accepting work in a single-instance deployment. */
    public List<String> failPreviousProcessRuns(UUID currentProcessId) {
        List<ActiveRun> abandoned = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT project_id, id FROM tb_agent_run
                 WHERE status IN ('QUEUED', 'RUNNING')
                   AND (process_instance_id IS NULL OR process_instance_id <> ?)
                 ORDER BY project_id, id
                """)) {
            statement.setObject(1, currentProcessId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) abandoned.add(new ActiveRun(rows.getString(1), rows.getString(2)));
            }
        } catch (SQLException e) { throw new IllegalStateException("Restart cleanup scan failed", e); }
        List<String> ended = new ArrayList<>();
        for (ActiveRun run : abandoned) {
            terminalStore.finish(run.projectId(), run.runId(),
                    new com.mojie.agent.loop.LoopOutcome.Failed("SERVICE_RESTARTED", "Previous process stopped"));
            ended.add(run.runId());
        }
        return ended;
    }

    private static void appendEvent(Connection connection, String runId, String type, String payload) throws SQLException {
        long seq;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE tb_agent_run SET last_event_seq = last_event_seq + 1 WHERE id = ?::uuid RETURNING last_event_seq
                """)) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) { rows.next(); seq = rows.getLong(1); }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tb_agent_event(run_id, seq, type, payload) VALUES (?::uuid, ?, ?, ?::jsonb)
                """)) {
            statement.setString(1, runId); statement.setLong(2, seq); statement.setString(3, type);
            statement.setString(4, payload); statement.executeUpdate();
        }
    }
    private static void lockProject(Connection connection, String projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM tb_project WHERE id = ?::uuid FOR UPDATE")) {
            statement.setString(1, projectId);
            try (ResultSet rows = statement.executeQuery()) { if (!rows.next()) throw new IllegalStateException("Project unavailable"); }
        }
    }
    private static void releaseSlot(Connection connection, String projectId, String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE tb_project SET active_run_id = NULL, updated_at = now()
                 WHERE id = ?::uuid AND active_run_id = ?::uuid
                """)) {
            statement.setString(1, projectId); statement.setString(2, runId); statement.executeUpdate();
        }
    }
    private interface SqlWork<T> { T run(Connection connection) throws SQLException; }
    private <T> T transaction(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw new IllegalStateException("Run lifecycle transaction failed", e);
            }
        } catch (SQLException e) { throw new IllegalStateException("Run lifecycle database unavailable", e); }
    }
}
