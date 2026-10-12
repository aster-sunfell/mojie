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
import com.mojie.conversation.service.*;
import com.mojie.infrastructure.skill.*;
import com.mojie.skill.repository.jdbc.JdbcSkillRepository;
import com.mojie.skill.service.SkillService;
import com.mojie.support.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ConversationRetryIT {
  @TempDir Path data;

  @Test
  void retryReusesInputAndNextTurnOmitsUnpairedDeclarations() throws Exception {
    try (TestDatabase database = new TestDatabase()) {
      var source = database.source();
      var tx = TestServices.transactions(source);
      var content = TestServices.content(source);
      var fail = new AtomicBoolean(true);
      List<Request> requests = Collections.synchronizedList(new ArrayList<>());
      ModelGateway gateway =
          request -> {
            requests.add(request);
            if (fail.getAndSet(false)) {
              List<ToolCall> calls = new ArrayList<>();
              for (int n = 0; n < 33; n++)
                calls.add(new ToolCall("unfinished-" + n, "unknown", tree("{}")));
              return new Response(
                  "中间计划不能冒充回答", calls, FinishReason.TOOL_CALLS, "test", "tool_use", Map.of());
            }
            return new Response(
                "恢复后的最终回答", List.of(), FinishReason.FINAL, "test", "end_turn", Map.of());
          };
      UUID process = UUID.randomUUID();
      var agent =
          AgentConfiguration.create(
              source,
              content,
              tx,
              PageCursor.configured(),
              gateway,
              Clock.systemUTC(),
              process,
              "legacy",
              "test-model",
              16000,
              "test-cursor-key-123456".getBytes(),
              ModelApiConfig.MESSAGES,
              1000000,
              true,
              true);
      agent.startup().start();
      try {
        var skills =
            new SkillService(
                new JdbcSkillRepository(source),
                new FileSkillStorage(data),
                new HttpSkillExecutor(null),
                tx,
                PageCursor.configured());
        var service =
            new ConversationConfiguration()
                .createConversationService(
                    source,
                    tx,
                    PageCursor.configured(),
                    content,
                    agent,
                    skills,
                    new ModelApiConfig(
                        ModelApiConfig.MESSAGES, "http://127.0.0.1", "test", "test-model"),
                    gateway, process, () -> Map.of("contextWindowTokens", 1000000));
        String project = (String) content.projects().createProject("失败协议测试").get("id");
        var conversation = service.create(project);
        var first =
            service.send(
                conversation.id(),
                new Send("原始输入", UUID.randomUUID().toString(), 0L, "ASSISTANT", null, null, List.of()));
        terminal(agent, first.runId());
        assertEquals("FAILED", agent.query().detail(first.runId()).get("status"));
        assertEquals(1, service.get(conversation.id()).lastSeq());
        var db = new JdbcAccess(source);
        // Old runs may retain the retired mode flag; retry uses the current unified behavior.
        db.update("UPDATE tb_agent_run SET analysis_only=true WHERE id=?::uuid", first.runId());
        String key = UUID.randomUUID().toString();
        var retry = service.retry(conversation.id(), first.messageId(), key);
        terminal(agent, retry.runId());
        assertNotEquals(first.runId(), retry.runId());
        assertEquals(first.messageId(), retry.messageId());
        assertEquals(
            retry.runId(),
            service.retry(conversation.id(), first.messageId(), key).runId());
        assertTrue(
            requests.getLast().tools().stream().anyMatch(t -> t.name().equals("propose_chapter_edit")));
        assertTrue(
            requests.getLast().tools().stream().anyMatch(t -> t.name().equals("propose_setting_edit")));
        assertFalse(agent.query().detail(retry.runId()).containsKey("analysisOnly"));
        assertEquals(
            1,
            ((Number)
                    db.first(
                            "SELECT count(*) AS n FROM tb_conversation_message WHERE"
                                + " conversation_id=?::uuid AND role='USER'",
                            conversation.id())
                        .get("n"))
                .intValue());
        assertEquals(2, service.get(conversation.id()).lastSeq());
        assertEquals(
            33,
            ((Number)
                    db.first(
                            "SELECT count(*) AS n FROM tb_agent_step WHERE run_id=?::uuid AND"
                                + " status='ABORTED'",
                            first.runId())
                        .get("n"))
                .intValue());
        var another = service.create(project);
        fail.set(true);
        var failed =
            service.send(
                another.id(),
                new Send(
                    "保留失败输入", UUID.randomUUID().toString(), 0L, "ASSISTANT", null, null, List.of()));
        terminal(agent, failed.runId());
        var next =
            service.send(
                another.id(),
                new Send("继续讨论", UUID.randomUUID().toString(), 1L, "ASSISTANT", null, null, List.of()));
        terminal(agent, next.runId());
        Request last = requests.getLast();
        assertTrue(
            last.messages().stream()
                .flatMap(m -> m.content().stream())
                .noneMatch(p -> p instanceof ToolCall || p instanceof ToolReply));
        assertTrue(last.messages().toString().contains("保留失败输入"));
        assertTrue(last.messages().toString().contains("ended FAILED"));
        assertFalse(last.messages().toString().contains("中间计划"));
        assertEquals(
            "CONVERSATION_VERSION_CONFLICT",
            assertThrows(
                    BusinessException.class,
                    () ->
                        service.retry(
                            another.id(),
                            failed.messageId(),
                            UUID.randomUUID().toString()))
                .code());
      } finally {
        agent.sweeper().close();
        agent.executor().close();
      }
    }
  }

  private void terminal(AgentConfiguration.Services agent, String run) throws Exception {
    long end = System.nanoTime() + 10000000000L;
    while (System.nanoTime() < end) {
      if (Set.of("SUCCEEDED", "FAILED", "CANCELLED")
          .contains(agent.query().detail(run).get("status"))) return;
      Thread.sleep(20);
    }
    fail("Run did not terminate");
  }
}
