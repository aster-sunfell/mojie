package com.mojie.config;

import static com.mojie.common.model.JsonSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.mojie.agent.model.ModelApiConfig;
import com.mojie.agent.service.port.ModelGateway;
import com.mojie.agent.service.port.ModelGateway.*;
import com.mojie.common.error.BusinessException;
import com.mojie.common.pagination.PageCursor;
import com.mojie.common.repository.jdbc.JdbcAccess;
import com.mojie.conversation.model.ConversationModels.*;
import com.mojie.infrastructure.skill.*;
import com.mojie.skill.repository.jdbc.JdbcSkillRepository;
import com.mojie.skill.service.SkillService;
import com.mojie.support.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationHistoryMigrationIT {
  @TempDir Path data;

  @Test
  void archivedHistoryIsPreservedListedAndCanContinueWithCompatibleModel() throws Exception {
    try (var database = new TestDatabase()) {
      var source = database.source();
      Flyway.configure().dataSource(source).target("10").load().migrate();
      var tx = TestServices.transactions(source);
      var content = TestServices.content(source);
      var requests = Collections.synchronizedList(new ArrayList<Request>());
      ModelGateway gateway = request -> {
        requests.add(request);
        return new Response("继续讨论完成", List.of(), FinishReason.FINAL, "test", "end_turn", Map.of());
      };
      UUID process = UUID.randomUUID();
      var cursor = PageCursor.configured();
      var agent = AgentConfiguration.create(source, content, tx, cursor, gateway,
          Clock.systemUTC(), process, "legacy", "test-model", 16000,
          "test-cursor-key-123456".getBytes(), ModelApiConfig.MESSAGES, 1000000, true, true);
      try {
        var skills = new SkillService(new JdbcSkillRepository(source), new FileSkillStorage(data),
            new HttpSkillExecutor(null), tx, cursor);
        String project = (String) content.projects().createProject("迁移测试").get("id");
        String recent = UUID.randomUUID().toString();
        String archived = UUID.randomUUID().toString();
        String legacy = UUID.randomUUID().toString();
        var db = new JdbcAccess(source);
        String config = encode(Map.of(
            "maxOutputTokens", 16000, "apiPath", ModelApiConfig.MESSAGES, "maxModelCalls", 16,
            "maxToolCalls", 32, "maxToolTextCodePoints", 120000, "runTimeoutSeconds", 300,
            "queueTimeoutSeconds", 60, "baseUrl", "http://127.0.0.1", "contextWindowTokens", 1000000));
        for (String id : List.of(recent, archived, legacy)) {
          db.update("INSERT INTO tb_conversation(id,project_id,model_id,model_config)"
              + " VALUES(?::uuid,?::uuid,'test-model',?::jsonb)", id, project, config);
        }
        db.update("UPDATE tb_conversation SET created_at='2026-01-03T00:00:00Z' WHERE id=?::uuid", recent);
        db.update("UPDATE tb_conversation SET created_at='2026-01-04T00:00:00Z' WHERE id=?::uuid", legacy);
        db.update("UPDATE tb_conversation SET status='ARCHIVED', title='旧讨论', last_seq=2,"
            + " created_at='2026-01-01T00:00:00Z', updated_at='2026-01-02T00:00:00Z' WHERE id=?::uuid", archived);
        db.update("UPDATE tb_conversation SET status='ARCHIVED', model_id='old-model' WHERE id=?::uuid", legacy);
        db.update("INSERT INTO tb_conversation_message(conversation_id,seq,role,content,status)"
            + " VALUES(?::uuid,1,'USER','保留原始问题','SUCCEEDED'),"
            + " (?::uuid,2,'ASSISTANT','保留原始回答','SUCCEEDED')", archived, archived);
        String oldRun = UUID.randomUUID().toString();
        String oldSetting = UUID.randomUUID().toString();
        db.update("INSERT INTO tb_agent_prompt_snapshot(hash,kind,content) VALUES(repeat('a',64),'SYSTEM_PROMPT','旧提示'),"
            + " (repeat('b',64),'TOOL_SCHEMA','旧工具')");
        db.update("INSERT INTO tb_agent_run(id,project_id,conversation_id,instruction,client_request_id,request_digest,"
            + "status,deadline_at,finished_at,model_id,prompt_hash,tool_schema_hash,config_snapshot,task_type)"
            + " VALUES(?::uuid,?::uuid,?::uuid,'旧讨论',?::uuid,repeat('c',64),'SUCCEEDED',now(),now(),"
            + "'test-model',repeat('a',64),repeat('b',64),'{}','CHAT')", oldRun, project, archived, UUID.randomUUID().toString());
        db.update("INSERT INTO tb_setting(id,project_id,type,title,content) VALUES(?::uuid,?::uuid,'CHARACTER','林舟','迁移前的设定')",
            oldSetting, project);
        var conversationsBefore = db.query("SELECT to_jsonb(c)-'status' AS record FROM tb_conversation c ORDER BY id");
        var messagesBefore = db.query("SELECT * FROM tb_conversation_message ORDER BY id");
        agent.startup().start();
        assertEquals("ASSISTANT", db.first("SELECT task_type FROM tb_agent_run WHERE id=?::uuid", oldRun).get("taskType"));
        assertEquals("迁移前的设定", content.settings().revisions(oldSetting).getFirst().content());
        assertEquals(1, content.settings().setting(oldSetting).get("version"));
        assertThrows(com.mojie.common.error.StorageException.class,
            () -> db.update("UPDATE tb_agent_run SET task_type='CHAT' WHERE id=?::uuid", oldRun));
        var service = new ConversationConfiguration().createConversationService(
            source, tx, cursor, content, agent, skills,
            new ModelApiConfig(ModelApiConfig.MESSAGES, "http://127.0.0.1", "test", "test-model"),
            gateway, process, () -> Map.of("contextWindowTokens", 1000000));
        assertEquals(conversationsBefore, db.query("SELECT to_jsonb(c) AS record FROM tb_conversation c ORDER BY id"));
        assertEquals(messagesBefore, db.query("SELECT * FROM tb_conversation_message ORDER BY id"));
        assertEquals(0, ((Number) db.first("SELECT count(*) AS n FROM information_schema.columns"
            + " WHERE table_schema=current_schema() AND table_name='tb_conversation' AND column_name='status'").get("n")).intValue());
        assertFalse(tree(encode(service.get(archived))).has("status"));
        var ids = new ArrayList<String>();
        String next = null;
        do {
          var page = service.page(project, next, 1);
          @SuppressWarnings("unchecked")
          var items = (List<Conversation>) page.get("items");
          items.forEach(c -> ids.add(c.id()));
          next = (String) page.get("nextCursor");
        } while (next != null);
        assertEquals(List.of(legacy, recent, archived), ids);
        assertEquals("CONVERSATION_MODEL_UNAVAILABLE", assertThrows(BusinessException.class,
            () -> service.send(legacy, new Send("继续旧任务", UUID.randomUUID().toString(),
                0L, null, null, null, List.of()))).code());
        assertEquals("old-model", service.get(legacy).modelId());
        var receipt = service.send(archived, new Send("继续旧讨论", UUID.randomUUID().toString(),
            2L, null, null, null, List.of()));
        long end = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < end
            && Set.of("QUEUED", "RUNNING").contains(agent.query().detail(receipt.runId()).get("status"))) {
          Thread.sleep(20);
        }
        assertEquals("SUCCEEDED", agent.query().detail(receipt.runId()).get("status"));
        assertEquals(4, service.get(archived).lastSeq());
        assertEquals(messagesBefore, db.query("SELECT * FROM tb_conversation_message WHERE seq<=2 ORDER BY id"));
        assertTrue(requests.getFirst().messages().getLast().content().stream()
            .filter(TextPart.class::isInstance).map(TextPart.class::cast).map(TextPart::text).findFirst().orElseThrow().endsWith("用户要求:\n继续旧讨论"));
      } finally {
        agent.sweeper().close();
        agent.executor().close();
      }
    }
  }
}
