package com.mojie.agent.trace.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.agent.loop.LoopTrace;
import com.mojie.agent.model.ModelGateway;
import com.mojie.agent.model.ModelGateway.*;
import com.mojie.agent.tool.AgentTool;
import com.mojie.agent.tool.AgentTool.Evidence;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** Short, atomic PostgreSQL transactions for model calls, tool steps, messages, evidence and events. */
public final class JdbcLoopTrace implements LoopTrace {
    private final DataSource dataSource;
    private final JsonMapper json = JsonMapper.builder().build();

    public JdbcLoopTrace(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource); }

    @Override public void modelStarted(String runId, int callIndex, List<Long> inputMessageSeqs) {
        transaction(connection -> {
            requireActive(connection, runId);
            if (inputMessageSeqs.isEmpty() || new HashSet<>(inputMessageSeqs).size() != inputMessageSeqs.size())
                throw new IllegalStateException("Invalid model input message sequence");
            Array sequenceArray = connection.createArrayOf("bigint", inputMessageSeqs.toArray(Long[]::new));
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM tb_agent_message WHERE run_id = ?::uuid AND message_seq = ANY(?)")) {
                statement.setString(1, runId); statement.setArray(2, sequenceArray);
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    if (rows.getLong(1) != inputMessageSeqs.size()) throw new IllegalStateException("Model input message is not committed");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE tb_agent_run SET model_call_count = model_call_count + 1, updated_at = now()
                     WHERE id = ?::uuid AND status = 'RUNNING' AND cancel_requested = false
                       AND model_call_count < 8 RETURNING model_call_count
                    """)) {
                statement.setString(1, runId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getInt(1) != callIndex) throw new IllegalStateException("Model call budget changed");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO tb_agent_model_call(run_id, call_index, input_message_seqs, status)
                    VALUES (?::uuid, ?, ?, 'STARTED')
                    """)) {
                statement.setString(1, runId); statement.setInt(2, callIndex); statement.setArray(3, sequenceArray);
                statement.executeUpdate();
            }
            appendEvent(connection, runId, "model.started", Map.of("callIndex", callIndex));
            return null;
        });
    }

    @Override public long modelFinished(String runId, int callIndex, Response response) {
        return transaction(connection -> {
            requireActive(connection, runId);
            UUID callId = modelCallId(connection, runId, callIndex);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE tb_agent_model_call SET status = 'SUCCEEDED', finish_reason = ?, raw_finish_reason = ?,
                        provider_request_id = ?, usage = ?::jsonb, finished_at = now()
                     WHERE id = ? AND status = 'STARTED'
                    """)) {
                statement.setString(1, response.finishReason().name());
                statement.setString(2, response.rawFinishReason());
                statement.setString(3, response.providerRequestId());
                statement.setString(4, response.usage() == null ? null : encode(response.usage()));
                statement.setObject(5, callId);
                requireOne(statement.executeUpdate());
            }
            long messageSeq = nextMessageSeq(connection, runId);
            insertMessage(connection, runId, messageSeq, "ASSISTANT", encode(content(response.content())), callId, null, null);
            appendEvent(connection, runId, "model.finished", Map.of("callIndex", callIndex, "status", "SUCCEEDED",
                    "finishReason", response.finishReason().name()));
            return messageSeq;
        });
    }

    @Override public long modelFailed(String runId, int callIndex, String code, String detail) {
        return transaction(connection -> {
            requireActive(connection, runId);
            UUID callId = modelCallId(connection, runId, callIndex);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE tb_agent_model_call SET status = 'FAILED', error_code = ?, error_detail = ?, finished_at = now()
                     WHERE id = ? AND status = 'STARTED'
                    """)) {
                statement.setString(1, code); statement.setString(2, detail); statement.setObject(3, callId);
                requireOne(statement.executeUpdate());
            }
            return appendEvent(connection, runId, "model.finished", Map.of("callIndex", callIndex, "status", "FAILED", "errorCode", code));
        });
    }

    @Override public String toolStarted(String runId, int callIndex, ToolCall call) {
        return transaction(connection -> {
            requireActive(connection, runId);
            UUID callId = modelCallId(connection, runId, callIndex);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE tb_agent_run SET tool_call_count = tool_call_count + 1, updated_at = now()
                     WHERE id = ?::uuid AND status = 'RUNNING' AND cancel_requested = false
                       AND tool_call_count < 12 RETURNING tool_call_count
                    """)) {
                statement.setString(1, runId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("Tool call budget changed");
                }
            }
            UUID executionId = UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO tb_agent_step(tool_execution_id, run_id, model_call_id, tool_call_id, tool_name, args, status)
                    VALUES (?, ?::uuid, ?, ?, ?, ?::jsonb, 'STARTED')
                    """)) {
                statement.setObject(1, executionId); statement.setString(2, runId); statement.setObject(3, callId);
                statement.setString(4, call.id()); statement.setString(5, call.name());
                statement.setString(6, call.arguments() == null ? null : encode(call.arguments()));
                statement.executeUpdate();
            }
            appendEvent(connection, runId, "tool.started", Map.of("callIndex", callIndex,
                    "toolExecutionId", executionId.toString(), "toolName", call.name()));
            return executionId.toString();
        });
    }

    @Override public long toolFinished(String runId, int callIndex, ToolCall call, String executionId,
                                       AgentTool.ToolResult result, String wireJson, int cumulativeCodePoints,
                                       boolean returnedToModel) {
        return transaction(connection -> {
            requireActive(connection, runId);
            UUID callId = modelCallId(connection, runId, callIndex);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE tb_agent_step SET status = ?, result = ?::jsonb, error_code = ?, finished_at = now()
                     WHERE run_id = ?::uuid AND tool_execution_id = ?::uuid AND model_call_id = ?
                       AND tool_call_id = ? AND status = 'STARTED'
                    """)) {
                statement.setString(1, result.success() ? "SUCCEEDED" : "FAILED");
                statement.setString(2, wireJson);
                statement.setString(3, result.error() == null ? null : result.error().code());
                statement.setString(4, runId); statement.setString(5, executionId);
                statement.setObject(6, callId); statement.setString(7, call.id());
                requireOne(statement.executeUpdate());
            }
            for (Evidence evidence : result.evidence()) insertEvidence(connection, runId, executionId, evidence);
            long messageSeq = -1;
            if (returnedToModel) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE tb_agent_run SET tool_text_codepoints = ?, updated_at = now()
                         WHERE id = ?::uuid AND tool_text_codepoints = ? AND status = 'RUNNING'
                           AND cancel_requested = false
                        """)) {
                    statement.setInt(1, cumulativeCodePoints); statement.setString(2, runId);
                    statement.setInt(3, cumulativeCodePoints - wireJson.codePointCount(0, wireJson.length()));
                    requireOne(statement.executeUpdate());
                }
                messageSeq = nextMessageSeq(connection, runId);
                insertMessage(connection, runId, messageSeq, "TOOL", wireJson, callId, call.id(), UUID.fromString(executionId));
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolExecutionId", executionId);
            payload.put("toolName", call.name());
            payload.put("status", result.success() ? "SUCCEEDED" : "FAILED");
            payload.put("returnedToModel", returnedToModel);
            if (result.error() != null) payload.put("errorCode", result.error().code());
            appendEvent(connection, runId, "tool.finished", payload);
            return messageSeq;
        });
    }

    @Override public void toolAborted(String runId, int callIndex, ToolCall call, String reason) {
        transaction(connection -> {
            lockRun(connection, runId);
            UUID callId = modelCallId(connection, runId, callIndex);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO tb_agent_step(run_id, model_call_id, tool_call_id, tool_name, args,
                                              status, error_code, finished_at)
                    VALUES (?::uuid, ?, ?, ?, ?::jsonb, 'ABORTED', ?, now())
                    """)) {
                statement.setString(1, runId); statement.setObject(2, callId); statement.setString(3, call.id());
                statement.setString(4, call.name());
                statement.setString(5, call.arguments() == null ? null : encode(call.arguments()));
                statement.setString(6, reason);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private void insertEvidence(Connection connection, String runId, String executionId, Evidence evidence) throws SQLException {
        String sql = """
                INSERT INTO tb_agent_context_item(run_id, tool_execution_id, kind, chapter_id, chapter_version,
                                                  setting_id, line_number, codepoint_offset, content_hash)
                VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?, ?::uuid, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId); statement.setString(2, executionId); statement.setString(3, evidence.kind());
            boolean chapter = "CHAPTER".equals(evidence.resourceType());
            boolean setting = "SETTING".equals(evidence.resourceType());
            if (!chapter && !setting) throw new IllegalArgumentException("Unknown evidence resource type");
            statement.setString(4, chapter ? evidence.resourceId() : null);
            if (chapter) statement.setInt(5, Integer.parseInt(evidence.version())); else statement.setNull(5, Types.INTEGER);
            statement.setString(6, setting ? evidence.resourceId() : null);
            if (evidence.line() == null) statement.setNull(7, Types.INTEGER); else statement.setInt(7, evidence.line());
            if (evidence.codePointOffset() == null) statement.setNull(8, Types.INTEGER); else statement.setInt(8, evidence.codePointOffset());
            statement.setString(9, setting ? evidence.version() : null);
            statement.executeUpdate();
        }
    }

    private long appendEvent(Connection connection, String runId, String type, Object payload) throws SQLException {
        long seq;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE tb_agent_run SET last_event_seq = last_event_seq + 1, updated_at = now()
                 WHERE id = ?::uuid RETURNING last_event_seq
                """)) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Run unavailable");
                seq = rows.getLong(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tb_agent_event(run_id, seq, type, payload) VALUES (?::uuid, ?, ?, ?::jsonb)
                """)) {
            statement.setString(1, runId); statement.setLong(2, seq); statement.setString(3, type);
            statement.setString(4, encode(payload)); statement.executeUpdate();
        }
        return seq;
    }

    private static void requireActive(Connection connection, String runId) throws SQLException {
        RunState state = lockRun(connection, runId);
        if (!"RUNNING".equals(state.status()) || state.cancelRequested())
            throw new IllegalStateException("Run is no longer active");
    }
    private record RunState(String status, boolean cancelRequested) {}
    private static RunState lockRun(Connection connection, String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status, cancel_requested FROM tb_agent_run WHERE id = ?::uuid FOR UPDATE")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Run is unavailable");
                return new RunState(rows.getString(1), rows.getBoolean(2));
            }
        }
    }

    private static UUID modelCallId(Connection connection, String runId, int callIndex) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM tb_agent_model_call WHERE run_id = ?::uuid AND call_index = ?")) {
            statement.setString(1, runId); statement.setInt(2, callIndex);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Model call unavailable");
                return rows.getObject(1, UUID.class);
            }
        }
    }
    private static long nextMessageSeq(Connection connection, String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(message_seq), 0) + 1 FROM tb_agent_message WHERE run_id = ?::uuid")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) { rows.next(); return rows.getLong(1); }
        }
    }
    private static void insertMessage(Connection connection, String runId, long seq, String role, String body,
                                      UUID callId, String toolCallId, UUID executionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tb_agent_message(run_id, message_seq, role, content, model_call_id, tool_call_id, tool_execution_id)
                VALUES (?::uuid, ?, ?, ?::jsonb, ?, ?, ?)
                """)) {
            statement.setString(1, runId); statement.setLong(2, seq); statement.setString(3, role);
            statement.setString(4, body); statement.setObject(5, callId); statement.setString(6, toolCallId);
            statement.setObject(7, executionId); statement.executeUpdate();
        }
    }
    private static List<Map<String, Object>> content(List<ContentPart> parts) {
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (ContentPart part : parts) {
            if (part instanceof TextPart text) serialized.add(Map.of("type", "text", "text", text.text()));
            else if (part instanceof ToolCall call) serialized.add(Map.of("type", "tool_call", "id", call.id(),
                    "name", call.name(), "arguments", call.arguments() == null ? Map.of() : call.arguments()));
            else if (part instanceof OpaquePart opaque) serialized.add(Map.of("type", "opaque", "block", opaque.providerBlock()));
            else throw new IllegalArgumentException("Unexpected response part");
        }
        return serialized;
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize run record", e); }
    }
    private static void requireOne(int updated) { if (updated != 1) throw new IllegalStateException("Run record changed concurrently"); }
    private interface SqlWork<T> { T run(Connection connection) throws SQLException; }
    private <T> T transaction(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T value = work.run(connection);
                connection.commit();
                return value;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw new IllegalStateException("Run trace transaction failed", e);
            }
        } catch (SQLException e) { throw new IllegalStateException("Run trace database unavailable", e); }
    }
}
