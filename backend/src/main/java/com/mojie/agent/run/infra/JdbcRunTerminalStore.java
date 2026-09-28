package com.mojie.agent.run.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.agent.loop.LoopOutcome;
import com.mojie.agent.tool.ToolContext.Candidate;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

/** Conditional terminal commit. A candidate becomes a proposal only in the SUCCEEDED transaction. */
public final class JdbcRunTerminalStore {
    private final DataSource dataSource;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public JdbcRunTerminalStore(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.clock = Objects.requireNonNull(clock);
    }

    public record Result(String status, String proposalId, String errorCode) {}
    private record RunRow(String status, boolean cancelRequested, Instant deadline, String targetChapterId,
                          int baseVersion, String baseContent, int modelCalls, int toolCalls, int toolText) {}

    public Result finish(String projectId, String runId, LoopOutcome outcome) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // All run-path writers use project -> run -> chapter lock order.
                lockProject(connection, projectId);
                RunRow row = lockRun(connection, projectId, runId);
                if (isTerminal(row.status())) {
                    Result existing = existingResult(connection, runId, row.status());
                    connection.commit();
                    return existing;
                }
                String status;
                String code = null;
                String detail = null;
                Candidate candidate = null;
                String analysis = null;
                if (row.cancelRequested() || outcome instanceof LoopOutcome.Cancelled) {
                    status = "CANCELLED";
                } else if (!clock.instant().isBefore(row.deadline())) {
                    status = "FAILED"; code = "RUN_TIMEOUT"; detail = "Run deadline reached";
                } else if (outcome instanceof LoopOutcome.Failed failure) {
                    status = "FAILED"; code = failure.code(); detail = failure.detail();
                } else if (outcome instanceof LoopOutcome.Completed completed) {
                    if (!"RUNNING".equals(row.status())) throw new IllegalStateException("Only a running task can succeed");
                    validateSuccess(connection, runId, row, completed);
                    status = "SUCCEEDED";
                    analysis = completed.analysis();
                    candidate = completed.candidate();
                } else throw new IllegalArgumentException("Unknown loop outcome");

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE tb_agent_run SET status = ?, analysis = ?, error_code = ?, error_detail = ?,
                                                finished_at = now(), updated_at = now()
                         WHERE id = ?::uuid AND status IN ('QUEUED', 'RUNNING')
                        """)) {
                    statement.setString(1, status); statement.setString(2, analysis);
                    statement.setString(3, code); statement.setString(4, detail); statement.setString(5, runId);
                    if (statement.executeUpdate() != 1) throw new IllegalStateException("Run status changed");
                }
                String proposalId = null;
                if (candidate != null) proposalId = insertProposal(connection, projectId, runId, row, candidate);
                finishOpenRecords(connection, runId, status);
                appendEvent(connection, runId, status, code, proposalId);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE tb_project SET active_run_id = NULL, updated_at = now()
                         WHERE id = ?::uuid AND active_run_id = ?::uuid
                        """)) {
                    statement.setString(1, projectId); statement.setString(2, runId);
                    statement.executeUpdate();
                }
                connection.commit();
                return new Result(status, proposalId, code);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw new IllegalStateException("Run terminal transaction failed", e);
            }
        } catch (SQLException e) { throw new IllegalStateException("Run terminal database unavailable", e); }
    }

    private void validateSuccess(Connection connection, String runId, RunRow row,
                                 LoopOutcome.Completed completed) throws SQLException {
        if (completed.analysis() == null || completed.analysis().isBlank()) throw new IllegalStateException("Empty analysis");
        if (row.modelCalls() != completed.modelCalls() || row.toolCalls() != completed.toolCalls()
                || row.toolText() != completed.toolTextCodePoints()) throw new IllegalStateException("Budget counters differ from trace");
        String sql = """
                SELECT EXISTS (
                    SELECT 1 FROM tb_agent_model_call final_call
                    JOIN tb_agent_message assistant ON assistant.run_id = final_call.run_id
                        AND assistant.model_call_id = final_call.id AND assistant.role = 'ASSISTANT'
                    WHERE final_call.run_id = ?::uuid AND final_call.call_index = ?
                      AND final_call.status = 'SUCCEEDED' AND final_call.finish_reason = 'FINAL'
                ), EXISTS (
                    SELECT 1 FROM tb_agent_context_item item
                    JOIN tb_agent_message reply ON reply.run_id = item.run_id
                        AND reply.tool_execution_id = item.tool_execution_id AND reply.role = 'TOOL'
                    JOIN tb_agent_model_call final_call ON final_call.run_id = item.run_id
                        AND final_call.call_index = ?
                    WHERE item.run_id = ?::uuid AND item.kind = 'FULL_CHAPTER'
                      AND item.chapter_id = ?::uuid AND item.chapter_version = ?
                      AND reply.message_seq = ANY(final_call.input_message_seqs)
                )
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId); statement.setInt(2, completed.modelCalls());
            statement.setInt(3, completed.modelCalls()); statement.setString(4, runId);
            statement.setString(5, row.targetChapterId()); statement.setInt(6, row.baseVersion());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (!result.getBoolean(1)) throw new IllegalStateException("Final model response is not committed");
                if (!result.getBoolean(2)) throw new IllegalStateException("Target chapter was not sent to final model call");
            }
        }
        if (completed.candidate() != null) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT EXISTS (SELECT 1 FROM tb_agent_step WHERE run_id = ?::uuid
                                   AND tool_name = 'propose_edit' AND status = 'SUCCEEDED')
                    """)) {
                statement.setString(1, runId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    if (!result.getBoolean(1)) throw new IllegalStateException("Candidate has no successful tool step");
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT count(*) FROM tb_agent_model_call WHERE run_id = ?::uuid AND status = 'STARTED'
                """)) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) { result.next(); if (result.getInt(1) != 0) throw new IllegalStateException("Model call remains open"); }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT count(*) FROM tb_agent_step WHERE run_id = ?::uuid AND status = 'STARTED'
                """)) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) { result.next(); if (result.getInt(1) != 0) throw new IllegalStateException("Tool step remains open"); }
        }
    }

    private static String insertProposal(Connection connection, String projectId, String runId,
                                         RunRow row, Candidate candidate) throws SQLException {
        String proposalId = UUID.randomUUID().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tb_edit_proposal(id, run_id, project_id, target_chapter_id, base_version,
                                             base_content, proposed_content, reason, status)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, 'PENDING')
                """)) {
            statement.setString(1, proposalId); statement.setString(2, runId); statement.setString(3, projectId);
            statement.setString(4, row.targetChapterId()); statement.setInt(5, row.baseVersion());
            statement.setString(6, row.baseContent()); statement.setString(7, candidate.proposedContent());
            statement.setString(8, candidate.reason()); statement.executeUpdate();
        }
        return proposalId;
    }

    private static void finishOpenRecords(Connection connection, String runId, String status) throws SQLException {
        if ("SUCCEEDED".equals(status)) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE tb_agent_model_call SET status = 'ABORTED', error_code = ?, finished_at = now()
                 WHERE run_id = ?::uuid AND status = 'STARTED'
                """)) {
            statement.setString(1, status); statement.setString(2, runId); statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE tb_agent_step SET status = 'ABORTED', error_code = ?, finished_at = now()
                 WHERE run_id = ?::uuid AND status = 'STARTED'
                """)) {
            statement.setString(1, status); statement.setString(2, runId); statement.executeUpdate();
        }
    }

    private void appendEvent(Connection connection, String runId, String status, String code, String proposalId) throws SQLException {
        long seq;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE tb_agent_run SET last_event_seq = last_event_seq + 1 WHERE id = ?::uuid RETURNING last_event_seq
                """)) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) { result.next(); seq = result.getLong(1); }
        }
        String type = switch (status) {
            case "SUCCEEDED" -> "run.succeeded";
            case "CANCELLED" -> "run.cancelled";
            case "FAILED" -> "run.failed";
            default -> throw new IllegalArgumentException("Invalid terminal status");
        };
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        if (code != null) payload.put("errorCode", code);
        if (proposalId != null) payload.put("proposalId", proposalId);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tb_agent_event(run_id, seq, type, payload) VALUES (?::uuid, ?, ?, ?::jsonb)
                """)) {
            statement.setString(1, runId); statement.setLong(2, seq); statement.setString(3, type);
            try { statement.setString(4, json.writeValueAsString(payload)); }
            catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize event", e); }
            statement.executeUpdate();
        }
    }

    private static void lockProject(Connection connection, String projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM tb_project WHERE id = ?::uuid FOR UPDATE")) {
            statement.setString(1, projectId);
            try (ResultSet result = statement.executeQuery()) { if (!result.next()) throw new IllegalStateException("Project unavailable"); }
        }
    }
    private static RunRow lockRun(Connection connection, String projectId, String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT status, cancel_requested, deadline_at, target_chapter_id, base_version,
                       base_content, model_call_count, tool_call_count, tool_text_codepoints
                  FROM tb_agent_run WHERE id = ?::uuid AND project_id = ?::uuid FOR UPDATE
                """)) {
            statement.setString(1, runId); statement.setString(2, projectId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("Run unavailable");
                return new RunRow(result.getString(1), result.getBoolean(2), result.getObject(3, java.time.OffsetDateTime.class).toInstant(),
                        result.getString(4), result.getInt(5), result.getString(6), result.getInt(7),
                        result.getInt(8), result.getInt(9));
            }
        }
    }
    private static Result existingResult(Connection connection, String runId, String status) throws SQLException {
        String proposalId = null;
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM tb_edit_proposal WHERE run_id = ?::uuid")) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) { if (result.next()) proposalId = result.getString(1); }
        }
        String errorCode = null;
        try (PreparedStatement statement = connection.prepareStatement("SELECT error_code FROM tb_agent_run WHERE id = ?::uuid")) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) { if (result.next()) errorCode = result.getString(1); }
        }
        return new Result(status, proposalId, errorCode);
    }
    private static boolean isTerminal(String status) { return Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status); }
}
