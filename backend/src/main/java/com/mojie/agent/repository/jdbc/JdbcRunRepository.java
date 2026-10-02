package com.mojie.agent.repository.jdbc;

import com.mojie.agent.model.*;
import com.mojie.agent.model.RunAdmission.*;
import com.mojie.agent.repository.RunRepository;
import com.mojie.common.repository.jdbc.JdbcAccess;
import java.util.*;
import javax.sql.DataSource;

public final class JdbcRunRepository implements RunRepository {
  private final JdbcAccess db;

  public JdbcRunRepository(DataSource source) {
    this.db = new JdbcAccess(source);
  }

  @Override
  public Existing findExisting(String owner, String key) {
    Map<String, Object> r =
        db.first(
            "SELECT id, status, request_digest FROM tb_agent_run WHERE owner_id = ? AND"
                + " client_request_id = ?::uuid",
            owner,
            key);
    return r == null
        ? null
        : new Existing(
            r.get("id").toString(),
            (String) r.get("status"),
            r.get("requestDigest").toString().trim());
  }

  @Override
  public boolean lockProject(String projectId) {
    return db.first("SELECT id FROM tb_project WHERE id = ?::uuid FOR UPDATE", projectId) != null;
  }

  @Override
  public String activeSlot(String projectId) {
    Map<String, Object> p =
        db.first("SELECT active_run_id FROM tb_project WHERE id = ?::uuid", projectId);
    return p == null || p.get("activeRunId") == null ? null : p.get("activeRunId").toString();
  }

  @Override
  public int occupySlot(String projectId, String runId) {
    return db.update(
        "UPDATE tb_project SET active_run_id = ?::uuid, updated_at = now() WHERE id = ?::uuid AND"
            + " active_run_id IS NULL",
        runId,
        projectId);
  }

  @Override
  public int releaseSlot(String projectId, String runId) {
    return db.update(
        "UPDATE tb_project SET active_run_id = NULL, updated_at = now() WHERE id = ?::uuid AND"
            + " active_run_id = ?::uuid",
        projectId,
        runId);
  }

  @Override
  public RunRecord lockRun(String projectId, String runId) {
    if (projectId != null)
      db.first(
          "SELECT c.id FROM tb_conversation c JOIN tb_agent_run r ON r.conversation_id=c.id WHERE"
              + " r.id=?::uuid FOR UPDATE OF c",
          runId);
    Map<String, Object> r =
        db.first(
            "SELECT status, cancel_requested, deadline_at, target_chapter_id, base_version,"
                + " base_content, model_call_count, tool_call_count, tool_text_codepoints FROM"
                + " tb_agent_run WHERE id = ?::uuid AND (?::uuid IS NULL OR project_id = ?::uuid)"
                + " FOR UPDATE",
            runId,
            projectId,
            projectId);
    return r == null
        ? null
        : new RunRecord(
            (String) r.get("status"),
            (Boolean) r.get("cancelRequested"),
            java.time.OffsetDateTime.parse(r.get("deadlineAt").toString()).toInstant(),
            r.get("targetChapterId") == null ? null : r.get("targetChapterId").toString(),
            r.get("baseVersion") == null ? 0 : ((Number) r.get("baseVersion")).intValue(),
            (String) r.get("baseContent"),
            ((Number) r.get("modelCallCount")).intValue(),
            ((Number) r.get("toolCallCount")).intValue(),
            ((Number) r.get("toolTextCodepoints")).intValue());
  }

  @Override
  public int insertSnapshot(String hash, String kind, String content) {
    return db.update(
        "INSERT INTO tb_agent_prompt_snapshot(hash, kind, content) VALUES (?, ?, ?) ON CONFLICT"
            + " (hash) DO NOTHING",
        hash,
        kind,
        content);
  }

  @Override
  public String insertRun(
      Request request,
      String instruction,
      String digest,
      String baseContent,
      String promptHash,
      String schemaHash,
      String modelId,
      String configJson,
      java.time.Instant queuedAt,
      UUID processId) {
    return db.first(
            """
INSERT INTO tb_agent_run(owner_id, project_id, target_chapter_id, instruction, client_request_id,
    request_digest, base_version, base_content, status, queued_at, deadline_at, process_instance_id,
    model_id, prompt_hash, tool_schema_hash, config_snapshot)
VALUES (?, ?::uuid, ?::uuid, ?, ?::uuid, ?, ?, ?, 'QUEUED', ?, ?, ?, ?, ?, ?, ?::jsonb) RETURNING id
""",
            request.ownerId(),
            request.projectId(),
            request.targetChapterId(),
            instruction,
            request.clientRequestId(),
            digest,
            request.expectedVersion(),
            baseContent,
            java.time.OffsetDateTime.ofInstant(queuedAt, java.time.ZoneOffset.UTC),
            java.time.OffsetDateTime.ofInstant(queuedAt.plusSeconds(60), java.time.ZoneOffset.UTC),
            processId,
            modelId,
            promptHash,
            schemaHash,
            configJson)
        .get("id")
        .toString();
  }

  @Override
  public int claimQueued(String projectId, String runId, UUID processId) {
    return db.update(
        """
UPDATE tb_agent_run SET status = 'RUNNING', started_at = clock_timestamp(),
       deadline_at = clock_timestamp() + make_interval(secs => COALESCE((config_snapshot->>'runTimeoutSeconds')::int,180)), updated_at = now()
 WHERE id = ?::uuid AND project_id = ?::uuid AND status = 'QUEUED'
   AND cancel_requested = false AND process_instance_id = ? AND deadline_at > clock_timestamp()
""",
        runId,
        projectId,
        processId);
  }

  @Override
  public int cancelQueued(String runId) {
    return db.update(
        "UPDATE tb_agent_run SET status = 'CANCELLED', cancel_requested = true, finished_at ="
            + " now(), updated_at = now() WHERE id = ?::uuid AND status = 'QUEUED'",
        runId);
  }

  @Override
  public int requestCancel(String runId) {
    return db.update(
        "UPDATE tb_agent_run SET cancel_requested = true, updated_at = now() WHERE id = ?::uuid",
        runId);
  }

  @Override
  public List<ActiveRun> overdue() {
    return db
        .query(
            "SELECT project_id, id FROM tb_agent_run WHERE status IN ('QUEUED', 'RUNNING') AND"
                + " deadline_at <= clock_timestamp() ORDER BY deadline_at, id")
        .stream()
        .map(r -> new ActiveRun(r.get("projectId").toString(), r.get("id").toString()))
        .toList();
  }

  @Override
  public List<ActiveRun> previousProcess(UUID processId) {
    return db
        .query(
            "SELECT project_id, id FROM tb_agent_run WHERE status IN ('QUEUED', 'RUNNING') AND"
                + " (process_instance_id IS NULL OR process_instance_id <> ?) ORDER BY project_id,"
                + " id",
            processId)
        .stream()
        .map(r -> new ActiveRun(r.get("projectId").toString(), r.get("id").toString()))
        .toList();
  }

  @Override
  public int updateTerminal(
      String runId, String status, String analysis, String code, String detail) {
    return db.update(
        "UPDATE tb_agent_run SET status = ?, analysis = ?, error_code = ?, error_detail = ?,"
            + " finished_at = now(), updated_at = now() WHERE id = ?::uuid AND status IN ('QUEUED',"
            + " 'RUNNING')",
        status,
        analysis,
        code,
        detail,
        runId);
  }

  @Override
  public TerminalResult existingTerminal(String runId, String status) {
    Map<String, Object> r =
        db.first(
            "SELECT r.error_code, p.id AS proposal_id FROM tb_agent_run r LEFT JOIN"
                + " tb_edit_proposal p ON p.run_id = r.id WHERE r.id = ?::uuid",
            runId);
    return new TerminalResult(
        status,
        r.get("proposalId") == null ? null : r.get("proposalId").toString(),
        (String) r.get("errorCode"));
  }
}
