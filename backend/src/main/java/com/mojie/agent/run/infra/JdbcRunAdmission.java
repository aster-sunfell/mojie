package com.mojie.agent.run.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.agent.loop.LoopInput;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

/** Idempotent run creation. The caller must reserve a bounded executor permit before create(). */
public final class JdbcRunAdmission {
    private final DataSource dataSource;
    private final Clock clock;
    private final UUID processInstanceId;
    private final JsonMapper json = JsonMapper.builder().build();

    public JdbcRunAdmission(DataSource dataSource, Clock clock, UUID processInstanceId) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.clock = Objects.requireNonNull(clock);
        this.processInstanceId = Objects.requireNonNull(processInstanceId);
    }

    public record Request(String ownerId, String projectId, String targetChapterId, long expectedVersion,
                          String instruction, String clientRequestId) {}
    public record Config(String systemPrompt, String toolSchemaJson, String modelId,
                         Map<String, Object> publicModelConfig) {}
    public record Receipt(String runId, String status, boolean created) {}
    public static final class AdmissionException extends RuntimeException {
        private final String code;
        public AdmissionException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    /** Check before capacity acquisition so a retried successful submission needs no new permit. */
    public Optional<Receipt> findExisting(Request request) {
        Prepared prepared = prepare(request);
        try (Connection connection = dataSource.getConnection()) {
            return findExisting(connection, request.ownerId(), request.clientRequestId(), prepared.digest());
        } catch (SQLException e) { throw new IllegalStateException("Run admission database unavailable", e); }
    }

    public Receipt create(Request request, Config config) {
        Prepared prepared = prepare(request);
        Objects.requireNonNull(config);
        if (config.systemPrompt() == null || config.systemPrompt().isBlank()
                || config.toolSchemaJson() == null || config.toolSchemaJson().isBlank()
                || config.modelId() == null || config.modelId().isBlank())
            throw new AdmissionException("MODEL_ERROR", "Model configuration is incomplete");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Receipt> existing = findExisting(connection, request.ownerId(), request.clientRequestId(), prepared.digest());
                if (existing.isPresent()) { connection.commit(); return existing.get(); }
                lockProject(connection, request.ownerId(), request.projectId());
                existing = findExisting(connection, request.ownerId(), request.clientRequestId(), prepared.digest());
                if (existing.isPresent()) { connection.commit(); return existing.get(); }
                ensureSlotFree(connection, request.projectId());
                String baseContent = chapterBase(connection, request.projectId(), request.targetChapterId(), request.expectedVersion());
                String promptHash = snapshot(connection, "SYSTEM_PROMPT", config.systemPrompt());
                String schemaHash = snapshot(connection, "TOOL_SCHEMA", config.toolSchemaJson());
                Instant queuedAt = clock.instant();
                String runId = insertRun(connection, request, prepared.instruction(), prepared.digest(),
                        baseContent, promptHash, schemaHash, config, queuedAt);
                insertInitialMessage(connection, runId, request.targetChapterId(), request.expectedVersion(), prepared.instruction());
                insertQueuedEvent(connection, runId);
                occupySlot(connection, request.projectId(), runId);
                connection.commit();
                return new Receipt(runId, "QUEUED", true);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                if (e instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                    // PostgreSQL aborts the transaction on uniqueness failure; re-read in a new transaction.
                    return findExisting(request).orElseThrow(() -> new IllegalStateException("Run uniqueness conflict", sql));
                }
                if (e instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Run admission transaction failed", e);
            }
        } catch (SQLException e) { throw new IllegalStateException("Run admission database unavailable", e); }
    }

    private record Prepared(String instruction, String digest) {}
    private Prepared prepare(Request request) {
        Objects.requireNonNull(request);
        if (request.ownerId() == null || request.ownerId().isBlank()) throw validation("ownerId");
        try { UUID.fromString(request.projectId()); UUID.fromString(request.targetChapterId()); UUID.fromString(request.clientRequestId()); }
        catch (RuntimeException e) { throw validation("UUID"); }
        if (request.expectedVersion() <= 0 || request.expectedVersion() > Integer.MAX_VALUE) throw validation("expectedVersion");
        if (request.instruction() == null) throw validation("instruction");
        String instruction = request.instruction().replace("\r\n", "\n").replace('\r', '\n');
        int size = instruction.codePointCount(0, instruction.length());
        if (instruction.isBlank() || size > 2000) throw validation("instruction");
        String digest = sha256(request.projectId() + "\n" + request.targetChapterId() + "\n"
                + request.expectedVersion() + "\n" + instruction);
        return new Prepared(instruction, digest);
    }
    private static AdmissionException validation(String field) { return new AdmissionException("VALIDATION_ERROR", "Invalid " + field); }

    private static Optional<Receipt> findExisting(Connection connection, String ownerId, String clientRequestId,
                                                   String digest) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, status, request_digest FROM tb_agent_run
                 WHERE owner_id = ? AND client_request_id = ?::uuid
                """)) {
            statement.setString(1, ownerId); statement.setString(2, clientRequestId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                if (!digest.equals(rows.getString(3).trim()))
                    throw new AdmissionException("IDEMPOTENCY_CONFLICT", "Request key belongs to different content");
                return Optional.of(new Receipt(rows.getString(1), rows.getString(2), false));
            }
        }
    }
    private static void lockProject(Connection connection, String ownerId, String projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM tb_project WHERE id = ?::uuid AND owner_id = ? AND deleted_at IS NULL FOR UPDATE
                """)) {
            statement.setString(1, projectId); statement.setString(2, ownerId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new AdmissionException("RESOURCE_NOT_FOUND", "Project unavailable");
            }
        }
    }
    private static void ensureSlotFree(Connection connection, String projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT active_run_id FROM tb_project WHERE id = ?::uuid")) {
            statement.setString(1, projectId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                if (rows.getObject(1) != null) throw new AdmissionException("RESOURCE_BUSY", "Project already has an active run");
            }
        }
    }
    private static String chapterBase(Connection connection, String projectId, String chapterId, long expectedVersion) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT version, content FROM tb_chapter
                 WHERE project_id = ?::uuid AND id = ?::uuid AND deleted_at IS NULL FOR UPDATE
                """)) {
            statement.setString(1, projectId); statement.setString(2, chapterId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new AdmissionException("RESOURCE_NOT_FOUND", "Chapter unavailable");
                if (rows.getLong(1) != expectedVersion) throw new AdmissionException("VERSION_CONFLICT", "Chapter version changed");
                return rows.getString(2);
            }
        }
    }
    private static String snapshot(Connection connection, String kind, String content) throws SQLException {
        String hash = sha256(kind + "\n" + content);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tb_agent_prompt_snapshot(hash, kind, content) VALUES (?, ?, ?)
                ON CONFLICT (hash) DO NOTHING
                """)) {
            statement.setString(1, hash); statement.setString(2, kind); statement.setString(3, content);
            statement.executeUpdate();
        }
        return hash;
    }
    private String insertRun(Connection connection, Request request, String instruction, String digest,
                             String baseContent, String promptHash, String schemaHash, Config config,
                             Instant queuedAt) throws SQLException {
        String sql = """
                INSERT INTO tb_agent_run(owner_id, project_id, target_chapter_id, instruction, client_request_id,
                    request_digest, base_version, base_content, status, queued_at, deadline_at, process_instance_id,
                    model_id, prompt_hash, tool_schema_hash, config_snapshot)
                VALUES (?, ?::uuid, ?::uuid, ?, ?::uuid, ?, ?, ?, 'QUEUED', ?, ?, ?, ?, ?, ?, ?::jsonb)
                RETURNING id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, request.ownerId()); statement.setString(2, request.projectId());
            statement.setString(3, request.targetChapterId()); statement.setString(4, instruction);
            statement.setString(5, request.clientRequestId()); statement.setString(6, digest);
            statement.setLong(7, request.expectedVersion()); statement.setString(8, baseContent);
            statement.setObject(9, java.time.OffsetDateTime.ofInstant(queuedAt, java.time.ZoneOffset.UTC));
            statement.setObject(10, java.time.OffsetDateTime.ofInstant(queuedAt.plusSeconds(60), java.time.ZoneOffset.UTC));
            statement.setObject(11, processInstanceId); statement.setString(12, config.modelId());
            statement.setString(13, promptHash); statement.setString(14, schemaHash);
            statement.setString(15, encode(config.publicModelConfig()));
            try (ResultSet rows = statement.executeQuery()) { rows.next(); return rows.getString(1); }
        }
    }
    private void insertInitialMessage(Connection connection, String runId, String chapterId, long baseVersion,
                                      String instruction) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tb_agent_message(run_id, message_seq, role, content)
                VALUES (?::uuid, 1, 'USER', ?::jsonb)
                """)) {
            statement.setString(1, runId);
            statement.setString(2, encode(List.of(Map.of("type", "text", "text",
                    LoopInput.initialUserMessage(chapterId, baseVersion, instruction)))));
            statement.executeUpdate();
        }
    }
    private static void insertQueuedEvent(Connection connection, String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE tb_agent_run SET last_event_seq = 1 WHERE id = ?::uuid
                """)) { statement.setString(1, runId); statement.executeUpdate(); }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tb_agent_event(run_id, seq, type, payload)
                VALUES (?::uuid, 1, 'run.queued', '{"status":"QUEUED"}'::jsonb)
                """)) { statement.setString(1, runId); statement.executeUpdate(); }
    }
    private static void occupySlot(Connection connection, String projectId, String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE tb_project SET active_run_id = ?::uuid, updated_at = now()
                 WHERE id = ?::uuid AND active_run_id IS NULL
                """)) {
            statement.setString(1, runId); statement.setString(2, projectId);
            if (statement.executeUpdate() != 1) throw new AdmissionException("RESOURCE_BUSY", "Project already has an active run");
        }
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize run configuration", e); }
    }
}
