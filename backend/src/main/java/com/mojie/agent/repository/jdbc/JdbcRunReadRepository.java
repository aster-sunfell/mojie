package com.mojie.agent.repository.jdbc;

import com.mojie.agent.model.*;
import com.mojie.agent.repository.RunReadRepository;
import com.mojie.common.repository.jdbc.JdbcAccess;
import java.util.*;
import javax.sql.DataSource;

public final class JdbcRunReadRepository implements RunReadRepository {
  private final JdbcAccess db;

  public JdbcRunReadRepository(DataSource source) {
    this.db = new JdbcAccess(source);
  }

  @Override
  public RunSnapshot scoped(String runId) {
    return db.firstRecord(
        RunSnapshot.class,
"""
SELECT r.id, r.project_id, COALESCE(p.target_chapter_id,r.target_chapter_id) AS target_chapter_id,
       COALESCE(p.base_version,r.base_version) AS base_version, r.instruction,
       r.status, r.cancel_requested, r.queued_at, r.started_at, r.finished_at,
       r.analysis, r.error_code, r.error_detail, r.model_call_count,
       r.tool_call_count, r.tool_text_codepoints, r.last_event_seq,
       p.id AS proposal_id,r.conversation_id,r.task_type,r.config_snapshot,
       r.context_chapter_id,r.context_version,
       p.target_type,p.target_setting_id,COALESCE(p.base_title,c.title) AS target_title
  FROM tb_agent_run r LEFT JOIN tb_edit_proposal p ON p.run_id = r.id
  LEFT JOIN tb_chapter c ON c.id = COALESCE(p.target_chapter_id,r.target_chapter_id)
 WHERE r.id = ?::uuid
""",
        runId);
  }

  @Override
  public List<RunSource> listSources(String runId) {
    return db.records(
        RunSource.class,
        """
        SELECT i.id, i.kind, i.chapter_id, i.chapter_version, i.setting_id,
               i.line_number, i.codepoint_offset, i.content_hash, i.tool_execution_id,
               EXISTS (SELECT 1 FROM tb_agent_message m
                        JOIN tb_agent_model_call mc ON mc.run_id = m.run_id
                       AND m.message_seq = ANY(mc.input_message_seqs)
                       AND mc.status = 'SUCCEEDED'
                       WHERE m.run_id = i.run_id AND m.tool_execution_id = i.tool_execution_id)
                 AS provided_to_model
          FROM tb_agent_context_item i WHERE i.run_id = ?::uuid ORDER BY i.created_at, i.id
        """,
        runId);
  }

  @Override
  public List<RunStep> listSteps(String runId) {
    return db.records(
        RunStep.class,
        """
        SELECT s.tool_execution_id, s.tool_call_id, s.tool_name, s.args, s.result,
               s.summary, s.status, s.error_code, s.started_at, s.finished_at,
               mc.call_index AS model_call_index
          FROM tb_agent_step s JOIN tb_agent_model_call mc ON mc.id = s.model_call_id
         WHERE s.run_id = ?::uuid ORDER BY mc.call_index, s.started_at, s.tool_execution_id
        """,
        runId);
  }

  @Override
  public List<RunActivity> listActivities(String runId) {
    return db.records(
        RunActivity.class,
        """
        SELECT m.id::text AS id, mc.call_index AS model_call_index,
               string_agg(block.value->>'text', E'\\n\\n' ORDER BY block.ordinality) AS text
          FROM tb_agent_message m JOIN tb_agent_model_call mc ON mc.id = m.model_call_id
          CROSS JOIN LATERAL jsonb_array_elements(m.content) WITH ORDINALITY AS block(value, ordinality)
         WHERE m.run_id = ?::uuid AND m.role = 'ASSISTANT'
           AND mc.phase = 'TASK' AND mc.finish_reason = 'TOOL_CALLS'
           AND block.value->>'type' = 'text' AND btrim(block.value->>'text') <> ''
         GROUP BY m.id, m.message_seq, mc.call_index
         ORDER BY m.message_seq
        """,
        runId);
  }

  @Override
  public RunOutput latestOutput(String runId) {
    return db.firstRecord(RunOutput.class, """
        SELECT o.call_index AS model_call_index, o.text, o.last_event_seq, m.status, m.finish_reason
          FROM tb_agent_output o JOIN tb_agent_model_call m
            ON m.run_id=o.run_id AND m.call_index=o.call_index
         WHERE o.run_id=?::uuid AND m.phase='TASK'
           AND m.call_index=(SELECT max(call_index) FROM tb_agent_model_call
                            WHERE run_id=?::uuid AND phase='TASK')
        """, runId, runId);
  }

  @Override
  public List<TraceSource> listSourcesDetailed(String runId) {
    return db.records(
        TraceSource.class,
        "SELECT kind, chapter_id, chapter_version, setting_id, line_number, codepoint_offset,"
            + " content_hash, tool_execution_id FROM tb_agent_context_item WHERE run_id = ?::uuid"
            + " ORDER BY created_at, id",
        runId);
  }

  @Override
  public List<TraceStep> listStepsDetailed(String runId) {
    return db.records(
        TraceStep.class,
        "SELECT tool_execution_id, model_call_id, tool_call_id, tool_name, args, result, status,"
            + " error_code, started_at, finished_at FROM tb_agent_step WHERE run_id = ?::uuid ORDER"
            + " BY started_at, tool_execution_id",
        runId);
  }

  @Override
  public List<TraceMessage> listMessages(String runId) {
    return db.records(
        TraceMessage.class,
        "SELECT message_seq, role, content, model_call_id, tool_call_id, tool_execution_id,"
            + " created_at FROM tb_agent_message WHERE run_id = ?::uuid ORDER BY message_seq",
        runId);
  }

  @Override
  public List<TraceModelCall> listModelCalls(String runId) {
    return db.records(
        TraceModelCall.class,
        "SELECT id, call_index, input_message_seqs, status, finish_reason, raw_finish_reason,"
            + " provider_request_id, error_code, error_detail, usage, started_at, finished_at FROM"
            + " tb_agent_model_call WHERE run_id = ?::uuid ORDER BY call_index",
        runId);
  }

  @Override
  public ModelSelection modelSelection(String runId) {
    return db.firstRecord(
        ModelSelection.class,
        "SELECT model_id, config_snapshot FROM tb_agent_run WHERE id = ?::uuid",
        runId);
  }

  @Override
  public List<RunSummary> page(
      String projectId,
      String chapterId,
      String status,
      String afterTime,
      String afterId,
      int size) {
    return db.records(
        RunSummary.class,
"""
SELECT r.id, r.project_id, COALESCE(p.target_chapter_id,r.target_chapter_id) AS target_chapter_id,
       COALESCE(p.base_version,r.base_version) AS base_version, r.instruction,
       r.status, r.cancel_requested, r.queued_at, r.started_at, r.finished_at,
       r.analysis, r.error_code, r.error_detail, r.last_event_seq, p.id AS proposal_id,
       r.created_at,p.target_type,p.target_setting_id,COALESCE(p.base_title,c.title) AS target_title
  FROM tb_agent_run r LEFT JOIN tb_edit_proposal p ON p.run_id = r.id
  LEFT JOIN tb_chapter c ON c.id = COALESCE(p.target_chapter_id,r.target_chapter_id)
 WHERE r.project_id = ?::uuid
   AND (?::uuid IS NULL OR COALESCE(p.target_chapter_id,r.target_chapter_id,r.context_chapter_id) = ?::uuid)
   AND (?::text IS NULL OR r.status = ?)
   AND (?::timestamptz IS NULL OR (r.created_at, r.id) < (?::timestamptz, ?::uuid))
 ORDER BY r.created_at DESC, r.id DESC LIMIT ?
""",
        projectId,
        chapterId,
        chapterId,
        status,
        status,
        afterTime,
        afterTime,
        afterId,
        size);
  }

  @Override
  public RunInputRow inputs(String runId) {
    return db.firstRecord(
        RunInputRow.class,
        """
        SELECT r.id, r.project_id, r.target_chapter_id, r.base_version, r.base_content,
               r.instruction, p.content, r.model_id, r.config_snapshot, r.deadline_at
          FROM tb_agent_run r JOIN tb_agent_prompt_snapshot p ON p.hash = r.prompt_hash
         WHERE r.id = ?::uuid AND r.status = 'RUNNING'
        """,
        runId);
  }
}
