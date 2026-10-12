package com.mojie.config;

import static com.mojie.common.model.JsonSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.mojie.agent.model.ModelApiConfig;
import com.mojie.agent.service.port.ModelGateway;
import com.mojie.agent.service.port.ModelGateway.*;
import com.mojie.common.pagination.PageCursor;
import com.mojie.common.repository.jdbc.JdbcAccess;
import com.mojie.conversation.model.ConversationModels.Send;
import com.mojie.infrastructure.skill.*;
import com.mojie.skill.repository.jdbc.JdbcSkillRepository;
import com.mojie.skill.service.SkillService;
import com.mojie.support.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CreateProposalsIT {
  @TempDir Path data;

  @Test
  void emptyWorkCreatesMultipleDraftsAndAuthorSavesFullFirstVersionsIdempotently()
      throws Exception {
    try (var database = new TestDatabase()) {
      var source = database.source();
      var tx = TestServices.transactions(source);
      var content = TestServices.content(source);
      var calls = new AtomicInteger();
      var updateCalls = new AtomicReference<List<ToolCall>>();
      var updateReads = new AtomicReference<List<ToolCall>>();
      var updateCount = new AtomicInteger();
      ModelGateway gateway =
          request -> {
            if (updateCalls.get() != null) {
              int step = updateCount.incrementAndGet();
              if (step < 3)
                return new Response(
                    "修改已有目标",
                    step == 1 ? updateReads.get() : updateCalls.get(),
                    FinishReason.TOOL_CALLS,
                    "test",
                    "tool_use",
                    Map.of());
              assertTrue(
                  request.messages().getLast().replies().stream()
                      .allMatch(r -> r.result().get("success").equals(true)));
              return new Response(
                  "已生成四份修改建议。", List.of(), FinishReason.FINAL, "test", "end_turn", Map.of());
            }
            if (calls.incrementAndGet() == 1) {
              List<ToolCall> tools = new ArrayList<>();
              for (String title : List.of("任务", "世界观", "写作约束"))
                tools.add(
                    call(
                        "propose_setting_edit",
                        Map.of(
                            "operation",
                            "CREATE",
                            "proposedType",
                            title.equals("世界观") ? "WORLD" : "RULE",
                            "proposedTitle",
                            title,
                            "proposedContent",
                            title + "正文",
                            "reason",
                            "根据构思新建")));
              for (String title : List.of("第一章", "第二章"))
                tools.add(
                    call(
                        "propose_chapter_edit",
                        Map.of(
                            "operation",
                            "CREATE",
                            "proposedTitle",
                            title,
                            "proposedContent",
                            title + "完整正文",
                            "reason",
                            "开篇")));
              return new Response(
                  "准备五份草稿", tools, FinishReason.TOOL_CALLS, "test", "tool_use", Map.of());
            }
            assertEquals(5, request.messages().getLast().replies().size());
            assertTrue(
                request.messages().getLast().replies().stream()
                    .allMatch(r -> r.result().get("success").equals(true)));
            return new Response(
                "已生成五份待审阅草稿，保存后生效。", List.of(), FinishReason.FINAL, "test", "end_turn", Map.of());
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
        var conversations =
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
        String project = (String) content.projects().createProject("空作品新建验收").get("id");
        var conversation = conversations.create(project);
        var receipt =
            conversations.send(
                conversation.id(),
                new Send(
                    "创建任务、世界观、写作约束和两章",
                    UUID.randomUUID().toString(),
                    0L,
                    null,
                    null,
                    null,
                    List.of(),
                    null,
                    null));
        var snapshot = await(agent, receipt.runId());
        assertTrue(content.settings().settingPage(project, null, null, null, 50).items().isEmpty());
        assertTrue(content.chapters().chapterPage(project, null, 50).items().isEmpty());
        var drafts = (List<Map<String, Object>>) snapshot.get("proposals");
        assertEquals(5, drafts.size());
        assertEquals(1, agent.query().page(project, null, null, null, 50).items().size());
        assertEquals(5, drafts.stream().map(p -> p.get("proposalId")).distinct().count());
        var byTitle = new HashMap<String, String>();
        for (var draft : drafts) {
          assertEquals("CREATE", draft.get("operation"));
          assertNull(draft.get("targetChapterId"));
          assertNull(draft.get("targetSettingId"));
          byTitle.put((String) draft.get("targetTitle"), (String) draft.get("proposalId"));
        }
        String settingDraft = byTitle.get("任务");
        String settingId;
        // Concurrent accept/save retries must create just one object.
        try (var workers = Executors.newFixedThreadPool(2)) {
          var first =
              workers.submit(
                  () ->
                      content
                          .proposals()
                          .saveSetting(settingDraft, "RULE", "任务", "审阅后的任务正文", null));
          var second =
              workers.submit(
                  () ->
                      content
                          .proposals()
                          .saveSetting(settingDraft, "RULE", "任务", "审阅后的任务正文", null));
          settingId = first.get().targetSettingId();
          assertEquals(settingId, second.get().targetSettingId());
        }
        assertEquals(1, content.settings().setting(settingId).get("version"));
        assertEquals("审阅后的任务正文", content.settings().setting(settingId).get("content"));
        var history = content.settings().revisions(settingId);
        assertEquals(1, history.size());
        assertEquals("AGENT", history.getFirst().source());
        assertEquals(settingDraft, history.getFirst().proposalId());
        var world = content.proposals().accept(byTitle.get("世界观"));
        assertEquals("APPLIED", world.status());
        assertEquals("REJECTED", content.proposals().reject(byTitle.get("写作约束")).status());
        assertEquals(
            2, content.settings().settingPage(project, null, null, null, 50).items().size());
        String chapterDraft = byTitle.get("第一章");
        assertThrows(
            com.mojie.common.error.BusinessException.class,
            () -> content.proposals().saveChapterCreate(chapterDraft, "", "内容"));
        assertTrue(content.chapters().chapterPage(project, null, 50).items().isEmpty());
        var saved = content.proposals().saveChapterCreate(chapterDraft, "第一章（审阅）", "完整首版正文");
        assertEquals(
            saved.targetChapterId(),
            content.proposals().saveChapterCreate(chapterDraft, "重试", "重试").targetChapterId());
        assertEquals(
            saved.targetChapterId(), content.proposals().accept(chapterDraft).targetChapterId());
        var chapter = content.chapters().chapter(saved.targetChapterId());
        assertEquals(1, chapter.get("version"));
        assertEquals(1, chapter.get("chapterOrder"));
        assertEquals("完整首版正文", chapter.get("content"));
        var db = new JdbcAccess(source);
        assertEquals(
            1L,
            db.first(
                    "SELECT count(*) AS n FROM tb_chapter_revision WHERE chapter_id=?::uuid",
                    saved.targetChapterId())
                .get("n"));
        assertEquals(
            "AGENT",
            db.first(
                    "SELECT source FROM tb_chapter_revision WHERE chapter_id=?::uuid",
                    saved.targetChapterId())
                .get("source"));
        var next = content.proposals().accept(byTitle.get("第二章"));
        assertEquals(2, content.chapters().chapter(next.targetChapterId()).get("chapterOrder"));
        assertEquals(5, ((List<?>) agent.query().detail(receipt.runId()).get("proposals")).size());
        updateReads.set(
            List.of(
                call(
                    "read_settings",
                    Map.of("settingIds", List.of(settingId, world.targetSettingId()))),
                call("read_chapter", Map.of("chapterId", saved.targetChapterId())),
                call("read_chapter", Map.of("chapterId", next.targetChapterId()))));
        updateCalls.set(
            List.of(
                call(
                    "propose_setting_edit",
                    Map.of(
                        "operation",
                        "UPDATE",
                        "settingId",
                        settingId,
                        "expectedVersion",
                        1,
                        "proposedType",
                        "RULE",
                        "proposedTitle",
                        "任务",
                        "proposedContent",
                        "更新后的任务",
                        "reason",
                        "调整")),
                call(
                    "propose_setting_edit",
                    Map.of(
                        "operation",
                        "UPDATE",
                        "settingId",
                        world.targetSettingId(),
                        "expectedVersion",
                        1,
                        "proposedType",
                        "WORLD",
                        "proposedTitle",
                        "世界观",
                        "proposedContent",
                        "更新后的世界观",
                        "reason",
                        "调整")),
                call(
                    "propose_chapter_edit",
                    Map.of(
                        "operation",
                        "UPDATE",
                        "chapterId",
                        saved.targetChapterId(),
                        "expectedVersion",
                        1,
                        "proposedContent",
                        "更新后的第一章",
                        "reason",
                        "调整")),
                call(
                    "propose_chapter_edit",
                    Map.of(
                        "operation",
                        "UPDATE",
                        "chapterId",
                        next.targetChapterId(),
                        "expectedVersion",
                        1,
                        "proposedContent",
                        "更新后的第二章",
                        "reason",
                        "调整"))));
        var updatedConversation = conversations.get(conversation.id());
        var updates =
            conversations.send(
                conversation.id(),
                new Send(
                    "修改两条设定和两章",
                    UUID.randomUUID().toString(),
                    updatedConversation.lastSeq(),
                    null,
                    null,
                    null,
                    List.of(),
                    null,
                    null));
        var updateSnapshot = await(agent, updates.runId());
        var updateDrafts = (List<Map<String, Object>>) updateSnapshot.get("proposals");
        assertEquals(4, updateDrafts.size());
        assertEquals(2, agent.query().page(project, null, null, null, 50).items().size());
        assertEquals(
            2, agent.query().page(project, next.targetChapterId(), null, null, 50).items().size());
        assertEquals("完整首版正文", content.chapters().chapter(saved.targetChapterId()).get("content"));
        content.settings().updateSetting(settingId, "RULE", "任务", "另一个窗口的新内容", 1);
        assertEquals(
            "CONFLICTED",
            content.proposals().accept((String) updateDrafts.get(0).get("proposalId")).status());
        assertEquals(
            "APPLIED",
            content.proposals().accept((String) updateDrafts.get(1).get("proposalId")).status());
        assertEquals(
            "APPLIED",
            content.proposals().accept((String) updateDrafts.get(2).get("proposalId")).status());
        assertEquals(
            "REJECTED",
            content.proposals().reject((String) updateDrafts.get(3).get("proposalId")).status());

      } finally {
        agent.sweeper().close();
        agent.executor().close();
      }
    }
  }

  private static ToolCall call(String name, Map<String, Object> args) {
    return new ToolCall(UUID.randomUUID().toString(), name, tree(encode(args)));
  }

  private static Map<String, Object> await(AgentConfiguration.Services agent, String run)
      throws Exception {
    long end = System.nanoTime() + 10_000_000_000L;
    while (System.nanoTime() < end) {
      var result = agent.query().detail(run);
      if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(result.get("status"))) {
        assertEquals("SUCCEEDED", result.get("status"), result.toString());
        return result;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("Run did not finish");
  }
}
