package com.mojie.conversation.repository.jdbc;

import com.mojie.common.repository.jdbc.JdbcAccess;
import com.mojie.conversation.model.ConversationModels.*;
import com.mojie.conversation.repository.ConversationRepository;
import java.util.*;
import javax.sql.DataSource;

public final class JdbcConversationRepository implements ConversationRepository {
  private final JdbcAccess db;

  public JdbcConversationRepository(DataSource source) {
    db = new JdbcAccess(source);
  }

  public Conversation get(String id, boolean lock) {
    return db.firstRecord(
        Conversation.class,
        "SELECT * FROM tb_conversation WHERE id=?::uuid" + (lock ? " FOR UPDATE" : ""),
        id);
  }

  public Conversation insert(String project, String model, String config) {
    return db.firstRecord(
        Conversation.class,
        "INSERT INTO tb_conversation(project_id,model_id,model_config)"
            + " VALUES(?::uuid,?,?::jsonb) RETURNING *",
        project,
        model,
        config);
  }

  public List<Conversation> page(String project, String time, String id, int size) {
    return db.records(
        Conversation.class,
        "SELECT * FROM tb_conversation WHERE project_id=?::uuid AND (?::timestamptz IS NULL OR"
            + " (created_at,id)<(?::timestamptz,?::uuid)) ORDER BY created_at DESC,id DESC LIMIT ?",
        project,
        time,
        time,
        id,
        size);
  }

  public int patch(String id, long version, String title) {
    return db.update(
        "UPDATE tb_conversation SET"
            + " title=?,version=version+1,updated_at=now() WHERE"
            + " id=?::uuid AND version=?",
        title,
        id,
        version);
  }

  public List<DisplayMessage> messages(String id, long after, int size) {
    return db.records(
        DisplayMessage.class,
        "SELECT * FROM tb_conversation_message WHERE conversation_id=?::uuid AND seq>? ORDER BY seq"
            + " LIMIT ?",
        id,
        after,
        size);
  }

  public DisplayMessage message(String conversation, String id) {
    return db.firstRecord(
        DisplayMessage.class,
        "SELECT * FROM tb_conversation_message WHERE conversation_id=?::uuid AND id=?::uuid",
        conversation,
        id);
  }

  public Existing existing(String client) {
    return db.firstRecord(
        Existing.class,
        "SELECT"
            + " r.id,r.request_digest,r.trigger_message_id,r.status,m.seq,r.conversation_id,r.retry_of"
            + " FROM tb_agent_run r LEFT JOIN tb_conversation_message m ON"
            + " m.id=r.trigger_message_id WHERE r.client_request_id=?::uuid",
        client);
  }

  public String insertMessage(String conversation, long seq, String content, String skillMentions) {
    return db.first(
            "INSERT INTO"
                + " tb_conversation_message(conversation_id,seq,role,content,status,skill_mentions)"
                + " VALUES(?::uuid,?,'USER',?,'QUEUED',?::jsonb) RETURNING id",
            conversation,
            seq,
            content,
            skillMentions)
        .get("id")
        .toString();
  }

  public String insertRun(
      String project,
      String conversation,
      String message,
      long boundary,
      String task,
      String chapter,
      Long version,
      String base,
      String contextChapter,
      Long contextVersion,
      String contextContent,
      String content,
      String key,
      String digest,
      String promptHash,
      String schemaHash,
      String model,
      String config,
      String process,
      String retry,
      boolean analysisOnly) {
    String id =
        db.first(
"""
INSERT INTO tb_agent_run(project_id,conversation_id,trigger_message_id,input_boundary,task_type,target_chapter_id,base_version,base_content,context_chapter_id,context_version,context_content,instruction,client_request_id,request_digest,prompt_hash,tool_schema_hash,model_id,config_snapshot,status,deadline_at,process_instance_id,retry_of,analysis_only)
VALUES(?::uuid,?::uuid,?::uuid,?,?,?::uuid,?,?,?::uuid,?,?,?,?::uuid,?,?,?,?,?::jsonb,'QUEUED',now()+interval '60 seconds',?::uuid,?::uuid,?) RETURNING id
""",
                project,
                conversation,
                message,
                boundary,
                task,
                chapter,
                version,
                base,
                contextChapter,
                contextVersion,
                contextContent,
                content,
                key,
                digest,
                promptHash,
                schemaHash,
                model,
                config,
                process,
                retry,
                analysisOnly)
            .get("id")
            .toString();
    db.update(
        "UPDATE tb_conversation_message SET run_id=?::uuid,status='QUEUED' WHERE id=?::uuid",
        id,
        message);
    return id;
  }

  public int occupy(String conversation, long expected, String run, boolean retry, String title) {
    return db.update(
        "UPDATE tb_conversation SET"
            + " active_run_id=?::uuid,last_seq=last_seq+?,version=version+1,title=CASE WHEN"
            + " title='新对话' THEN ? ELSE title END,updated_at=now() WHERE id=?::uuid AND last_seq=?"
            + " AND active_run_id IS NULL",
        run,
        retry ? 0 : 1,
        title,
        conversation,
        expected);
  }

  public RunContext runContext(String run) {
    return db.firstRecord(
        RunContext.class,
        "SELECT"
            + " conversation_id,project_id,task_type,input_boundary,trigger_message_id,config_snapshot,context_chapter_id,context_version,context_content,analysis_only"
            + " FROM tb_agent_run WHERE id=?::uuid",
        run);
  }

  public List<Protocol> history(String conversation, long boundary, long after) {
    return db.records(
        Protocol.class,
"""
SELECT p.id,p.run_id,p.message_seq,p.role,p.content,p.tool_call_id,p.tool_execution_id,r.status AS run_status,c.finish_reason,m.seq AS turn_seq
FROM tb_conversation_message m JOIN tb_agent_run r ON r.id=m.run_id JOIN tb_agent_message p ON p.run_id=r.id
LEFT JOIN tb_agent_model_call c ON c.id=p.model_call_id
WHERE m.conversation_id=?::uuid AND m.role='USER' AND m.seq<=? AND m.seq>? AND COALESCE(c.phase,'TASK')='TASK'
ORDER BY m.seq,p.message_seq
""",
        conversation,
        boundary,
        after);
  }

  public Summary summary(String conversation, long boundary) {
    return db.firstRecord(
        Summary.class,
        "SELECT id,covered_seq,summary FROM tb_conversation_compaction WHERE"
            + " conversation_id=?::uuid AND covered_seq<=? ORDER BY covered_seq DESC,created_at"
            + " DESC LIMIT 1",
        conversation,
        boundary);
  }

  public void saveSummary(
      String conversation,
      String run,
      long boundary,
      String content,
      String parent,
      String model,
      int call) {
    db.update(
        "INSERT INTO"
            + " tb_conversation_compaction(conversation_id,run_id,covered_seq,summary,parent_id,model_id,snapshot_id)"
            + " VALUES(?::uuid,?::uuid,?,?,?::uuid,?,(SELECT id FROM tb_agent_input_snapshot WHERE"
            + " run_id=?::uuid AND call_index=?))",
        conversation,
        run,
        boundary,
        content,
        parent,
        model,
        run,
        call);
    long seq =
        ((Number)
                db.first(
                        "UPDATE tb_agent_run SET last_event_seq=last_event_seq+1 WHERE id=?::uuid"
                            + " RETURNING last_event_seq",
                        run)
                    .get("lastEventSeq"))
            .longValue();
    db.update(
        "INSERT INTO tb_agent_event(run_id,seq,type,payload)"
            + " VALUES(?::uuid,?,'context.compacted',?::jsonb)",
        run,
        seq,
        com.mojie.common.model.JsonSupport.encode(
            Map.of("coveredSeq", boundary, "estimated", true)));
  }
}
