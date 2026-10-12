package com.mojie.agent.repository.jdbc;

import com.mojie.agent.repository.RunTraceRepository;
import com.mojie.common.repository.jdbc.JdbcAccess;
import java.util.*;
import javax.sql.DataSource;

public final class JdbcRunTraceRepository implements RunTraceRepository {
  private final JdbcAccess db;

  public JdbcRunTraceRepository(DataSource source) {
    this.db = new JdbcAccess(source);
  }

  @Override
  public void snapshot(
      String run, int call, String request, List<String> history, List<Long> inputs, String phase) {
    var current =
        db.query(
            "SELECT id FROM tb_agent_message WHERE run_id=?::uuid AND message_seq=ANY(?) ORDER BY"
                + " message_seq",
            run,
            inputs.toArray(Long[]::new));
    List<String> refs = new ArrayList<>(history);
    current.forEach(row -> refs.add(row.get("id").toString()));
    for (String id : history) {
      if (db.first(
              "SELECT m.id FROM tb_agent_message m JOIN tb_agent_run source ON source.id=m.run_id"
                  + " JOIN tb_agent_run target ON target.id=?::uuid JOIN tb_conversation_message"
                  + " turn ON turn.id=source.trigger_message_id WHERE m.id=?::uuid AND"
                  + " source.conversation_id=target.conversation_id AND"
                  + " turn.seq<=target.input_boundary",
              run,
              id)
          == null) throw new IllegalStateException("Invalid historical input reference");
    }
    db.update(
        "INSERT INTO tb_agent_input_snapshot(run_id,call_index,hash,request,source_message_ids)"
            + " VALUES(?::uuid,?,?,?::jsonb,?::jsonb)",
        run,
        call,
        com.mojie.common.model.JsonSupport.hash(request),
        request,
        com.mojie.common.model.JsonSupport.encode(refs));
    db.update(
        "UPDATE tb_agent_model_call SET phase=? WHERE run_id=?::uuid AND call_index=?",
        phase,
        run,
        call);
    if ("TASK".equals(phase))
      db.update(
          "UPDATE tb_run_skill SET provided_to_model=true WHERE run_id=?::uuid AND loaded AND"
              + " (loaded_message_seq IS NULL OR loaded_message_seq=ANY(?))",
          run,
          inputs.toArray(Long[]::new));
  }

  @Override
  public long countInputMessages(String runId, List<Long> sequences) {
    return ((Number)
            db.first(
                    "SELECT count(*) AS count FROM tb_agent_message WHERE run_id = ?::uuid AND"
                        + " message_seq = ANY(?)",
                    runId,
                    sequences.toArray(Long[]::new))
                .get("count"))
        .longValue();
  }

  @Override
  public Integer reserveModelCall(String runId, int max) {
    Map<String, Object> r =
        db.first(
            "UPDATE tb_agent_run SET model_call_count = model_call_count + 1, updated_at = now()"
                + " WHERE id = ?::uuid AND status = 'RUNNING' AND cancel_requested = false AND"
                + " model_call_count < COALESCE((config_snapshot->>'maxModelCalls')::int,?)"
                + " RETURNING model_call_count",
            runId,
            max);
    return r == null ? null : ((Number) r.get("modelCallCount")).intValue();
  }

  @Override
  public int insertModelCall(String runId, int callIndex, List<Long> inputs) {
    return db.update(
        "INSERT INTO tb_agent_model_call(run_id, call_index, input_message_seqs, status) VALUES"
            + " (?::uuid, ?, ?, 'STARTED')",
        runId,
        callIndex,
        inputs.toArray(Long[]::new));
  }

  @Override
  public UUID modelCallId(String runId, int callIndex) {
    Map<String, Object> r =
        db.first(
            "SELECT id FROM tb_agent_model_call WHERE run_id = ?::uuid AND call_index = ?",
            runId,
            callIndex);
    return r == null ? null : UUID.fromString(r.get("id").toString());
  }

  @Override
  public int completeModelCall(
      UUID id, String finishReason, String rawReason, String requestId, String usage) {
    return db.update(
        "UPDATE tb_agent_model_call SET status = 'SUCCEEDED', finish_reason = ?, raw_finish_reason"
            + " = ?, provider_request_id = ?, usage = ?::jsonb, finished_at = now() WHERE id = ?"
            + " AND status = 'STARTED'",
        finishReason,
        rawReason,
        requestId,
        usage,
        id);
  }

  @Override
  public int failModelCall(UUID id, String code, String detail) {
    return db.update(
        "UPDATE tb_agent_model_call SET status = 'FAILED', error_code = ?, error_detail = ?,"
            + " finished_at = now() WHERE id = ? AND status = 'STARTED'",
        code,
        detail,
        id);
  }

  @Override
  public long nextMessageSequence(String runId) {
    return ((Number)
            db.first(
                    "SELECT COALESCE(MAX(message_seq), 0) + 1 AS seq FROM tb_agent_message WHERE"
                        + " run_id = ?::uuid",
                    runId)
                .get("seq"))
        .longValue();
  }

  @Override
  public int insertMessage(
      String runId,
      long seq,
      String role,
      String body,
      UUID callId,
      String toolCallId,
      UUID executionId) {
    int changed =
        db.update(
            "INSERT INTO tb_agent_message(run_id, message_seq, role, content, model_call_id,"
                + " tool_call_id, tool_execution_id) VALUES (?::uuid, ?, ?, ?::jsonb, ?, ?, ?)",
            runId,
            seq,
            role,
            body,
            callId,
            toolCallId,
            executionId);
    db.update(
        "UPDATE tb_agent_message m SET conversation_id=r.conversation_id FROM tb_agent_run r WHERE"
            + " m.run_id=r.id AND m.run_id=?::uuid AND m.message_seq=?",
        runId,
        seq);
    if ("TOOL".equals(role)) {
      var data = com.mojie.common.model.JsonSupport.tree(body);
      var step =
          db.first("SELECT tool_name FROM tb_agent_step WHERE tool_execution_id=?", executionId);
      if (step != null
          && "read_skill".equals(step.get("toolName"))
          && data.path("success").asBoolean()
          && !data.path("data").path("alreadyLoaded").asBoolean()) {
        String skill = data.path("data").path("skillId").asText();
        db.update(
            "UPDATE tb_run_skill SET"
                + " loaded=true,loaded_message_seq=?,guidance=?::jsonb,guidance_hash=? WHERE"
                + " run_id=?::uuid AND skill_id=?::uuid",
            seq,
            com.mojie.common.model.JsonSupport.encode(data.path("data")),
            com.mojie.common.model.JsonSupport.hash(
                com.mojie.common.model.JsonSupport.encode(data.path("data"))),
            runId,
            skill);
        long event =
            ((Number)
                    db.first(
                            "UPDATE tb_agent_run SET last_event_seq=last_event_seq+1 WHERE"
                                + " id=?::uuid RETURNING last_event_seq",
                            runId)
                        .get("lastEventSeq"))
                .longValue();
        db.update(
            "INSERT INTO tb_agent_event(run_id,seq,type,payload)"
                + " VALUES(?::uuid,?,'skill.loaded',?::jsonb)",
            runId,
            event,
            com.mojie.common.model.JsonSupport.encode(
                java.util.Map.of(
                    "skillId",
                    skill,
                    "versionId",
                    data.path("data").path("versionId").asText(),
                    "providedToModel",
                    false)));
      }
    }
    return changed;
  }

  @Override
  public Integer reserveToolCall(String runId, int max) {
    Map<String, Object> r =
        db.first(
            "UPDATE tb_agent_run SET tool_call_count = tool_call_count + 1, updated_at = now()"
                + " WHERE id = ?::uuid AND status = 'RUNNING' AND cancel_requested = false AND"
                + " tool_call_count < COALESCE((config_snapshot->>'maxToolCalls')::int,?) RETURNING"
                + " tool_call_count",
            runId,
            max);
    return r == null ? null : ((Number) r.get("toolCallCount")).intValue();
  }

  @Override
  public int insertStep(
      UUID executionId,
      String runId,
      UUID callId,
      String toolCallId,
      String toolName,
      String arguments) {
    return db.update(
        "INSERT INTO tb_agent_step(tool_execution_id, run_id, model_call_id, tool_call_id,"
            + " tool_name, args, status) VALUES (?, ?::uuid, ?, ?, ?, ?::jsonb, 'STARTED')",
        executionId,
        runId,
        callId,
        toolCallId,
        toolName,
        arguments);
  }

  @Override
  public int completeStep(
      String runId,
      String executionId,
      UUID callId,
      String toolCallId,
      String status,
      String result,
      String code) {
    int changed =
        db.update(
            "UPDATE tb_agent_step SET status = ?, result = ?::jsonb, error_code = ?, finished_at ="
                + " now() WHERE run_id = ?::uuid AND tool_execution_id = ?::uuid AND model_call_id"
                + " = ? AND tool_call_id = ? AND status = 'STARTED'",
            status,
            result,
            code,
            runId,
            executionId,
            callId,
            toolCallId);
    return changed;
  }

  @Override
  public int updateToolText(String runId, int expected, int cumulative) {
    return db.update(
        "UPDATE tb_agent_run SET tool_text_codepoints = ?, updated_at = now() WHERE id = ?::uuid"
            + " AND tool_text_codepoints = ? AND status = 'RUNNING' AND cancel_requested = false",
        cumulative,
        runId,
        expected);
  }

  @Override
  public int insertAbortedStep(
      String runId,
      UUID callId,
      String toolCallId,
      String toolName,
      String arguments,
      String reason) {
    return db.update(
        "INSERT INTO tb_agent_step(run_id, model_call_id, tool_call_id, tool_name, args, status,"
            + " error_code, finished_at) VALUES (?::uuid, ?, ?, ?, ?::jsonb, 'ABORTED', ?, now())",
        runId,
        callId,
        toolCallId,
        toolName,
        arguments,
        reason);
  }

  @Override
  public int insertEvidence(
      String runId,
      String executionId,
      String kind,
      String chapterId,
      Integer chapterVersion,
      String settingId,
      Integer line,
      Integer offset,
      String hash) {
    return db.update(
        "INSERT INTO tb_agent_context_item(run_id, tool_execution_id, kind, chapter_id,"
            + " chapter_version, setting_id, line_number, codepoint_offset, content_hash) VALUES"
            + " (?::uuid, ?::uuid, ?, ?::uuid, ?, ?::uuid, ?, ?, ?)",
        runId,
        executionId,
        kind,
        chapterId,
        chapterVersion,
        settingId,
        line,
        offset,
        hash);
  }

  @Override
  public int abortOpenCalls(String runId, String status) {
    return db.update(
        "UPDATE tb_agent_model_call SET status = 'ABORTED', error_code = ?, finished_at = now()"
            + " WHERE run_id = ?::uuid AND status = 'STARTED'",
        status,
        runId);
  }

  @Override
  public int abortOpenSteps(String runId, String status) {
    return db.update(
        "UPDATE tb_agent_step SET status = 'ABORTED', error_code = ?, finished_at = now() WHERE"
            + " run_id = ?::uuid AND status = 'STARTED'",
        status,
        runId);
  }

  @Override
  public boolean hasFinalResponse(String runId, int callIndex) {
    return (Boolean)
        db.first(
                "SELECT EXISTS (SELECT 1 FROM tb_agent_model_call mc JOIN tb_agent_message m ON"
                    + " m.run_id = mc.run_id AND m.model_call_id = mc.id AND m.role = 'ASSISTANT'"
                    + " WHERE mc.run_id = ?::uuid AND mc.call_index = ? AND mc.status = 'SUCCEEDED'"
                    + " AND mc.finish_reason = 'FINAL') AS valid",
                runId,
                callIndex)
            .get("valid");
  }

  @Override
  public boolean hasTargetInput(String runId, int callIndex, String chapterId, int version) {
    return (Boolean)
        db.first(
                "SELECT EXISTS (SELECT 1 FROM tb_agent_context_item i JOIN tb_agent_message m ON"
                    + " m.run_id = i.run_id AND m.tool_execution_id = i.tool_execution_id AND"
                    + " m.role = 'TOOL' JOIN tb_agent_model_call mc ON mc.run_id = i.run_id AND"
                    + " mc.call_index = ? WHERE i.run_id = ?::uuid AND i.kind = 'FULL_CHAPTER' AND"
                    + " i.chapter_id = ?::uuid AND i.chapter_version = ? AND m.message_seq ="
                    + " ANY(mc.input_message_seqs)) AS valid",
                callIndex,
                runId,
                chapterId,
                version)
            .get("valid");
  }

  @Override
  public boolean hasCandidateStep(String runId) {
    return (Boolean)
        db.first(
                "SELECT EXISTS (SELECT 1 FROM tb_agent_step WHERE run_id = ?::uuid AND tool_name IN"
                    + " ('propose_chapter_edit', 'propose_setting_edit') AND status = 'SUCCEEDED')"
                    + " AS valid",
                runId)
            .get("valid");
  }

  @Override
  public boolean hasSettingInput(String runId, int callIndex, String settingId, int version) {
    return (Boolean)
        db.first(
                """
                SELECT EXISTS (
                  SELECT 1 FROM tb_agent_step s
                  JOIN tb_agent_message m ON m.run_id = s.run_id AND m.tool_execution_id = s.tool_execution_id AND m.role = 'TOOL'
                  JOIN tb_agent_model_call mc ON mc.run_id = s.run_id AND mc.call_index = ?
                  WHERE s.run_id = ?::uuid AND s.tool_name = 'read_settings' AND s.status = 'SUCCEEDED'
                    AND s.result->'data'->'settings' @> jsonb_build_array(jsonb_build_object('settingId', ?::text, 'version', ?::int))
                    AND m.message_seq = ANY(mc.input_message_seqs)
                ) AS valid
                """,
                callIndex,
                runId,
                settingId,
                version)
            .get("valid");
  }

  @Override
  public long openCalls(String runId) {
    return ((Number)
            db.first(
                    "SELECT count(*) AS count FROM tb_agent_model_call WHERE run_id = ?::uuid AND"
                        + " status = 'STARTED'",
                    runId)
                .get("count"))
        .longValue();
  }

  @Override
  public long openSteps(String runId) {
    return ((Number)
            db.first(
                    "SELECT count(*) AS count FROM tb_agent_step WHERE run_id = ?::uuid AND status"
                        + " = 'STARTED'",
                    runId)
                .get("count"))
        .longValue();
  }
}
