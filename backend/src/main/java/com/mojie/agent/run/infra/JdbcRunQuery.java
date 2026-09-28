package com.mojie.agent.run.infra;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.common.Page;
import com.mojie.common.PageCursor;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import javax.sql.DataSource;

/** Product snapshots and committed event replay, scoped to the configured single author. */
public final class JdbcRunQuery {
    private final DataSource dataSource;
    private final JsonMapper json = JsonMapper.builder().build();
    private final PageCursor cursors = PageCursor.configured();
    public JdbcRunQuery(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource); }
    public static final class NotFound extends RuntimeException { public NotFound() { super("Run or proposal unavailable"); } }
    public record Event(long seq, String type, Object payload, String occurredAt) {}

    public List<Map<String,Object>> list(String owner, String projectId, String chapterId, String status, int limit) {
        return page(owner, projectId, chapterId, status, null, limit).items();
    }
    public Page<Map<String,Object>> page(String owner, String projectId, String chapterId, String status,
                                          String cursor, int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("Invalid limit");
        UUID.fromString(projectId);
        if (chapterId != null) UUID.fromString(chapterId);
        String scope = "runs:" + owner + ":" + projectId + ":" + chapterId + ":" + status;
        String marker = cursors.decode(scope, cursor);
        String afterTime = null, afterId = null;
        if (marker != null) {
            String[] parts = marker.split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException("Invalid cursor");
            try { OffsetDateTime.parse(parts[0]); UUID.fromString(parts[1]); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Invalid cursor", e); }
            afterTime = parts[0]; afterId = parts[1];
        }
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement("""
                SELECT r.id, r.project_id, r.target_chapter_id, r.base_version, r.instruction,
                       r.status, r.cancel_requested, r.queued_at, r.started_at, r.finished_at,
                       r.analysis, r.error_code, r.error_detail, r.last_event_seq, p.id AS proposal_id,
                       r.created_at
                  FROM tb_agent_run r LEFT JOIN tb_edit_proposal p ON p.run_id = r.id
                 WHERE r.owner_id = ? AND r.project_id = ?::uuid
                   AND (?::uuid IS NULL OR r.target_chapter_id = ?::uuid)
                   AND (?::text IS NULL OR r.status = ?)
                   AND (?::timestamptz IS NULL OR (r.created_at, r.id) < (?::timestamptz, ?::uuid))
                 ORDER BY r.created_at DESC, r.id DESC LIMIT ?
                """)) {
            s.setString(1, owner); s.setString(2, projectId); s.setString(3, chapterId); s.setString(4, chapterId);
            s.setString(5, status); s.setString(6, status); s.setString(7, afterTime);
            s.setString(8, afterTime); s.setString(9, afterId); s.setInt(10, limit + 1);
            try (ResultSet rs = s.executeQuery()) {
                List<Map<String,Object>> rows = rows(rs);
                if (rows.size() <= limit) return new Page<>(rows, null);
                List<Map<String,Object>> items = rows.subList(0, limit);
                Map<String,Object> last = items.getLast();
                return new Page<>(items, cursors.encode(scope, last.get("createdAt") + "|" + last.get("id")));
            }
        } catch (SQLException e) { throw new IllegalStateException("Run list unavailable", e); }
    }

    public Map<String,Object> detail(String owner, String runId) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false); c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            Map<String,Object> run = scoped(c, owner, runId);
            run.put("steps", query(c, """
                    SELECT s.tool_execution_id, s.tool_call_id, s.tool_name, s.args, s.result,
                           s.summary, s.status, s.error_code, s.started_at, s.finished_at,
                           mc.call_index AS model_call_index
                      FROM tb_agent_step s JOIN tb_agent_model_call mc ON mc.id = s.model_call_id
                     WHERE s.run_id = ?::uuid ORDER BY mc.call_index, s.started_at, s.tool_execution_id
                    """, runId));
            run.put("sources", query(c, """
                    SELECT i.id, i.kind, i.chapter_id, i.chapter_version, i.setting_id,
                           i.line_number, i.codepoint_offset, i.content_hash, i.tool_execution_id,
                           EXISTS (SELECT 1 FROM tb_agent_message m
                                    JOIN tb_agent_model_call mc ON mc.run_id = m.run_id
                                   AND m.message_seq = ANY(mc.input_message_seqs)
                                   AND mc.status = 'SUCCEEDED'
                                   WHERE m.run_id = i.run_id AND m.tool_execution_id = i.tool_execution_id)
                             AS provided_to_model
                      FROM tb_agent_context_item i WHERE i.run_id = ?::uuid ORDER BY i.created_at, i.id
                    """, runId));
            c.commit();
            return run;
        } catch (SQLException e) { throw new IllegalStateException("Run detail unavailable", e); }
    }
    public List<Event> events(String owner, String runId, long afterSeq, int limit) {
        if (afterSeq < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid event cursor");
        try (Connection c = dataSource.getConnection()) {
            scoped(c, owner, runId);
            try (PreparedStatement s = statement(c, "SELECT seq, type, payload, occurred_at FROM tb_agent_event WHERE run_id = ?::uuid AND seq > ? ORDER BY seq LIMIT ?", runId, afterSeq, limit);
                 ResultSet rs = s.executeQuery()) {
                List<Event> result = new ArrayList<>();
                while (rs.next()) result.add(new Event(rs.getLong(1), rs.getString(2), decode(rs.getString(3)), rs.getObject(4, OffsetDateTime.class).toString()));
                return result;
            }
        } catch (SQLException e) { throw new IllegalStateException("Run events unavailable", e); }
    }
    public Map<String,Object> trace(String owner, String runId) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false); c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            Map<String,Object> result = scoped(c, owner, runId);
            result.put("modelCalls", query(c, "SELECT id, call_index, input_message_seqs, status, finish_reason, raw_finish_reason, provider_request_id, error_code, error_detail, usage, started_at, finished_at FROM tb_agent_model_call WHERE run_id = ?::uuid ORDER BY call_index", runId));
            result.put("messages", query(c, "SELECT message_seq, role, content, model_call_id, tool_call_id, tool_execution_id, created_at FROM tb_agent_message WHERE run_id = ?::uuid ORDER BY message_seq", runId));
            result.put("steps", query(c, "SELECT tool_execution_id, model_call_id, tool_call_id, tool_name, args, result, status, error_code, started_at, finished_at FROM tb_agent_step WHERE run_id = ?::uuid ORDER BY started_at, tool_execution_id", runId));
            result.put("sources", query(c, "SELECT kind, chapter_id, chapter_version, setting_id, line_number, codepoint_offset, content_hash, tool_execution_id FROM tb_agent_context_item WHERE run_id = ?::uuid ORDER BY created_at, id", runId));
            c.commit(); return result;
        } catch (SQLException e) { throw new IllegalStateException("Run trace unavailable", e); }
    }
    public Map<String,Object> proposal(String owner, String proposalId) {
        try (Connection c = dataSource.getConnection()) {
            Map<String,Object> value = first(c, """
                    SELECT p.id, p.run_id, p.project_id, p.target_chapter_id, p.base_version,
                           p.base_content, p.proposed_content, p.reason, p.status, p.applied_version,
                           p.created_at, p.updated_at
                      FROM tb_edit_proposal p JOIN tb_agent_run r ON r.id = p.run_id
                     WHERE p.id = ?::uuid AND r.owner_id = ?
                    """, proposalId, owner);
            if (value == null) throw new NotFound();
            return value;
        } catch (SQLException e) { throw new IllegalStateException("Proposal unavailable", e); }
    }
    private Map<String,Object> scoped(Connection c, String owner, String runId) throws SQLException {
        Map<String,Object> run = first(c, """
                SELECT r.id, r.project_id, r.target_chapter_id, r.base_version, r.instruction,
                       r.status, r.cancel_requested, r.queued_at, r.started_at, r.finished_at,
                       r.analysis, r.error_code, r.error_detail, r.model_call_count,
                       r.tool_call_count, r.tool_text_codepoints, r.last_event_seq,
                       p.id AS proposal_id
                  FROM tb_agent_run r LEFT JOIN tb_edit_proposal p ON p.run_id = r.id
                 WHERE r.id = ?::uuid AND r.owner_id = ?
                """, runId, owner);
        if (run == null) throw new NotFound();
        return run;
    }
    private List<Map<String,Object>> query(Connection c, String query, Object... args) throws SQLException {
        try (PreparedStatement s = statement(c, query, args); ResultSet rs = s.executeQuery()) { return rows(rs); }
    }
    private Map<String,Object> first(Connection c, String query, Object... args) throws SQLException {
        try (PreparedStatement s = statement(c, query, args); ResultSet rs = s.executeQuery()) { return rs.next() ? row(rs) : null; }
    }
    private static PreparedStatement statement(Connection c, String query, Object... args) throws SQLException {
        PreparedStatement s = c.prepareStatement(query);
        for (int i = 0; i < args.length; i++) s.setObject(i+1, args[i]);
        return s;
    }
    private List<Map<String,Object>> rows(ResultSet rs) throws SQLException {
        List<Map<String,Object>> result = new ArrayList<>();
        while (rs.next()) result.add(row(rs));
        return result;
    }
    private Map<String,Object> row(ResultSet rs) throws SQLException {
        Map<String,Object> result = new LinkedHashMap<>();
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String name = meta.getColumnLabel(i);
            String[] parts = name.split("_"); StringBuilder key = new StringBuilder(parts[0]);
            for (int j = 1; j < parts.length; j++) key.append(Character.toUpperCase(parts[j].charAt(0))).append(parts[j].substring(1));
            Object value = rs.getObject(i);
            if (value instanceof Timestamp timestamp) value = timestamp.toInstant().toString();
            else if (value instanceof UUID || value instanceof OffsetDateTime) value = value.toString();
            if (value instanceof java.sql.Array array) value = Arrays.asList((Object[]) array.getArray());
            if ((name.equals("args") || name.equals("result") || name.equals("content") || name.equals("usage")) && value != null)
                value = decode(value.toString());
            result.put(key.toString(), value);
        }
        return result;
    }
    private Object decode(String value) {
        try { return json.readTree(value); }
        catch (Exception e) { throw new IllegalStateException("Stored JSON is invalid", e); }
    }
}
