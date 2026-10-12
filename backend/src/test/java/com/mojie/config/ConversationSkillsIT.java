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
  void continuousHistoryIsolationDiscoveryAndExplicitMentions() throws Exception {
    try (TestDatabase database = new TestDatabase()) {
      var source = database.source();
      var tx = TestServices.transactions(source);
      var content = TestServices.content(source);
      var requests = Collections.synchronizedList(new ArrayList<Request>());
      var skipGuidance = new java.util.concurrent.atomic.AtomicBoolean(false);
      ModelGateway gateway =
          request -> {
            requests.add(request);
            String last = request.messages().getLast().content().toString();
            if ((last.contains("请检查") || last.contains("$character-"))
                && !skipGuidance.get()
                && request.messages().getLast().replies().isEmpty()) {
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
        var metadata =
            new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>(
                Map.of("contextWindowTokens", 1000000, "contextWindowSource", "first"));
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
                    gateway,
                    process,
                    () -> {
                      if (metadata.get() == null)
                        throw new BusinessException(
                            "MODEL_METADATA_UNAVAILABLE", "Metadata unavailable");
                      return metadata.get();
                    });
        String project = (String) content.projects().createProject("测试小说").get("id");
        var a = service.create(project);
        var b = service.create(project);
        var upload =
            skills.importPackage(
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
        var skill = skills.requireSkill(upload.skillId());
        skills.patch(skill.id(), skill.version(), true, null, false);
        Send first =
            new Send(
                "记住林舟怕火", UUID.randomUUID().toString(), 0L, "ASSISTANT", null, null, List.of());
        Receipt receipt = service.send(a.id(), first);
        await(agent, receipt.runId());
        metadata.set(null);
        assertEquals(receipt.runId(), service.send(a.id(), first).runId());
        assertEquals(
            "MODEL_METADATA_UNAVAILABLE",
            assertThrows(
                    BusinessException.class,
                    () ->
                        service.send(
                            a.id(),
                            new Send(
                                "继续",
                                UUID.randomUUID().toString(),
                                2L,
                                "ASSISTANT",
                                null,
                                null,
                                List.of())))
                .code());
        metadata.set(Map.of("contextWindowTokens", 900000, "contextWindowSource", "refreshed"));
        assertEquals(2, service.get(a.id()).lastSeq());
        assertEquals(
            "CONVERSATION_VERSION_CONFLICT",
            assertThrows(
                    BusinessException.class,
                    () ->
                        service.send(
                            a.id(),
                            new Send(
                                "旧序号",
                                UUID.randomUUID().toString(),
                                0L,
                                "ASSISTANT",
                                null,
                                null,
                                List.of())))
                .code());
        Receipt second =
            service.send(
                a.id(),
                new Send(
                    "他为什么避开火？",
                    UUID.randomUUID().toString(),
                    2L,
                    "ASSISTANT",
                    null,
                    null,
                    List.of()));
        await(agent, second.runId());
        var snapshotDb = new JdbcAccess(source);
        assertEquals(
            1000000,
            ((Number)
                    snapshotDb
                        .first(
                            "SELECT (config_snapshot->>'contextWindowTokens')::int AS tokens FROM"
                                + " tb_agent_run WHERE id=?::uuid",
                            receipt.runId())
                        .get("tokens"))
                .intValue());
        assertEquals(
            900000,
            ((Number)
                    snapshotDb
                        .first(
                            "SELECT (config_snapshot->>'contextWindowTokens')::int AS tokens FROM"
                                + " tb_agent_run WHERE id=?::uuid",
                            second.runId())
                        .get("tokens"))
                .intValue());
        assertTrue(requests.get(1).messages().toString().contains("记住林舟怕火"));
        assertTrue(requests.get(1).messages().toString().contains("林舟怕火"));
        Receipt independent =
            service.send(
                b.id(),
                new Send(
                    "讨论大纲", UUID.randomUUID().toString(), 0L, "ASSISTANT", null, null, List.of()));
        await(agent, independent.runId());
        assertFalse(requests.get(2).messages().toString().contains("林舟怕火"));
        Receipt third =
            service.send(
                a.id(),
                new Send(
                    "请检查人物", UUID.randomUUID().toString(), 4L, "ASSISTANT", null, null, List.of()));
        await(agent, third.runId());
        assertEquals("SUCCEEDED", agent.query().detail(third.runId()).get("status"));
        var loaded = skills.runSkills(third.runId()).getFirst();
        assertTrue(loaded.loaded());
        assertTrue(loaded.providedToModel());
        assertEquals(upload.versionId(), loaded.versionId());
        assertTrue(
            requests.stream()
                .allMatch(
                    r ->
                        r.tools().stream().anyMatch(t -> t.name().equals("propose_chapter_edit"))));
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
        var update =
            skills.importPackage(
                skill.id(),
                Map.of(
                    "SKILL.md",
                    "---\nname: character-check\ndescription: 新说明\n---\n新版指导\n"
                        .getBytes(StandardCharsets.UTF_8)));
        assertEquals("READY", update.status());
        int explicitIndex = requests.size();
        Receipt mentioned =
            service.send(
                a.id(),
                new Send(
                    "使用 $character-check 检查",
                    UUID.randomUUID().toString(),
                    6L,
                    "ASSISTANT",
                    null,
                    null,
                    List.of(new SkillMention(skill.id(), "character-check"))));
        await(agent, mentioned.runId());
        assertEquals("SUCCEEDED", agent.query().detail(mentioned.runId()).get("status"));
        var explicit = skills.runSkills(mentioned.runId()).getFirst();
        assertEquals("EXPLICIT", explicit.source());
        assertEquals(update.versionId(), explicit.versionId());
        assertTrue(explicit.loaded());
        assertTrue(explicit.providedToModel());
        assertFalse(requests.get(explicitIndex).systemPrompt().contains("新版指导"));
        assertTrue(requests.get(explicitIndex + 1).messages().toString().contains("新版指导"));
        assertEquals(
            skill.id(),
            service.messages(a.id(), null, 50).get("items") instanceof List<?> rows
                ? ((DisplayMessage) rows.get(rows.size() - 2))
                    .skillMentions()
                    .get(0)
                    .path("skillId")
                    .asText()
                : "");
        var before = service.get(a.id());
        service.patch(a.id(), before.version(), "人物讨论");
        assertEquals("人物讨论", service.get(a.id()).title());
        assertEquals(
            "CONVERSATION_VERSION_CONFLICT",
            assertThrows(
                    BusinessException.class, () -> service.patch(a.id(), before.version(), "过期标题"))
                .code());
        Receipt continued =
            service.send(
                a.id(),
                new Send(
                    "继续", UUID.randomUUID().toString(), 8L, "ASSISTANT", null, null, List.of()));
        await(agent, continued.runId());
        assertEquals(10, service.get(a.id()).lastSeq());
        var discoverable = skills.runSkills(continued.runId()).getFirst();
        assertEquals("DISCOVERABLE", discoverable.source());
        assertFalse(discoverable.loaded());
        assertEquals(update.versionId(), discoverable.versionId());
        assertEquals(
            "SKILL_UNAVAILABLE",
            assertThrows(
                    BusinessException.class,
                    () ->
                        service.send(
                            a.id(),
                            new Send(
                                "使用 $unknown-skill",
                                UUID.randomUUID().toString(),
                                10L,
                                "ASSISTANT",
                                null,
                                null,
                                List.of())))
                .code());
        var renamed =
            skills.importPackage(
                skill.id(),
                Map.of(
                    "SKILL.md",
                    "---\nname: character-renamed\ndescription: 改名说明\n---\n改名时的指导\n"
                        .getBytes(StandardCharsets.UTF_8)));
        skipGuidance.set(true);
        var skipped =
            service.send(
                a.id(),
                new Send(
                    "使用 $character-check 检查",
                    UUID.randomUUID().toString(),
                    10L,
                    "ASSISTANT",
                    null,
                    null,
                    List.of(new SkillMention(skill.id(), "character-check"))));
        awaitTerminal(agent, skipped.runId());
        assertEquals("FAILED", agent.query().detail(skipped.runId()).get("status"));
        assertEquals(
            "SKILL_GUIDANCE_REQUIRED", agent.query().detail(skipped.runId()).get("errorCode"));
        assertFalse(skills.runSkills(skipped.runId()).getFirst().loaded());
        var newer =
            skills.importPackage(
                skill.id(),
                Map.of(
                    "SKILL.md",
                    "---\nname: character-renamed\ndescription: 更新说明\n---\n后续新版指导\n"
                        .getBytes(StandardCharsets.UTF_8)));
        skipGuidance.set(false);
        var retried = service.retry(a.id(), skipped.messageId(), UUID.randomUUID().toString());
        await(agent, retried.runId());
        assertEquals("SUCCEEDED", agent.query().detail(retried.runId()).get("status"));
        assertEquals(renamed.versionId(), skills.runSkills(retried.runId()).getFirst().versionId());
        assertTrue(skills.runSkills(retried.runId()).getFirst().providedToModel());
        assertEquals(12, service.get(a.id()).lastSeq());
        var typed =
            service.send(
                a.id(),
                new Send(
                    "使用 $character-renamed 检查",
                    UUID.randomUUID().toString(),
                    12L,
                    "ASSISTANT",
                    null,
                    null,
                    List.of()));
        await(agent, typed.runId());
        assertEquals("SUCCEEDED", agent.query().detail(typed.runId()).get("status"));
        assertEquals("EXPLICIT", skills.runSkills(typed.runId()).getFirst().source());
        assertEquals(newer.versionId(), skills.runSkills(typed.runId()).getFirst().versionId());
      } finally {
        agent.sweeper().close();
        agent.executor().close();
      }
    }
  }

  @Test
  void upgradeRemovesDiscoveryAndPinsWithoutLosingMessagesOrVersions() throws Exception {
    try (TestDatabase database = new TestDatabase()) {
      var source = database.source();
      org.flywaydb.core.Flyway.configure().dataSource(source).target("12").load().migrate();
      var db = new JdbcAccess(source);
      String project =
          db.first("INSERT INTO tb_project(name) VALUES('旧作品') RETURNING id").get("id").toString();
      String conversation =
          db.first(
                  "INSERT INTO tb_conversation(project_id,model_id,model_config,discovery_enabled)"
                      + " VALUES(?::uuid,'test','{}',false) RETURNING id",
                  project)
              .get("id")
              .toString();
      db.update(
          "INSERT INTO tb_conversation_message(conversation_id,seq,role,content,status)"
              + " VALUES(?::uuid,1,'USER','历史消息','SUCCEEDED')",
          conversation);
      String skill =
          db.first("INSERT INTO tb_skill(name) VALUES('旧技能') RETURNING id").get("id").toString();
      String version = UUID.randomUUID().toString();
      db.update(
          "INSERT INTO"
              + " tb_skill_version(id,skill_id,package_hash,storage_key,status,metadata,size_bytes,file_count,compatibility)"
              + " VALUES(?::uuid,?::uuid,repeat('a',64),'old-package','READY','{}',10,1,'{}')",
          version,
          skill);
      db.update(
          "INSERT INTO tb_conversation_skill VALUES(?::uuid,?::uuid,?::uuid)",
          conversation,
          skill,
          version);
      org.flywaydb.core.Flyway.configure().dataSource(source).load().migrate();
      assertNull(
          db.first("SELECT to_regclass(current_schema() || '.tb_conversation_skill') AS name")
              .get("name"));
      assertEquals(
          0,
          ((Number)
                  db.first(
                          "SELECT count(*) AS n FROM information_schema.columns WHERE"
                              + " table_schema=current_schema() AND table_name='tb_conversation'"
                              + " AND column_name='discovery_enabled'")
                      .get("n"))
              .intValue());
      assertEquals("历史消息", db.first("SELECT content FROM tb_conversation_message").get("content"));
      assertEquals(
          1, ((Number) db.first("SELECT count(*) AS n FROM tb_skill_version").get("n")).intValue());
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
                .createConversationService(
                    source,
                    tx,
                    PageCursor.configured(),
                    content,
                    agent,
                    skills,
                    new ModelApiConfig(
                        ModelApiConfig.MESSAGES, "http://127.0.0.1", "test", "test-model"),
                    gateway,
                    process,
                    () -> Map.of("contextWindowTokens", 1000000));
        String project = (String) content.projects().createProject("压缩测试").get("id");
        var a = service.create(project);
        for (int n = 0; n < 4; n++) {
          var r =
              service.send(
                  a.id(),
                  new Send(
                      "继续 " + n,
                      UUID.randomUUID().toString(),
                      (long) n * 2,
                      "ASSISTANT",
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
        var b = service.create(project);
        for (int n = 0; n < 3; n++) {
          var r =
              service.send(
                  b.id(),
                  new Send(
                      "继续 " + n,
                      UUID.randomUUID().toString(),
                      (long) n * 2,
                      "ASSISTANT",
                      null,
                      null,
                      List.of()));
          await(agent, r.runId());
        }
        fail.set(true);
        var failed =
            service.send(
                b.id(),
                new Send(
                    "触发失败", UUID.randomUUID().toString(), 6L, "ASSISTANT", null, null, List.of()));
        long end = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < end
            && Set.of("QUEUED", "RUNNING")
                .contains(agent.query().detail(failed.runId()).get("status"))) Thread.sleep(20);
        assertEquals(
            "CONTEXT_COMPACTION_FAILED", agent.query().detail(failed.runId()).get("errorCode"));
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
    awaitTerminal(agent, run);
    var snapshot = agent.query().detail(run);
    assertEquals("SUCCEEDED", snapshot.get("status"), snapshot.toString());
  }

  private static void awaitTerminal(AgentConfiguration.Services agent, String run) throws Exception {
    long end = System.nanoTime() + 10_000_000_000L;
    while (System.nanoTime() < end) {
      var snapshot = agent.query().detail(run);
      if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(snapshot.get("status"))) {
        return;
      }
      Thread.sleep(20);
    }
    fail("Run did not finish");
  }
}
