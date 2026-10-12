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

class SettingAssistantIT {
  @TempDir Path data;

  @Test
  void settingSuggestionsRequireFreshProvidedReadsAndSaveVersionedReviewedContent()
      throws Exception {
    try (TestDatabase database = new TestDatabase()) {
      var source = database.source();
      var tx = TestServices.transactions(source);
      var content = TestServices.content(source);
      var requests = Collections.synchronizedList(new ArrayList<Request>());
      var steps = new ConcurrentHashMap<String, AtomicInteger>();
      var settingId = new AtomicReference<String>();
      var freshVersion = new AtomicInteger(1);
      ModelGateway gateway =
          request -> {
            requests.add(request);
            String instruction =
                request.messages().stream()
                    .filter(m -> m.role().equals("user"))
                    .flatMap(m -> m.content().stream())
                    .filter(TextPart.class::isInstance)
                    .map(TextPart.class::cast)
                    .map(TextPart::text)
                    .reduce((a, b) -> b)
                    .orElseThrow();
            if (instruction.endsWith("ONLY")) {
              assertTrue(request.tools().stream().noneMatch(t -> t.name().startsWith("propose_")));
              return answer("只分析，不生成建议");
            }
            assertTrue(
                request.tools().stream().anyMatch(t -> t.name().equals("propose_chapter_edit")));
            assertTrue(
                request.tools().stream().anyMatch(t -> t.name().equals("propose_setting_edit")));
            assertTrue(request.tools().stream().noneMatch(t -> t.name().equals("propose_edit")));
            int step =
                steps
                    .computeIfAbsent(instruction, ignored -> new AtomicInteger())
                    .incrementAndGet();
            Map<String, Object> args =
                Map.of(
                    "settingId",
                    settingId.get(),
                    "expectedVersion",
                    freshVersion.get(),
                    "proposedType",
                    "RULE",
                    "proposedTitle",
                    "林舟的行动规则",
                    "proposedContent",
                    "林舟行事谨慎，过河时选择石桥。",
                    "reason",
                    "统一人物动机");
            if (step == 1)
              return tools(
                  call("read_settings", Map.of("settingIds", List.of(settingId.get()))),
                  call("propose_setting_edit", args));
            if (step == 2) {
              assertTrue(
                  request
                      .messages()
                      .getLast()
                      .replies()
                      .toString()
                      .contains("INSUFFICIENT_CONTEXT"));
              return tools(call("propose_setting_edit", args));
            }
            return answer("已生成设定修改建议");
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
        String project = (String) content.projects().createProject("设定建议测试").get("id");
        settingId.set(
            (String)
                content.settings().createSetting(project, "CHARACTER", "林舟", "林舟不会游泳。").get("id"));
        var conversation = service.create(project);
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.send(
                    conversation.id(),
                    new Send(
                        "旧讨论", UUID.randomUUID().toString(), 0L, "CHAT", null, null, List.of())));

        Send first = send("EDIT", 0, false);
        var receipt = service.send(conversation.id(), first);
        var snapshot = await(agent, receipt.runId());
        assertEquals("SETTING", snapshot.get("targetType"));
        assertEquals(settingId.get(), snapshot.get("targetSettingId"));
        assertNull(snapshot.get("targetChapterId"));
        assertEquals("林舟", snapshot.get("targetTitle"));
        String proposalId = (String) snapshot.get("proposalId");
        var proposal = content.proposals().detail(proposalId);
        assertEquals("林舟不会游泳。", proposal.get("baseContent"));
        assertEquals("CHARACTER", proposal.get("baseType"));
        assertEquals("RULE", proposal.get("proposedType"));
        assertEquals("林舟的行动规则", proposal.get("proposedTitle"));
        assertEquals("林舟不会游泳。", content.settings().setting(settingId.get()).get("content"));
        assertEquals(receipt.runId(), service.send(conversation.id(), first).runId());
        assertEquals(
            "SETTING", agent.query().list(project, null, null, 20).getFirst().get("targetType"));

        assertEquals(
            "APPLIED",
            content
                .proposals()
                .saveSetting(proposalId, "CHARACTER", "林舟", "林舟谨慎，会走石桥。", 1)
                .status());
        assertEquals(
            "APPLIED",
            content
                .proposals()
                .saveSetting(proposalId, "CHARACTER", "林舟", "林舟谨慎，会走石桥。", 1)
                .status());
        var saved = content.settings().setting(settingId.get());
        assertEquals(2, saved.get("version"));
        assertEquals("林舟谨慎，会走石桥。", saved.get("content"));
        var history = content.settings().revisions(settingId.get());
        assertEquals(2, history.size());
        assertEquals("AGENT", history.getFirst().source());
        assertEquals(proposalId, history.getFirst().proposalId());
        assertEquals("CREATE", history.getLast().source());
        assertEquals(
            "VERSION_CONFLICT",
            assertThrows(
                    BusinessException.class,
                    () ->
                        content
                            .settings()
                            .updateSetting(settingId.get(), "CHARACTER", "林舟", "旧版本覆盖", 1))
                .code());

        freshVersion.set(2);
        receipt =
            service.send(
                conversation.id(), send("STALE", service.get(conversation.id()).lastSeq(), false));
        snapshot = await(agent, receipt.runId());
        proposalId = (String) snapshot.get("proposalId");
        content.settings().updateSetting(settingId.get(), "CHARACTER", "林舟", "用户的新设定", 2);
        assertEquals("CONFLICTED", content.proposals().accept(proposalId).status());
        assertEquals("用户的新设定", content.settings().setting(settingId.get()).get("content"));
        assertEquals(3, content.settings().setting(settingId.get()).get("version"));
        assertEquals("REJECTED", content.proposals().reject(proposalId).status());

        var onlyInput = send("ONLY", service.get(conversation.id()).lastSeq(), true);
        receipt = service.send(conversation.id(), onlyInput);
        snapshot = await(agent, receipt.runId());
        assertEquals(true, snapshot.get("analysisOnly"));
        assertNull(snapshot.get("proposalId"));
        assertEquals(receipt.runId(), service.send(conversation.id(), onlyInput).runId());
        assertEquals(
            "IDEMPOTENCY_CONFLICT",
            assertThrows(
                    BusinessException.class,
                    () ->
                        service.send(
                            conversation.id(),
                            new Send(
                                onlyInput.content(),
                                onlyInput.clientRequestId(),
                                onlyInput.expectedConversationSeq(),
                                null,
                                null,
                                null,
                                List.of(),
                                null,
                                null,
                                false)))
                .code());

        freshVersion.set(3);
        receipt =
            service.send(
                conversation.id(), send("ACCEPT", service.get(conversation.id()).lastSeq(), false));
        snapshot = await(agent, receipt.runId());
        assertEquals(
            "APPLIED", content.proposals().accept((String) snapshot.get("proposalId")).status());
        saved = content.settings().setting(settingId.get());
        assertEquals("RULE", saved.get("type"));
        assertEquals("林舟的行动规则", saved.get("title"));
        assertEquals(4, saved.get("version"));
      } finally {
        agent.sweeper().close();
        agent.executor().close();
      }
    }
  }

  private static Send send(String content, long seq, boolean analysisOnly) {
    return new Send(
        content,
        UUID.randomUUID().toString(),
        seq,
        null,
        null,
        null,
        List.of(),
        null,
        null,
        analysisOnly);
  }

  private static ToolCall call(String name, Map<String, Object> args) {
    return new ToolCall(UUID.randomUUID().toString(), name, tree(encode(args)));
  }

  private static Response tools(ToolCall... calls) {
    return new Response("", List.of(calls), FinishReason.TOOL_CALLS, "test", "tool_use", Map.of());
  }

  private static Response answer(String text) {
    return new Response(text, List.of(), FinishReason.FINAL, "test", "end_turn", Map.of());
  }

  private static Map<String, Object> await(AgentConfiguration.Services agent, String run)
      throws Exception {
    long end = System.nanoTime() + 10_000_000_000L;
    while (System.nanoTime() < end) {
      var snapshot = agent.query().detail(run);
      if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(snapshot.get("status"))) {
        assertEquals("SUCCEEDED", snapshot.get("status"), snapshot.toString());
        return snapshot;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("Run did not finish");
  }
}
