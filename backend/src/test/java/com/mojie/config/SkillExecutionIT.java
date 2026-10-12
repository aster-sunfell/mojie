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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SkillExecutionIT {
  @TempDir Path data;

  @Test
  void realPythonAndNodeUseVersionedChapterAndPriorArtifact() throws Exception {
    String url = System.getenv("MOJIE_TEST_EXECUTOR_URL");
    Assumptions.assumeTrue(url != null && !url.isBlank(), "Real isolated executor not configured");
    try (TestDatabase database = new TestDatabase()) {
      var source = database.source();
      Flyway.configure().dataSource(source).load().migrate();
      var tx = TestServices.transactions(source);
      var content = TestServices.content(source);
      String project = (String) content.projects().createProject("脚本交付测试").get("id");
      var chapter = content.chapters().createChapter(project, "原文");
      String chapterId = (String) chapter.get("id");
      var saved =
          content
              .saves()
              .saveChapter(
                  chapterId,
                  "本轮固定章节：林舟走桥。",
                  ((Number) chapter.get("version")).intValue());
      var skillId = new String[1];
      var step = new AtomicInteger();
      var waiting = new AtomicBoolean();
      ModelGateway gateway =
          request -> {
            if (waiting.get())
              return new Response(
                  "",
                  List.of(
                      new ToolCall(
                          "waiting",
                          "run_skill_script",
                          tree(
                              encode(
                                  Map.of(
                                      "skillId",
                                      skillId[0],
                                      "relativePath",
                                      "scripts/wait.py",
                                      "arguments",
                                      List.of(),
                                      "inputs",
                                      List.of()))))),
                  FinishReason.TOOL_CALLS,
                  "test",
                  "tool_use",
                  Map.of());
            int n = step.getAndIncrement();
            ToolCall call;
            if (n == 0)
              call =
                  new ToolCall(
                      "chapter", "read_chapter", tree(encode(Map.of("chapterId", chapterId))));
            else if (n == 1)
              call =
                  new ToolCall(
                      "python",
                      "run_skill_script",
                      tree(
                          encode(
                              Map.of(
                                  "skillId",
                                  skillId[0],
                                  "relativePath",
                                  "scripts/report.py",
                                  "arguments",
                                  List.of(),
                                  "inputs",
                                  List.of(
                                      Map.of(
                                          "name",
                                          "content.txt",
                                          "source",
                                          Map.of("type", "CHAPTER", "chapterId", chapterId)))))));
            else if (n == 2) {
              var result = request.messages().getLast().replies().getFirst().result();
              assertEquals(true, result.get("success"), result.toString());
              var data = (Map<?, ?>) result.get("data");
              String artifact =
                  (String)
                      ((Map<?, ?>) ((List<?>) data.get("artifacts")).getFirst()).get("artifactId");
              call =
                  new ToolCall(
                      "node",
                      "run_skill_script",
                      tree(
                          encode(
                              Map.of(
                                  "skillId",
                                  skillId[0],
                                  "relativePath",
                                  "scripts/copy.js",
                                  "arguments",
                                  List.of(),
                                  "inputs",
                                  List.of(
                                      Map.of(
                                          "name",
                                          "previous.txt",
                                          "source",
                                          Map.of("type", "ARTIFACT", "artifactId", artifact)))))));
            } else
              return new Response(
                  "两个脚本完成，报告已交付。", List.of(), FinishReason.FINAL, "test", "end_turn", Map.of());
            return new Response(
                "", List.of(call), FinishReason.TOOL_CALLS, "test", "tool_use", Map.of());
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
                new HttpSkillExecutor(url),
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
        var upload =
            skills.importPackage(
                null,
                Map.of(
                    "SKILL.md",
                    bytes(
                        "---\n"
                            + "name: two-runtime-report\n"
                            + "description: Python 与 Node 报告\n"
                            + "---\n"
                            + "执行两个脚本，保持输入来源。\n"),
                    "scripts/report.py",
                    bytes(
                        "from pathlib import Path\n"
                            + "Path('/output/report.txt').write_text(Path('/input/content.txt').read_text())\n"),
                    "scripts/copy.js",
                    bytes(
                        "require('fs').writeFileSync('/output/copied.txt',require('fs').readFileSync('/input/previous.txt'))\n"),
                    "scripts/wait.py",
                    bytes(
                        "import subprocess,time\n"
                            + "subprocess.Popen(['python3','-c','import time; time.sleep(60)'])\n"
                            + "time.sleep(60)\n")));
        assertEquals("READY", upload.status());
        skillId[0] = upload.skillId();
        var skill = skills.requireSkill(skillId[0]);
        skills.patch(skill.id(), skill.version(), true, null, false);
        var c = service.create(project);
        var run =
            service.send(
                c.id(),
                new Send(
                    "生成报告",
                    UUID.randomUUID().toString(),
                    0L,
                    "ASSISTANT",
                    null,
                    null,
                    List.of(new Selection(skill.id(), upload.versionId()))));
        long end = System.nanoTime() + 60000000000L;
        while (System.nanoTime() < end
            && Set.of("QUEUED", "RUNNING")
                .contains(agent.query().detail(run.runId()).get("status")))
          Thread.sleep(25);
        assertEquals(
            "SUCCEEDED",
            agent.query().detail(run.runId()).get("status"),
            agent.query().detail(run.runId()).toString());
        var artifacts = skills.runArtifacts(run.runId());
        assertEquals(2, artifacts.size());
        for (var artifact : artifacts)
          assertEquals(
              "本轮固定章节：林舟走桥。",
              new String(skills.downloadArtifact(artifact.id()), StandardCharsets.UTF_8));
        var db = new JdbcAccess(source);
        var executions =
            db.query(
                "SELECT * FROM tb_skill_execution WHERE run_id=?::uuid ORDER BY started_at",
                run.runId());
        assertEquals(2, executions.size());
        for (var execution : executions) {
          assertEquals("SUCCEEDED", execution.get("status"));
          assertTrue(execution.get("environment").toString().contains("sha256:"));
        }
        assertTrue(executions.getFirst().get("inputs").toString().contains("version"));
        assertTrue(
            executions.getLast().get("inputs").toString().contains(artifacts.getFirst().id()));
        assertEquals(
            "RESOURCE_NOT_FOUND",
            assertThrows(
                    BusinessException.class,
                    () -> skills.downloadArtifact(UUID.randomUUID().toString()))
                .code());
        long before =
            ((Number)
                    db.first("SELECT used_bytes AS n FROM tb_skill_quota WHERE singleton")
                        .get("n"))
                .longValue();
        db.update(
            "UPDATE tb_artifact SET expires_at=now()-interval '1 day' WHERE run_id=?::uuid",
            run.runId());
        skills.maintain();
        assertEquals("READY", skills.artifact(artifacts.getFirst().id()).status());
        assertTrue(
            java.nio.file.Files.exists(
                data.resolve("artifacts/" + artifacts.getFirst().id() + "/file")));
        assertEquals("EXPIRED", skills.artifact(artifacts.getLast().id()).status());
        assertFalse(
            java.nio.file.Files.exists(
                data.resolve("artifacts/" + artifacts.getLast().id() + "/file")));
        assertEquals(
            "ARTIFACT_EXPIRED",
            assertThrows(
                    BusinessException.class,
                    () -> skills.downloadArtifact(artifacts.getFirst().id()))
                .code());
        skills.maintain();
        assertEquals(
            before - artifacts.getLast().sizeBytes(),
            ((Number)
                    db.first("SELECT used_bytes AS n FROM tb_skill_quota WHERE singleton")
                        .get("n"))
                .longValue());
        waiting.set(true);
        var cancelled =
            service.send(
                c.id(),
                new Send(
                    "停止脚本测试",
                    UUID.randomUUID().toString(),
                    service.get(c.id()).lastSeq(),
                    "ASSISTANT",
                    null,
                    null,
                    List.of(new Selection(skill.id(), upload.versionId()))));
        String executionId = null;
        boolean containerStarted = false;
        long startedDeadline = System.nanoTime() + 10000000000L;
        while (System.nanoTime() < startedDeadline) {
          var row =
              db.first(
                  "SELECT id FROM tb_skill_execution WHERE run_id=?::uuid AND status='STARTED'",
                  cancelled.runId());
          if (row != null) {
            executionId = row.get("id").toString();
            var inspect =
                new ProcessBuilder(
                        "docker",
                        "inspect",
                        "-f",
                        "{{.State.Running}}",
                        "mojie-skill-" + executionId)
                    .start();
            String running =
                new String(inspect.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            inspect.waitFor();
            if (running.equals("true")) {
              containerStarted = true;
              break;
            }
          }
          Thread.sleep(25);
        }
        assertNotNull(executionId, "Execution was not started");
        assertTrue(containerStarted, "Isolated script container was not running");
        agent.runs().cancel(cancelled.runId());
        long cancelledDeadline = System.nanoTime() + 10000000000L;
        while (System.nanoTime() < cancelledDeadline
            && Set.of("QUEUED", "RUNNING")
                .contains(agent.query().detail(cancelled.runId()).get("status")))
          Thread.sleep(25);
        assertEquals("CANCELLED", agent.query().detail(cancelled.runId()).get("status"));
        assertEquals("CANCELLED", skills.repository().execution(executionId).status());
        assertTrue(skills.runArtifacts(cancelled.runId()).isEmpty());
        assertNull(agent.query().detail(cancelled.runId()).get("proposalId"));
        assertNotNull(
            db.first(
                "SELECT seq FROM tb_agent_event WHERE run_id=?::uuid AND type='script.finished'",
                cancelled.runId()));
        var inspect = new ProcessBuilder("docker", "inspect", "mojie-skill-" + executionId).start();
        inspect.getInputStream().readAllBytes();
        inspect.getErrorStream().readAllBytes();
        assertNotEquals(0, inspect.waitFor(), "Cancelled container still exists");
        assertNull(service.get(c.id()).activeRunId());
      } finally {
        agent.sweeper().close();
        agent.executor().close();
      }
    }
  }

  private byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
