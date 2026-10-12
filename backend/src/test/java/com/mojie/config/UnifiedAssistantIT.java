package com.mojie.config;

import static com.mojie.common.model.JsonSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.mojie.agent.model.ModelApiConfig;
import com.mojie.agent.service.port.ModelGateway;
import com.mojie.agent.service.port.ModelGateway.*;
import com.mojie.common.error.BusinessException;
import com.mojie.common.pagination.PageCursor;
import com.mojie.conversation.model.ConversationModels.*;
import com.mojie.infrastructure.skill.*;
import com.mojie.skill.repository.jdbc.JdbcSkillRepository;
import com.mojie.skill.service.SkillService;
import com.mojie.support.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnifiedAssistantIT {
  @TempDir Path data;

  @Test
  void emptyWorkTitleReadsAreRecordedPinnedWithinRunAndRefreshedInNextTurn() throws Exception {
    try (TestDatabase database = new TestDatabase()) {
      var source = database.source();
      var tx = TestServices.transactions(source);
      var content = TestServices.content(source);
      var project = new AtomicReference<String>();
      var requests = Collections.synchronizedList(new ArrayList<Request>());
      var calls = new AtomicInteger();
      ModelGateway gateway =
          request -> {
            requests.add(request);
            assertTrue(request.systemPrompt().contains("read_project"));
            var read = request.tools().stream()
                .filter(t -> t.name().equals("read_project")).findFirst().orElseThrow();
            assertEquals(Map.of(), read.inputSchema().get("properties"));
            int step = calls.incrementAndGet();
            if (step == 1) {
              assertFalse(request.messages().toString().contains("原书名😀"));
              assertFalse(request.systemPrompt().contains("原书名😀"));
              return tool("read_project", Map.of());
            }
            if (step == 4) return tool("read_project", Map.of());
            var result = tree(encode(request.messages().getLast().replies().getFirst().result()));
            assertTrue(result.path("success").asBoolean());
            assertEquals(project.get(), result.path("data").path("projectId").asText());
            assertEquals(step == 5 ? "新书名" : "原书名😀", result.path("data").path("title").asText());
            assertEquals(step == 3 ? 2 : 1, result.path("budget").path("toolCallsUsed").asInt());
            if (step == 2) {
              // Renaming is allowed while a run is active; its subsequent read stays pinned.
              content.projects().renameProject(project.get(), "新书名");
              return tool("read_project", Map.of());
            }
            return answer(step == 3 ? "原书名分析完成" : "新书名分析完成");
          };
      UUID process = UUID.randomUUID();
      var agent = AgentConfiguration.create(
          source, content, tx, PageCursor.configured(), gateway, Clock.systemUTC(), process,
          "legacy", "test-model", 16000, "test-cursor-key-123456".getBytes(),
          ModelApiConfig.MESSAGES, 1000000, true, true);
      agent.startup().start();
      try {
        var skills = new SkillService(
            new JdbcSkillRepository(source), new FileSkillStorage(data), new HttpSkillExecutor(null),
            tx, PageCursor.configured());
        var service = new ConversationConfiguration().createConversationService(
            source, tx, PageCursor.configured(), content, agent, skills,
            new ModelApiConfig(ModelApiConfig.MESSAGES, "http://127.0.0.1", "test", "test-model"),
            gateway, process, () -> Map.of("contextWindowTokens", 1000000));
        project.set((String) content.projects().createProject("原书名😀").get("id"));
        content.projects().createProject("其他作品标题不应返回");
        var conversation = service.create(project.get());
        var first = service.send(conversation.id(), send("评价当前书名", 0, null, null));
        var snapshot = await(agent, first.runId(), "SUCCEEDED");
        assertEquals("原书名分析完成", snapshot.get("analysis"));
        assertEquals(2, snapshot.get("toolCallCount"));
        assertNull(snapshot.get("proposalId"));
        assertNull(snapshot.get("contextChapterId"));
        var trace = tree(encode(agent.query().trace(first.runId())));
        assertEquals(2, trace.path("steps").size());
        for (var step : trace.path("steps")) {
          assertEquals("read_project", step.path("toolName").asText());
          assertEquals("SUCCEEDED", step.path("status").asText());
          assertEquals("原书名😀", step.path("result").path("data").path("title").asText());
        }
        assertTrue(trace.path("messages").toString().contains("原书名😀"));
        assertTrue((Integer) snapshot.get("toolTextCodepoints") > 0);
        try (var connection = source.getConnection();
            var statement = connection.prepareStatement(
                "SELECT p.content FROM tb_agent_run r JOIN tb_agent_prompt_snapshot p"
                    + " ON p.hash = r.tool_schema_hash WHERE r.id = ?::uuid")) {
          statement.setString(1, first.runId());
          try (var rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertTrue(tree(rows.getString(1)).toString().contains("read_project"));
          }
        }
        var next = service.send(conversation.id(),
            send("再评价当前书名", service.get(conversation.id()).lastSeq(), null, null));
        snapshot = await(agent, next.runId(), "SUCCEEDED");
        assertEquals("新书名分析完成", snapshot.get("analysis"));
        assertEquals(1, snapshot.get("toolCallCount"));
        assertEquals(5, calls.get());
        assertTrue(requests.get(3).messages().toString().contains("原书名😀"));
        assertEquals("新书名", content.projects().project(project.get()).get("title"));
        assertTrue(content.chapters().chapters(project.get(), 20).isEmpty());
      } finally {
        agent.sweeper().close();
        agent.executor().close();
      }
    }
  }

  @Test
  void optionalContextSelectiveReadsCrossChapterEditsAndRetryKeepIndependentTargets()
      throws Exception {
    try (TestDatabase database = new TestDatabase()) {
      var source = database.source();
      var tx = TestServices.transactions(source);
      var content = TestServices.content(source);
      var requests = Collections.synchronizedList(new ArrayList<Request>());
      var calls = new ConcurrentHashMap<String, AtomicInteger>();
      var preferred = new AtomicReference<String>();
      var other = new AtomicReference<String>();
      ModelGateway gateway =
          request -> {
            requests.add(request);
            var editTool =
                request.tools().stream()
                    .filter(t -> t.name().equals("propose_chapter_edit"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(
                ((List<?>) editTool.inputSchema().get("required")).contains("operation")
                    && editTool.inputSchema().get("properties").toString().contains("proposedTitle"));
            String instruction =
                request.messages().stream()
                    .filter(m -> m.role().equals("user"))
                    .flatMap(m -> m.content().stream())
                    .filter(TextPart.class::isInstance)
                    .map(TextPart.class::cast)
                    .map(TextPart::text)
                    .reduce((a, b) -> b)
                    .orElseThrow();
            int step =
                calls
                    .computeIfAbsent(instruction, ignored -> new AtomicInteger())
                    .incrementAndGet();
            if (instruction.endsWith("DIRECT") || instruction.endsWith("WHY"))
              return answer("讨论完成");
            if (instruction.endsWith("REVIEW"))
              return step == 1
                  ? tool("read_chapter", Map.of("chapterId", preferred.get()))
                  : answer("审核完成");
            if (instruction.endsWith("RETRY")) {
              if (step == 1)
                throw new ModelGateway.ModelException("MODEL_NETWORK_ERROR", "test failure");
              if (step == 2) return tool("read_chapter", Map.of("chapterId", preferred.get()));
              assertTrue(request.messages().getLast().replies().toString().contains("第一章机密原文"));
              assertFalse(request.messages().getLast().replies().toString().contains("第一章新版"));
              return answer("基于原参考版本重试完成");
            }
            if (step == 1) return tool("search_chapters", Map.of("keyword", "矛盾"));
            if (step == 2) return tool("read_chapter", Map.of("chapterId", other.get()));
            if (step == 3)
              return tool(
                  "propose_chapter_edit",
                  Map.of(
                      "chapterId",
                      other.get(),
                      "expectedVersion",
                      2,
                      "proposedContent",
                      "第二章修正正文",
                      "reason",
                      "修正矛盾"));
            return answer("检查并修改完成");
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
        String project = (String) content.projects().createProject("统一对话测试").get("id");
        preferred.set((String) content.chapters().createChapter(project, "第一章").get("id"));
        other.set((String) content.chapters().createChapter(project, "第二章").get("id"));
        content.saves().saveChapter(preferred.get(), "第一章机密原文", 1);
        content.saves().saveChapter(other.get(), "第二章矛盾原文", 1);
        var conversation = service.create(project);

        Send directInput = send("DIRECT", 0, preferred.get(), 2L);
        var direct = service.send(conversation.id(), directInput);
        var snapshot = await(agent, direct.runId(), "SUCCEEDED");
        assertEquals("ASSISTANT", snapshot.get("taskType"));
        assertEquals(preferred.get(), snapshot.get("contextChapterId"));
        assertEquals(2L, snapshot.get("contextVersion"));
        assertNull(snapshot.get("targetChapterId"));
        assertEquals(0, snapshot.get("toolCallCount"));
        assertFalse(requests.getFirst().messages().toString().contains("第一章机密原文"));
        assertEquals(direct.runId(), service.send(conversation.id(), directInput).runId());

        var independent = service.create(project);
        var searched = service.send(independent.id(), send("SEARCH_EDIT", 0, null, null));
        snapshot = await(agent, searched.runId(), "SUCCEEDED");
        assertNull(snapshot.get("contextChapterId"));
        assertEquals(other.get(), snapshot.get("targetChapterId"));
        assertEquals(3, snapshot.get("toolCallCount"));
        var proposal = content.proposals().detail((String) snapshot.get("proposalId"));
        assertEquals(other.get(), proposal.get("targetChapterId"));
        assertEquals("第二章矛盾原文", proposal.get("baseContent"));

        var cross =
            service.send(
                conversation.id(),
                send("CROSS_EDIT", service.get(conversation.id()).lastSeq(), preferred.get(), 2L));
        snapshot = await(agent, cross.runId(), "SUCCEEDED");
        assertEquals(preferred.get(), snapshot.get("contextChapterId"));
        assertEquals(other.get(), snapshot.get("targetChapterId"));
        assertEquals(2, snapshot.get("baseVersion"));
        assertEquals(
            other.get(),
            content.proposals().detail((String) snapshot.get("proposalId")).get("targetChapterId"));
        assertEquals("第二章矛盾原文", content.chapters().chapter(other.get()).get("content"));
        assertTrue(
            agent.query().list(project, other.get(), null, 20).stream()
                .anyMatch(r -> r.get("id").equals(cross.runId())));
        assertTrue(
            agent.query().list(project, preferred.get(), null, 20).stream()
                .anyMatch(r -> r.get("id").equals(direct.runId())));

        var review =
            service.send(
                conversation.id(),
                send("REVIEW", service.get(conversation.id()).lastSeq(), preferred.get(), 2L));
        snapshot = await(agent, review.runId(), "SUCCEEDED");
        assertNull(snapshot.get("proposalId"));
        assertEquals(1, snapshot.get("toolCallCount"));
        var why =
            service.send(
                conversation.id(),
                send("WHY", service.get(conversation.id()).lastSeq(), null, null));
        assertEquals(0, await(agent, why.runId(), "SUCCEEDED").get("toolCallCount"));

        var failed =
            service.send(
                conversation.id(),
                send("RETRY", service.get(conversation.id()).lastSeq(), preferred.get(), 2L));
        await(agent, failed.runId(), "FAILED");
        content.saves().saveChapter(preferred.get(), "第一章新版", 2);
        var retry =
            service.retry(conversation.id(), failed.messageId(), UUID.randomUUID().toString());
        snapshot = await(agent, retry.runId(), "SUCCEEDED");
        assertEquals(2L, snapshot.get("contextVersion"));
        assertEquals(failed.messageId(), retry.messageId());

        assertEquals(
            "VERSION_CONFLICT",
            assertThrows(
                    BusinessException.class,
                    () ->
                        service.send(
                            conversation.id(),
                            send(
                                "STALE",
                                service.get(conversation.id()).lastSeq(),
                                preferred.get(),
                                2L)))
                .code());
        String foreignProject = (String) content.projects().createProject("其他作品").get("id");
        String foreign = (String) content.chapters().createChapter(foreignProject, "其他章").get("id");
        assertEquals(
            "RESOURCE_NOT_FOUND",
            assertThrows(
                    BusinessException.class,
                    () ->
                        service.send(
                            conversation.id(),
                            send("FOREIGN", service.get(conversation.id()).lastSeq(), foreign, 1L)))
                .code());
      } finally {
        agent.sweeper().close();
        agent.executor().close();
      }
    }
  }

  private static Send send(String content, long seq, String chapter, Long version) {
    return new Send(
        content, UUID.randomUUID().toString(), seq, null, null, null, List.of(), chapter, version);
  }

  private static Response tool(String name, Map<String, Object> args) {
    return new Response(
        "",
        List.of(new ToolCall(UUID.randomUUID().toString(), name, tree(encode(args)))),
        FinishReason.TOOL_CALLS,
        "test",
        "tool_use",
        Map.of());
  }

  private static Response answer(String text) {
    return new Response(text, List.of(), FinishReason.FINAL, "test", "end_turn", Map.of());
  }

  private static Map<String, Object> await(
      AgentConfiguration.Services agent, String run, String status) throws Exception {
    long end = System.nanoTime() + 10_000_000_000L;
    while (System.nanoTime() < end) {
      var snapshot = agent.query().detail(run);
      if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(snapshot.get("status"))) {
        assertEquals(status, snapshot.get("status"), snapshot.toString());
        return snapshot;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("Run did not finish");
  }
}
