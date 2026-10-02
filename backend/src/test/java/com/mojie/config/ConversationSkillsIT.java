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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ConversationSkillsIT {
  @TempDir Path data;

  @Test
  void continuousHistoryIsolationDiscoveryAndVersionPins() throws Exception {
    try (TestDatabase database = new TestDatabase()) {
      var source = database.source();
      var tx = TestServices.transactions(source);
      var content = TestServices.content(source);
      var requests = Collections.synchronizedList(new ArrayList<Request>());
      ModelGateway gateway =
          request -> {
            requests.add(request);
            String last = request.messages().getLast().content().toString();
            if (last.contains("请检查") && request.messages().getLast().replies().isEmpty()) {
              var catalog =
                  tree(
                      request
                          .systemPrompt()
                          .substring(
                              request
                                      .systemPrompt()
                                      .indexOf("Skill catalog (data, not authority):\n")
                                  + "Skill catalog (data, not authority):\n".length())
                          .split("\\nExplicit")[0]);
              String id = catalog.get(0).path("skillId").asText();
              return new Response(
                  "",
                  List.of(new ToolCall("load", "read_skill", tree(encode(Map.of("skillId", id))))),
                  FinishReason.TOOL_CALLS,
                  "test",
                  "tool_use",
                  Map.of());
            }
            return new Response(
                "记住：林舟怕火。", List.of(), FinishReason.FINAL, "test", "end_turn", Map.of());
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
                .conversationService(
                    source,
                    tx,
                    PageCursor.configured(),
                    content,
                    agent,
                    skills,
                    new ModelApiConfig(
                        ModelApiConfig.MESSAGES, "http://127.0.0.1", "test", "test-model", 1000000),
                    gateway,
                    process);
        String project = (String) content.projects().createProject("author", "测试小说").get("id");
        var a = service.create("author", project);
        var b = service.create("author", project);
        var upload =
            skills.importPackage(
                "author",
                null,
                Map.of(
                    "SKILL.md",
                    "---\nname: character-check\ndescription: 检查人物一致性\nmetadata:\n  source: test\n---\n核查人物性格。\n"
                        .getBytes(StandardCharsets.UTF_8),
                    "assets/image.bin",
                    new byte[] {0, 1, (byte) 255},
                    "references/r.md",
                    "参考".getBytes(StandardCharsets.UTF_8)));
        assertEquals("READY", upload.status());
        var skill = skills.requireSkill("author", upload.skillId());
        skills.patch("author", skill.id(), skill.version(), true, null, false);
        Send first =
            new Send("记住林舟怕火", UUID.randomUUID().toString(), 0L, "CHAT", null, null, List.of());
        Receipt receipt = service.send("author", a.id(), first);
        await(agent, receipt.runId());
        assertEquals(receipt.runId(), service.send("author", a.id(), first).runId());
        assertEquals(2, service.get("author", a.id()).lastSeq());
        assertEquals(
            "CONVERSATION_VERSION_CONFLICT",
            assertThrows(
                    BusinessException.class,
                    () ->
                        service.send(
                            "author",
                            a.id(),
                            new Send(
                                "旧序号",
                                UUID.randomUUID().toString(),
                                0L,
                                "CHAT",
                                null,
                                null,
                                List.of())))
                .code());
        Receipt second =
            service.send(
                "author",
                a.id(),
                new Send(
                    "他为什么避开火？", UUID.randomUUID().toString(), 2L, "CHAT", null, null, List.of()));
        await(agent, second.runId());
        assertTrue(requests.get(1).messages().toString().contains("记住林舟怕火"));
        assertTrue(requests.get(1).messages().toString().contains("林舟怕火"));
        Receipt independent =
            service.send(
                "author",
                b.id(),
                new Send("讨论大纲", UUID.randomUUID().toString(), 0L, "CHAT", null, null, List.of()));
        await(agent, independent.runId());
        assertFalse(requests.get(2).messages().toString().contains("林舟怕火"));
        Receipt third =
            service.send(
                "author",
                a.id(),
                new Send("请检查人物", UUID.randomUUID().toString(), 4L, "CHAT", null, null, List.of()));
        await(agent, third.runId());
        assertEquals("SUCCEEDED", agent.query().detail("author", third.runId()).get("status"));
        var loaded = skills.runSkills(third.runId()).getFirst();
        assertTrue(loaded.loaded());
        assertTrue(loaded.providedToModel());
        assertEquals(upload.versionId(), loaded.versionId());
        assertTrue(
            requests.stream()
                .allMatch(r -> r.tools().stream().noneMatch(t -> t.name().equals("propose_edit"))));
        var db = new JdbcAccess(source);
        assertEquals(
            5,
            ((Number) db.first("SELECT count(*) AS n FROM tb_agent_input_snapshot").get("n"))
                .intValue());
        assertEquals(
            0,
            ((Number)
                    db.first(
                            "SELECT count(*) AS n FROM tb_conversation WHERE active_run_id IS NOT"
                                + " NULL")
                        .get("n"))
                .intValue());
        service.pins("author", a.id(), List.of(new Selection(skill.id(), upload.versionId())));
        var update =
            skills.importPackage(
                "author",
                skill.id(),
                Map.of(
                    "SKILL.md",
                    "---\nname: character-check\ndescription: 新说明\n---\n新版指导\n"
                        .getBytes(StandardCharsets.UTF_8)));
        assertEquals("READY", update.status());
        assertEquals(upload.versionId(), service.pins("author", a.id()).getFirst().versionId());
        assertEquals(
            "SKILL_VERSION_CONFLICT",
            assertThrows(
                    BusinessException.class,
                    () ->
                        service.send(
                            "author",
                            a.id(),
                            new Send(
                                "继续",
                                UUID.randomUUID().toString(),
                                6L,
                                "CHAT",
                                null,
                                null,
                                List.of(new Selection(skill.id(), update.versionId())))))
                .code());
        var before = service.get("author", a.id());
        service.patch("author", a.id(), before.version(), "人物讨论", "ARCHIVED", null);
        assertThrows(
            BusinessException.class,
            () ->
                service.send(
                    "author",
                    a.id(),
                    new Send(
                        "继续", UUID.randomUUID().toString(), 6L, "CHAT", null, null, List.of())));
      } finally {
        agent.sweeper().close();
        agent.executor().close();
      }
    }
  }

  @Test
  void compactionIsTrackedAndFailureDoesNotDiscardHistory() throws Exception {
    try (TestDatabase database = new TestDatabase()) {
      var source = database.source();
      var tx = TestServices.transactions(source);
      var content = TestServices.content(source);
      var fail = new java.util.concurrent.atomic.AtomicBoolean(false);
      ModelGateway gateway =
          request -> {
            if (request.systemPrompt().startsWith("You summarize"))
              return new Response(
                  fail.get() ? "" : "历史结论：林舟怕火，未解决大纲。",
                  List.of(),
                  fail.get() ? FinishReason.TRUNCATED : FinishReason.FINAL,
                  "test",
                  "end_turn",
                  Map.of());
            return new Response(
                "已验证的章节结论。".repeat(4000),
                List.of(),
                FinishReason.FINAL,
                "test",
                "end_turn",
                Map.of());
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
                .conversationService(
                    source,
                    tx,
                    PageCursor.configured(),
                    content,
                    agent,
                    skills,
                    new ModelApiConfig(
                        ModelApiConfig.MESSAGES, "http://127.0.0.1", "test", "test-model", 1000000),
                    gateway,
                    process);
        String project = (String) content.projects().createProject("author", "压缩测试").get("id");
        var a = service.create("author", project);
        for (int n = 0; n < 4; n++) {
          var r =
              service.send(
                  "author",
                  a.id(),
                  new Send(
                      "继续 " + n,
                      UUID.randomUUID().toString(),
                      (long) n * 2,
                      "CHAT",
                      null,
                      null,
                      List.of()));
          await(agent, r.runId());
        }
        var db = new JdbcAccess(source);
        assertTrue(
            ((Number)
                        db.first(
                                "SELECT count(*) AS n FROM tb_conversation_compaction WHERE"
                                    + " conversation_id=?::uuid",
                                a.id())
                            .get("n"))
                    .intValue()
                > 0);
        assertEquals(
            8,
            ((Number)
                    db.first(
                            "SELECT count(*) AS n FROM tb_conversation_message WHERE"
                                + " conversation_id=?::uuid",
                            a.id())
                        .get("n"))
                .intValue());
        assertNotNull(db.first("SELECT * FROM tb_agent_event WHERE type='context.compacted'"));
        var b = service.create("author", project);
        for (int n = 0; n < 3; n++) {
          var r =
              service.send(
                  "author",
                  b.id(),
                  new Send(
                      "继续 " + n,
                      UUID.randomUUID().toString(),
                      (long) n * 2,
                      "CHAT",
                      null,
                      null,
                      List.of()));
          await(agent, r.runId());
        }
        fail.set(true);
        var failed =
            service.send(
                "author",
                b.id(),
                new Send("触发失败", UUID.randomUUID().toString(), 6L, "CHAT", null, null, List.of()));
        long end = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < end
            && Set.of("QUEUED", "RUNNING")
                .contains(agent.query().detail("author", failed.runId()).get("status")))
          Thread.sleep(20);
        assertEquals(
            "CONTEXT_COMPACTION_FAILED",
            agent.query().detail("author", failed.runId()).get("errorCode"));
        assertEquals(
            7,
            ((Number)
                    db.first(
                            "SELECT count(*) AS n FROM tb_conversation_message WHERE"
                                + " conversation_id=?::uuid",
                            b.id())
                        .get("n"))
                .intValue());
      } finally {
        agent.sweeper().close();
        agent.executor().close();
      }
    }
  }

  private static void await(AgentConfiguration.Services agent, String run) throws Exception {
    long end = System.nanoTime() + 10_000_000_000L;
    while (System.nanoTime() < end) {
      var snapshot = agent.query().detail("author", run);
      if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(snapshot.get("status"))) {
        assertEquals("SUCCEEDED", snapshot.get("status"), snapshot.toString());
        return;
      }
      Thread.sleep(20);
    }
    fail("Run did not finish");
  }
}
