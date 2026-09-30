package com.mojie.agent.runtime;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.agent.model.ModelGateway;
import com.mojie.agent.model.ModelGateway.*;
import com.mojie.agent.model.openai.ChatCompletionsModelGateway;
import com.mojie.agent.run.infra.JdbcRunAdmission;
import com.mojie.agent.run.infra.JdbcRunQuery;
import com.mojie.agent.run.infra.JdbcRunLifecycle;
import com.mojie.agent.run.infra.JdbcRunTerminalStore;
import com.mojie.agent.loop.LoopOutcome;
import com.mojie.agent.tool.AgentTool;
import com.mojie.agent.tool.ContentTools;
import com.mojie.agent.tool.ToolContext;
import com.mojie.agent.tool.infra.JdbcContentRepository;
import com.mojie.agent.trace.infra.JdbcLoopTrace;
import com.mojie.content.JdbcContentService;
import com.mojie.proposal.infra.JdbcEditProposalService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import com.sun.net.httpserver.HttpServer;
import java.sql.*;
import java.time.Clock;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RunSchedulerIT {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test void fullConsistencyReviewPagesEvidenceAndPublishesOnlyAProposal() throws Exception {
        withDatabase(dataSource -> {
            Flyway.configure().dataSource(dataSource).load().migrate();
            JdbcContentService content = new JdbcContentService(dataSource);
            String project = (String) content.createProject("owner", "林舟演示").get("id");
            String prior = (String) content.createChapter("owner", project, "河边").get("id");
            content.saveChapter("owner", prior, "林舟不会游泳。\n林舟再次说他不会游泳。", 1);
            String target = (String) content.createChapter("owner", project, "深水").get("id");
            String original = "林舟潜入深水，独自游到对岸。";
            content.saveChapter("owner", target, original, 1);
            String firstSetting = (String) content.createSetting("owner", project, "CHARACTER", "林舟", "林舟不会游泳。").get("id");
            String secondSetting = (String) content.createSetting("owner", project, "CHARACTER", "向导", "向导提醒林舟别游泳。").get("id");
            content.createSetting("owner", project, "WORLD", "河流", "雨后水位上涨。");
            int[] turn = {0};
            ModelGateway scripted = request -> {
                int index = ++turn[0];
                if (index == 1) return calls("review-1",
                        tool("read-target", "read_chapter", Map.of("chapterId", target)),
                        tool("list-chapters", "list_chapters", Map.of()),
                        tool("list-settings-first", "list_settings", Map.of("type", "CHARACTER", "limit", 1)));
                if (index == 2) {
                    assertEquals(original, data(request, "read-target").get("content"));
                    assertFalse(data(request, "list-chapters").toString().contains(original));
                    assertFalse(data(request, "list-settings-first").toString().contains("不会游泳"));
                    return calls("review-2",
                            tool("list-settings-next", "list_settings", Map.of("type", "CHARACTER", "limit", 1,
                                    "cursor", data(request, "list-settings-first").get("nextCursor"))),
                            tool("search-settings-first", "search_settings", Map.of("keyword", "游泳", "limit", 1)));
                }
                if (index == 3) {
                    assertEquals(false, data(request, "list-settings-next").get("hasMore"));
                    assertEquals(true, data(request, "search-settings-first").get("hasMore"));
                    assertFalse(((Map<?,?>) ((List<?>) data(request, "search-settings-first").get("items"))
                            .getFirst()).containsKey("content"));
                    return calls("review-3",
                            tool("search-settings-next", "search_settings", Map.of("keyword", "游泳", "limit", 1,
                                    "cursor", data(request, "search-settings-first").get("nextCursor"))),
                            tool("read-settings", "read_settings", Map.of("settingIds", List.of(firstSetting, secondSetting))),
                            tool("search-chapters-first", "search_chapters", Map.of("keyword", "游泳", "limit", 1)));
                }
                if (index == 4) {
                    assertEquals(false, data(request, "search-settings-next").get("hasMore"));
                    assertTrue(data(request, "read-settings").toString().contains("不会游泳"));
                    assertEquals(true, data(request, "search-chapters-first").get("hasMore"));
                    return calls("review-4",
                            tool("search-chapters-next", "search_chapters", Map.of("keyword", "游泳", "limit", 1,
                                    "cursor", data(request, "search-chapters-first").get("nextCursor"))),
                            tool("read-prior", "read_chapter", Map.of("chapterId", prior)),
                            tool("candidate", "propose_edit", Map.of("proposedContent", "林舟借助绳索渡过深水。",
                                    "reason", "人物设定明确不会游泳，前文也重复说明。")));
                }
                assertEquals(5, index);
                assertEquals(false, data(request, "search-chapters-next").get("hasMore"));
                assertTrue(data(request, "read-prior").toString().contains("不会游泳"));
                assertEquals(true, request.messages().getLast().replies().stream()
                        .filter(reply -> reply.callId().equals("candidate")).findFirst().orElseThrow().result().get("success"));
                return new Response("建议补充过河方式，以保持人物设定和前文一致。", List.of(),
                        FinishReason.FINAL, "review-5", "end_turn", null);
            };
            try (RunScheduler scheduler = scheduler(dataSource, scripted)) {
                scheduler.start();
                String runId = scheduler.submit(new JdbcRunAdmission.Request("owner", project, target, 2,
                        "检查第三章与人物设定及前文是否矛盾", UUID.randomUUID().toString())).runId();
                await(() -> "SUCCEEDED".equals(status(dataSource, runId)));
                Map<String,Object> detail = new JdbcRunQuery(dataSource).detail("owner", runId);
                assertEquals(5, detail.get("modelCallCount"));
                assertEquals(11, detail.get("toolCallCount"));
                List<?> steps = (List<?>) detail.get("steps");
                assertEquals(List.of("read_chapter", "list_chapters", "list_settings", "list_settings", "search_settings",
                        "search_settings", "read_settings", "search_chapters", "search_chapters",
                        "read_chapter", "propose_edit"),
                        steps.stream().map(item -> ((Map<?,?>) item).get("toolName")).toList());
                assertNotNull(detail.get("proposalId"));
                Map<String,Object> proposal = new JdbcRunQuery(dataSource).proposal("owner", detail.get("proposalId").toString());
                assertEquals("PENDING", proposal.get("status"));
                assertEquals(original, content.chapter("owner", target).get("content"));
                assertEquals(2, content.chapter("owner", target).get("version"));
                List<?> sources = (List<?>) detail.get("sources");
                assertTrue(sources.stream().anyMatch(item -> "FULL_CHAPTER".equals(((Map<?,?>) item).get("kind"))
                        && target.equals(((Map<?,?>) item).get("chapterId"))));
                assertTrue(sources.stream().anyMatch(item -> "FULL_CHAPTER".equals(((Map<?,?>) item).get("kind"))
                        && prior.equals(((Map<?,?>) item).get("chapterId"))));
                assertEquals(2, sources.stream().filter(item -> "FULL_SETTING".equals(((Map<?,?>) item).get("kind"))).count());
                assertTrue(sources.stream().anyMatch(item -> "SEARCH_SNIPPET".equals(((Map<?,?>) item).get("kind"))));
                assertTrue(sources.stream().allMatch(item -> Boolean.TRUE.equals(((Map<?,?>) item).get("providedToModel"))));
            }
        });
    }

    @Test void editsBetweenReadsAndSearchPagesKeepBaseFirstReadAndViewVersions() throws Exception {
        withDatabase(source -> {
            Flyway.configure().dataSource(source).load().migrate();
            JdbcContentService content = new JdbcContentService(source);
            String project = (String) content.createProject("owner", "version-view").get("id");
            String prior = (String) content.createChapter("owner", project, "prior").get("id");
            String second = (String) content.createChapter("owner", project, "second").get("id");
            String target = (String) content.createChapter("owner", project, "target").get("id");
            String priorOld = "needle prior old", secondOld = "needle second old", targetOld = "needle target old";
            content.saveChapter("owner", prior, priorOld, 1);
            content.saveChapter("owner", second, secondOld, 1);
            content.saveChapter("owner", target, targetOld, 1);
            AtomicInteger turn = new AtomicInteger();
            String[] pinnedView = new String[1];
            ModelGateway scripted = request -> switch (turn.incrementAndGet()) {
                case 1 -> calls("read-before-edit",
                        tool("target-first", "read_chapter", Map.of("chapterId", target)),
                        tool("prior-first", "read_chapter", Map.of("chapterId", prior)));
                case 2 -> {
                    assertEquals(targetOld, data(request, "target-first").get("content"));
                    assertEquals(priorOld, data(request, "prior-first").get("content"));
                    content.saveChapter("owner", target, "changed target", 2);
                    content.saveChapter("owner", prior, "changed prior", 2);
                    yield calls("search-after-edit", tool("page-one", "search_chapters",
                            Map.of("keyword", "needle", "scope", "ALL_CHAPTERS", "limit", 1)));
                }
                case 3 -> {
                    Map<String,Object> firstPage = data(request, "page-one");
                    List<?> matches = (List<?>) firstPage.get("matches");
                    assertEquals(1, matches.size());
                    assertEquals(prior, ((Map<?,?>) matches.getFirst()).get("chapterId"));
                    assertEquals(2, ((Number) ((Map<?,?>) matches.getFirst()).get("version")).intValue());
                    assertEquals(true, firstPage.get("hasMore"));
                    pinnedView[0] = (String) firstPage.get("viewId");
                    assertNotNull(pinnedView[0]);
                    content.saveChapter("owner", second, "changed second", 2);
                    yield calls("page-and-read-after-edit",
                            tool("page-two", "search_chapters", Map.of("keyword", "needle", "scope", "ALL_CHAPTERS",
                                    "limit", 2, "cursor", firstPage.get("nextCursor"))),
                            tool("prior-again", "read_chapter", Map.of("chapterId", prior)),
                            tool("second-after-view", "read_chapter", Map.of("chapterId", second)),
                            tool("target-again", "read_chapter", Map.of("chapterId", target)));
                }
                case 4 -> {
                    Map<String,Object> secondPage = data(request, "page-two");
                    assertEquals(pinnedView[0], secondPage.get("viewId"));
                    List<?> matches = (List<?>) secondPage.get("matches");
                    assertEquals(List.of(second, target), matches.stream()
                            .map(item -> ((Map<?,?>) item).get("chapterId")).toList());
                    assertTrue(matches.stream().allMatch(item ->
                            ((Number) ((Map<?,?>) item).get("version")).intValue() == 2));
                    assertEquals(false, secondPage.get("hasMore"));
                    assertEquals(priorOld, data(request, "prior-again").get("content"));
                    assertEquals(secondOld, data(request, "second-after-view").get("content"));
                    assertEquals(targetOld, data(request, "target-again").get("content"));
                    yield new Response("检查完成。", List.of(), FinishReason.FINAL, "final", "end_turn", null);
                }
                default -> throw new AssertionError("Unexpected model turn");
            };
            try (RunScheduler scheduler = scheduler(source, scripted)) {
                scheduler.start();
                String runId = scheduler.submit(new JdbcRunAdmission.Request("owner", project, target, 2,
                        "check version view", UUID.randomUUID().toString())).runId();
                await(() -> "SUCCEEDED".equals(status(source, runId)));
                assertEquals(4, turn.get());
                Map<String,Object> detail = new JdbcRunQuery(source).detail("owner", runId);
                List<?> sources = (List<?>) detail.get("sources");
                assertTrue(sources.stream().filter(item -> ((Map<?,?>) item).get("chapterId") != null)
                        .allMatch(item -> ((Number) ((Map<?,?>) item).get("chapterVersion")).intValue() == 2));
                assertEquals(3, count(source, "SELECT count(*) FROM tb_agent_run_chapter_view WHERE run_id='"
                        + runId + "' AND version=2"));
                assertEquals("changed target", content.chapter("owner", target).get("content"));
                assertEquals("changed prior", content.chapter("owner", prior).get("content"));
                assertEquals("changed second", content.chapter("owner", second).get("content"));
            }
        });
    }

    @Test void malformedChatToolArgumentsAreReturnedAndCorrectedAcrossHttpAndPostgres() throws Exception {
        withDatabase(source -> {
            Fixture fixture = fixtureAfterMigration(source, "chat-argument-correction");
            AtomicInteger calls = new AtomicInteger();
            HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            provider.createContext("/v1/chat/completions", exchange -> {
                int index = calls.incrementAndGet();
                JsonMapper mapper = JsonMapper.builder().build();
                var body = mapper.readTree(exchange.getRequestBody().readAllBytes());
                if (index == 2) {
                    var messages = body.path("messages");
                    var reply = messages.get(messages.size() - 1);
                    assertEquals("tool", reply.path("role").asText());
                    assertEquals("bad-arguments", reply.path("tool_call_id").asText());
                    assertEquals("VALIDATION_ERROR", mapper.readTree(reply.path("content").asText())
                            .path("error").path("code").asText());
                    assertEquals("not-json", messages.get(messages.size() - 2).path("tool_calls")
                            .get(0).path("function").path("arguments").asText());
                }
                String response = switch (index) {
                    case 1 -> chatToolResponse("bad-arguments", "not-json");
                    case 2 -> chatToolResponse("corrected-read", "{\"chapterId\":\"" + fixture.chapter() + "\"}");
                    case 3 -> """
                            {"id":"final","choices":[{"message":{"role":"assistant","content":"已读取目标章并完成检查。"},
                             "finish_reason":"stop"}]}
                            """;
                    default -> throw new AssertionError("Unexpected model call " + index);
                };
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            provider.start();
            try (ChatCompletionsModelGateway gateway = new ChatCompletionsModelGateway("local-test-key",
                    "http://127.0.0.1:" + provider.getAddress().getPort());
                 RunScheduler scheduler = new RunScheduler(source, gateway, Clock.systemUTC(), "system", "scripted", 100,
                         "0123456789abcdef0123456789abcdef".getBytes(), "/v1/chat/completions")) {
                scheduler.start();
                String runId = scheduler.submit(fixture.request("correct malformed arguments")).runId();
                await(() -> "SUCCEEDED".equals(status(source, runId)));
                var detail = new JdbcRunQuery(source).detail("owner", runId);
                assertEquals(3, calls.get());
                assertEquals(3, detail.get("modelCallCount"));
                assertEquals(2, detail.get("toolCallCount"));
                List<?> steps = (List<?>) detail.get("steps");
                assertEquals("VALIDATION_ERROR", ((Map<?, ?>) steps.get(0)).get("errorCode"));
                assertEquals("SUCCEEDED", ((Map<?, ?>) steps.get(1)).get("status"));
                assertNull(detail.get("proposalId"));
            } finally { provider.stop(0); }
        });
    }

    @Test void modelFailuresStayDistinctAndNeverPublishAProposal() throws Exception {
        withDatabase(source -> {
            Flyway.configure().dataSource(source).load().migrate();
            for (String code : List.of("MODEL_AUTH_ERROR", "MODEL_NETWORK_ERROR", "MODEL_TIMEOUT")) {
                Fixture fixture = fixture(source, code);
                AtomicInteger attempts = new AtomicInteger();
                ModelGateway failure = request -> {
                    attempts.incrementAndGet();
                    throw new ModelGateway.ModelException(code, "Simulated provider failure");
                };
                try (RunScheduler scheduler = scheduler(source, failure)) {
                    scheduler.start();
                    String runId = scheduler.submit(fixture.request("check")).runId();
                    await(() -> "FAILED".equals(status(source, runId)));
                    assertEquals(code, errorCode(source, runId));
                    assertEquals(1, attempts.get());
                    assertEquals(0, count(source, "SELECT count(*) FROM tb_edit_proposal WHERE run_id='" + runId + "'"));
                }
            }
            Fixture refusedFixture = fixture(source, "refused");
            AtomicInteger refusedCalls = new AtomicInteger();
            ModelGateway refused = request -> {
                refusedCalls.incrementAndGet();
                return new Response("", List.of(), FinishReason.REFUSED, "refused", "refusal", null);
            };
            try (RunScheduler scheduler = scheduler(source, refused)) {
                scheduler.start();
                String runId = scheduler.submit(refusedFixture.request("check")).runId();
                await(() -> "FAILED".equals(status(source, runId)));
                assertEquals("MODEL_REFUSED", errorCode(source, runId));
                assertEquals(1, refusedCalls.get());
                assertEquals(0, count(source, "SELECT count(*) FROM tb_edit_proposal WHERE run_id='" + runId + "'"));
            }
            Fixture blankFixture = fixture(source, "blank-analysis");
            AtomicInteger blankCalls = new AtomicInteger();
            ModelGateway blank = request -> blankCalls.incrementAndGet() == 1
                    ? calls("read", tool("target", "read_chapter", Map.of("chapterId", blankFixture.chapter())))
                    : new Response("  ", List.of(), FinishReason.FINAL, "blank", "end_turn", null);
            try (RunScheduler scheduler = scheduler(source, blank)) {
                scheduler.start();
                String runId = scheduler.submit(blankFixture.request("check")).runId();
                await(() -> "FAILED".equals(status(source, runId)));
                assertEquals("INVALID_MODEL_RESPONSE", errorCode(source, runId));
                assertEquals(2, blankCalls.get());
                assertEquals(0, count(source, "SELECT count(*) FROM tb_edit_proposal WHERE run_id='" + runId + "'"));
            }
            Fixture unexpectedFixture = fixture(source, "unexpected-provider-failure");
            ModelGateway unexpected = request -> { throw new IllegalStateException("Bearer sensitive-canary"); };
            try (RunScheduler scheduler = scheduler(source, unexpected)) {
                scheduler.start();
                String runId = scheduler.submit(unexpectedFixture.request("check")).runId();
                await(() -> "FAILED".equals(status(source, runId)));
                JdbcRunQuery query = new JdbcRunQuery(source);
                assertEquals("MODEL_ERROR", errorCode(source, runId));
                assertFalse(query.detail("owner", runId).toString().contains("sensitive-canary"));
                assertFalse(query.trace("owner", runId).toString().contains("sensitive-canary"));
                assertFalse(query.events("owner", runId, 0, 100).toString().contains("sensitive-canary"));
            }
        });
    }

    @Test void developerTraceRedactsOpaqueReasoningWhileContinuationKeepsIt() throws Exception {
        withDatabase(source -> {
            Flyway.configure().dataSource(source).load().migrate();
            Fixture fixture = fixture(source, "opaque-trace");
            String hidden = "hidden-phase-detail";
            AtomicInteger turns = new AtomicInteger();
            ModelGateway scripted = request -> {
                if (turns.incrementAndGet() == 1) return new Response(List.of(
                        new OpaquePart(JSON.valueToTree(Map.of("type", "chat_reasoning",
                                "reasoning_content", hidden))),
                        tool("read-target", "read_chapter", Map.of("chapterId", fixture.chapter()))),
                        FinishReason.TOOL_CALLS, "first", "tool_calls", null);
                assertEquals(hidden, ((OpaquePart) request.messages().get(1).content().getFirst())
                        .providerBlock().path("reasoning_content").asText());
                assertEquals("body", data(request, "read-target").get("content"));
                return new Response("检查完成。", List.of(), FinishReason.FINAL, "second", "stop", null);
            };
            try (RunScheduler scheduler = scheduler(source, scripted)) {
                scheduler.start();
                String runId = scheduler.submit(fixture.request("check")).runId();
                await(() -> "SUCCEEDED".equals(status(source, runId)));
                assertEquals(2, turns.get());
                assertEquals(1, count(source, "SELECT count(*) FROM tb_agent_message WHERE run_id='" + runId
                        + "' AND content::text LIKE '%" + hidden + "%'"));
                JdbcRunQuery query = new JdbcRunQuery(source);
                Map<String,Object> trace = query.trace("owner", runId);
                assertFalse(trace.toString().contains(hidden));
                List<?> messages = (List<?>) trace.get("messages");
                Map<?,?> assistant = (Map<?,?>) messages.get(1);
                var block = ((com.fasterxml.jackson.databind.JsonNode) assistant.get("content")).get(0).path("block");
                assertEquals("chat_reasoning", block.path("type").asText());
                assertTrue(block.path("redacted").asBoolean());
                assertFalse(query.detail("owner", runId).toString().contains(hidden));
                assertFalse(query.events("owner", runId, 0, 100).toString().contains(hidden));
            }
        });
    }

    private static String chatToolResponse(String callId, String arguments) throws java.io.IOException {
        return "{\"id\":\"" + callId + "\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"" + callId + "\",\"type\":\"function\",\"function\":{"
                + "\"name\":\"read_chapter\",\"arguments\":" + JSON.writeValueAsString(arguments) + "}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}";
    }

    private static Fixture fixtureAfterMigration(PGSimpleDataSource source, String name) throws SQLException {
        Flyway.configure().dataSource(source).load().migrate();
        return fixture(source, name);
    }

    private static ToolCall tool(String id, String name, Map<String,?> args) {
        return new ToolCall(id, name, JSON.valueToTree(args));
    }
    private static Response calls(String id, ToolCall... tools) {
        return new Response("", List.of(tools), FinishReason.TOOL_CALLS, id, "tool_use", null);
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> data(ModelGateway.Request request, String callId) {
        ToolReply reply = request.messages().getLast().replies().stream()
                .filter(item -> item.callId().equals(callId)).findFirst().orElseThrow();
        assertEquals(true, reply.result().get("success"), callId + " failed: " + reply.result());
        return (Map<String,Object>) reply.result().get("data");
    }

    @Test void scriptedRunCompletesAndReleasesCapacity() throws Exception {
        withDatabase(dataSource -> {
            ModelGateway scripted = request -> responseFor(request);
            try (RunScheduler scheduler = scheduler(dataSource, scripted)) {
                scheduler.start();
                Fixture fixture = fixture(dataSource, "simple");
                JdbcRunAdmission.Request request = fixture.request("check");
                JdbcRunAdmission.Receipt receipt = scheduler.submit(request);
                assertTrue(receipt.created());
                assertEquals(receipt.runId(), scheduler.submit(request).runId());
                await(() -> "SUCCEEDED".equals(status(dataSource, receipt.runId())) && scheduler.occupiedCapacity() == 0);
                assertEquals(2, count(dataSource, "SELECT model_call_count FROM tb_agent_run WHERE id='" + receipt.runId() + "'"));
            }
        });
    }

    @Test void configuredContextWindowFailsBeforeFirstModelRequestAndIsSnapshotted() throws Exception {
        withDatabase(source -> {
            AtomicInteger modelCalls = new AtomicInteger();
            ModelGateway scripted = request -> {
                modelCalls.incrementAndGet();
                return responseFor(request);
            };
            try (RunScheduler scheduler = new RunScheduler(source, scripted, Clock.systemUTC(), "system", "scripted",
                    100, "0123456789abcdef0123456789abcdef".getBytes(), "/v1/messages", 3_000)) {
                scheduler.start();
                Fixture fixture = fixture(source, "short-model-window");
                String runId = scheduler.submit(fixture.request("check")).runId();
                await(() -> "FAILED".equals(status(source, runId)));
                assertEquals("LIMIT_EXCEEDED", errorCode(source, runId));
                assertEquals(0, modelCalls.get());
                assertEquals(0, count(source, "SELECT model_call_count FROM tb_agent_run WHERE id='" + runId + "'"));
                assertEquals(3_000, count(source, "SELECT (config_snapshot->>'contextWindowTokens')::int "
                        + "FROM tb_agent_run WHERE id='" + runId + "'"));
            }
        });
    }

    @Test void databaseRecoverySettlesAnExitedWorkerWithoutReplayingTheModel() throws Exception {
        withDatabase(source -> {
            AtomicBoolean unavailable = new AtomicBoolean();
            DataSource controlled = failConnectionsWhen(source, unavailable);
            CountDownLatch modelEntered = new CountDownLatch(1), modelRelease = new CountDownLatch(1);
            AtomicInteger modelCalls = new AtomicInteger();
            ModelGateway blocked = request -> {
                modelCalls.incrementAndGet();
                modelEntered.countDown();
                try { assertTrue(modelRelease.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                return responseFor(request);
            };
            try (RunScheduler scheduler = new RunScheduler(controlled, blocked, Clock.systemUTC(), "system", "scripted",
                    100, "0123456789abcdef0123456789abcdef".getBytes())) {
                scheduler.start();
                Fixture fixture = fixture(source, "recovery");
                String runId = scheduler.submit(fixture.request("recover")).runId();
                assertTrue(modelEntered.await(5, TimeUnit.SECONDS));
                unavailable.set(true);
                modelRelease.countDown();
                await(() -> scheduler.occupiedCapacity() == 0);
                assertEquals("RUNNING", status(source, runId));
                assertFalse(scheduler.healthy());

                unavailable.set(false);
                await(() -> "FAILED".equals(status(source, runId)) && scheduler.healthy());
                assertEquals("PERSISTENCE_ERROR", errorCode(source, runId));
                assertEquals(1, modelCalls.get());
                assertEquals(0, count(source, "SELECT count(*) FROM tb_edit_proposal WHERE run_id='" + runId + "'"));
                assertEquals(0, count(source, "SELECT count(*) FROM tb_project WHERE id='" + fixture.project()
                        + "' AND active_run_id IS NOT NULL"));
                assertEquals(1, count(source, "SELECT count(*) FROM tb_agent_event WHERE run_id='" + runId
                        + "' AND type='run.failed'"));
            } finally { modelRelease.countDown(); unavailable.set(false); }
        });
    }

    @Test void toolFinishEventFailureRollsBackStepReplyAndEvidence() throws Exception {
        withDatabase(source -> {
            Flyway.configure().dataSource(source).load().migrate();
            Fixture fixture = fixture(source, "tool-transaction-failure");
            failAgentEventInsert(source, "tool.finished");
            AtomicInteger modelCalls = new AtomicInteger();
            ModelGateway scripted = request -> {
                modelCalls.incrementAndGet();
                return calls("read-turn", tool("read-target", "read_chapter",
                        Map.of("chapterId", fixture.chapter())));
            };
            try (RunScheduler scheduler = scheduler(source, scripted)) {
                scheduler.start();
                String runId = scheduler.submit(fixture.request("read")).runId();
                await(() -> "FAILED".equals(status(source, runId)));
                assertEquals("PERSISTENCE_ERROR", errorCode(source, runId));
                assertEquals(1, modelCalls.get());
                assertEquals(1, count(source, "SELECT count(*) FROM tb_agent_step WHERE run_id='" + runId
                        + "' AND status='ABORTED'"));
                assertEquals(0, count(source, "SELECT count(*) FROM tb_agent_step WHERE run_id='" + runId
                        + "' AND status='SUCCEEDED'"));
                assertEquals(0, count(source, "SELECT count(*) FROM tb_agent_message WHERE run_id='" + runId
                        + "' AND role='TOOL'"));
                assertEquals(0, count(source, "SELECT count(*) FROM tb_agent_context_item WHERE run_id='" + runId + "'"));
                assertEquals(0, count(source, "SELECT count(*) FROM tb_agent_event WHERE run_id='" + runId
                        + "' AND type='tool.finished'"));
                assertEquals(0, count(source, "SELECT tool_text_codepoints FROM tb_agent_run WHERE id='" + runId + "'"));
                assertEquals(0, count(source, "SELECT count(*) FROM tb_project WHERE id='" + fixture.project()
                        + "' AND active_run_id IS NOT NULL"));
            }
        });
    }

    @Test void successEventFailureRollsBackProposalStatusAndActivitySlot() throws Exception {
        withDatabase(source -> {
            Flyway.configure().dataSource(source).load().migrate();
            Fixture fixture = fixture(source, "success-transaction-failure");
            failAgentEventInsert(source, "run.succeeded");
            AtomicInteger modelCalls = new AtomicInteger();
            ModelGateway scripted = request -> {
                if (modelCalls.incrementAndGet() == 1) return calls("proposal-turn",
                        tool("read-target", "read_chapter", Map.of("chapterId", fixture.chapter())),
                        tool("candidate", "propose_edit", Map.of("proposedContent", "revised body", "reason", "fix")));
                return new Response("建议修改正文。", List.of(), FinishReason.FINAL, "final-turn", "end_turn", null);
            };
            try (RunScheduler scheduler = scheduler(source, scripted)) {
                scheduler.start();
                String runId = scheduler.submit(fixture.request("propose")).runId();
                await(() -> "FAILED".equals(status(source, runId)));
                assertEquals("PERSISTENCE_ERROR", errorCode(source, runId));
                assertEquals(2, modelCalls.get());
                assertEquals(2, count(source, "SELECT count(*) FROM tb_agent_step WHERE run_id='" + runId
                        + "' AND status='SUCCEEDED'"));
                assertEquals(0, count(source, "SELECT count(*) FROM tb_edit_proposal WHERE run_id='" + runId + "'"));
                assertEquals(0, count(source, "SELECT count(*) FROM tb_agent_event WHERE run_id='" + runId
                        + "' AND type='run.succeeded'"));
                assertEquals(1, count(source, "SELECT count(*) FROM tb_agent_event WHERE run_id='" + runId
                        + "' AND type='run.failed'"));
                assertEquals(0, count(source, "SELECT count(*) FROM tb_project WHERE id='" + fixture.project()
                        + "' AND active_run_id IS NOT NULL"));
            }
        });
    }

    @Test void cancelledAfterToolReplyPersistsExecutionButMarksSourceUnsent() throws Exception {
        withDatabase(source -> {
            Flyway.configure().dataSource(source).load().migrate();
            Fixture fixture = fixture(source, "unsent-source");
            Clock clock = Clock.systemUTC();
            JdbcRunTerminalStore terminal = new JdbcRunTerminalStore(source, clock);
            JdbcRunLifecycle lifecycle = new JdbcRunLifecycle(source, terminal);
            UUID processId = UUID.randomUUID();
            JdbcRunAdmission admission = new JdbcRunAdmission(source, clock, processId);
            String runId = admission.create(fixture.request("read then cancel"),
                    new JdbcRunAdmission.Config("system", "{\"tools\":[]}", "scripted", Map.of("maxOutputTokens", 100)))
                    .runId();
            assertTrue(lifecycle.start(fixture.project(), runId, processId));

            JdbcLoopTrace trace = new JdbcLoopTrace(source);
            ToolCall call = tool("read-target", "read_chapter", Map.of("chapterId", fixture.chapter()));
            trace.modelStarted(runId, 1, List.of(1L));
            long assistantSeq = trace.modelFinished(runId, 1, calls("tool-turn", call));
            assertEquals(2L, assistantSeq);
            String executionId = trace.toolStarted(runId, 1, call);
            ToolContext context = new ToolContext(runId, fixture.project(), fixture.chapter(), 1, "body");
            AgentTool read = ContentTools.all(new JdbcContentRepository(source), "0123456789abcdef".getBytes())
                    .stream().filter(item -> item.spec().name().equals("read_chapter")).findFirst().orElseThrow();
            AgentTool.ToolResult result = read.execute(call.arguments(), context);
            assertTrue(result.success());
            String wire = JSON.writeValueAsString(result.wire());
            long replySeq = trace.toolFinished(runId, 1, call, executionId, result, wire,
                    wire.codePointCount(0, wire.length()), true);
            assertEquals(3L, replySeq);

            assertEquals(new JdbcRunLifecycle.CancelResult("RUNNING", true),
                    lifecycle.requestCancel(fixture.project(), runId));
            assertEquals("CANCELLED", terminal.finish(fixture.project(), runId, new LoopOutcome.Cancelled()).status());
            Map<String,Object> detail = new JdbcRunQuery(source).detail("owner", runId);
            assertEquals("CANCELLED", detail.get("status"));
            assertEquals(1, count(source, "SELECT count(*) FROM tb_agent_step WHERE run_id='" + runId
                    + "' AND status='SUCCEEDED'"));
            assertEquals(1, count(source, "SELECT count(*) FROM tb_agent_message WHERE run_id='" + runId
                    + "' AND role='TOOL'"));
            List<?> sources = (List<?>) detail.get("sources");
            assertEquals(1, sources.size());
            assertEquals(false, ((Map<?,?>) sources.getFirst()).get("providedToModel"));
            assertNull(detail.get("proposalId"));
        });
    }

    @Test void queuedCancelReleasesPermitButRunningCancelWaitsForWorkerExit() throws Exception {
        withDatabase(dataSource -> {
            CountDownLatch entered = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            ModelGateway blocked = request -> {
                if (request.messages().size() == 1) {
                    entered.countDown();
                    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (release.getCount() > 0 && System.nanoTime() < end) {
                        try { release.await(100, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException ignored) { /* Simulate a provider call that has not exited yet. */ }
                    }
                }
                return responseFor(request);
            };
            try (RunScheduler scheduler = scheduler(dataSource, blocked)) {
                scheduler.start();
                Fixture first = fixture(dataSource, "one");
                Fixture second = fixture(dataSource, "two");
                Fixture third = fixture(dataSource, "three");
                String firstRun = scheduler.submit(first.request("check 1")).runId();
                String secondRun = scheduler.submit(second.request("check 2")).runId();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                String queuedRun = scheduler.submit(third.request("check 3")).runId();
                assertEquals(3, scheduler.occupiedCapacity());
                assertEquals("CANCELLED", scheduler.cancel(third.project(), queuedRun).status());
                await(() -> scheduler.occupiedCapacity() == 2);
                assertEquals("RUNNING", scheduler.cancel(first.project(), firstRun).status());
                assertEquals(2, scheduler.occupiedCapacity());
                release.countDown();
                await(() -> "CANCELLED".equals(status(dataSource, firstRun))
                        && "SUCCEEDED".equals(status(dataSource, secondRun)) && scheduler.occupiedCapacity() == 0);
                assertEquals("CANCELLED", status(dataSource, queuedRun));
            } finally { release.countDown(); }
        });
    }

    @Test void fullQueueRejectsCleanlyAndQueuedCancellationRestoresCapacity() throws Exception {
        withDatabase(dataSource -> {
            CountDownLatch running = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            ModelGateway blocked = request -> {
                if (request.messages().size() == 1) {
                    running.countDown();
                    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                    while (release.getCount() > 0 && System.nanoTime() < end) {
                        try { release.await(100, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException ignored) { /* Keep the physical workers occupied. */ }
                    }
                }
                return responseFor(request);
            };
            try (RunScheduler scheduler = scheduler(dataSource, blocked)) {
                scheduler.start();
                List<Fixture> fixtures = new ArrayList<>();
                for (int i = 0; i < 11; i++) fixtures.add(fixture(dataSource, "capacity-" + i));
                List<String> accepted = new ArrayList<>();
                accepted.add(scheduler.submit(fixtures.get(0).request("check 0")).runId());
                accepted.add(scheduler.submit(fixtures.get(1).request("check 1")).runId());
                assertTrue(running.await(5, TimeUnit.SECONDS));
                for (int i = 2; i < 10; i++)
                    accepted.add(scheduler.submit(fixtures.get(i).request("check " + i)).runId());
                assertEquals(10, scheduler.occupiedCapacity());
                assertEquals(2, scheduler.runningThreads());
                JdbcRunAdmission.AdmissionException full = assertThrows(JdbcRunAdmission.AdmissionException.class,
                        () -> scheduler.submit(fixtures.get(10).request("overflow")));
                assertEquals("RUN_CAPACITY_EXCEEDED", full.code());
                assertEquals(10, count(dataSource, "SELECT count(*) FROM tb_agent_run"));
                JdbcContentService.ContentException busy = assertThrows(JdbcContentService.ContentException.class,
                        () -> new JdbcContentService(dataSource).deleteProject("owner", fixtures.get(2).project()));
                assertEquals("RESOURCE_BUSY", busy.code());
                assertEquals(10, scheduler.occupiedCapacity());

                String cancelled = accepted.get(9);
                assertEquals("CANCELLED", scheduler.cancel(fixtures.get(9).project(), cancelled).status());
                await(() -> scheduler.occupiedCapacity() == 9);
                String replacement = scheduler.submit(fixtures.get(10).request("replacement")).runId();
                assertEquals("QUEUED", status(dataSource, replacement));
                assertEquals(10, scheduler.occupiedCapacity());
                release.countDown();
                await(() -> accepted.subList(0, 9).stream().allMatch(id -> "SUCCEEDED".equals(status(dataSource, id)))
                        && "SUCCEEDED".equals(status(dataSource, replacement))
                        && scheduler.occupiedCapacity() == 0);
                assertEquals("CANCELLED", status(dataSource, cancelled));
            } finally { release.countDown(); }
        });
    }

    @Test void concurrentSubmissionsForOneProjectAdmitOnlyOneRun() throws Exception {
        withDatabase(dataSource -> {
            CountDownLatch release = new CountDownLatch(1);
            ModelGateway blocked = request -> {
                if (request.messages().size() == 1) {
                    try { release.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                return responseFor(request);
            };
            try (RunScheduler scheduler = scheduler(dataSource, blocked)) {
                scheduler.start();
                Fixture fixture = fixture(dataSource, "one-project");
                CountDownLatch start = new CountDownLatch(1);
                ExecutorService submitters = Executors.newFixedThreadPool(2);
                try {
                    List<Future<Object>> results = new ArrayList<>();
                    for (int i = 0; i < 2; i++) {
                        int number = i;
                        results.add(submitters.submit(() -> {
                            start.await();
                            try { return scheduler.submit(fixture.request("concurrent " + number)); }
                            catch (JdbcRunAdmission.AdmissionException rejected) { return rejected.code(); }
                        }));
                    }
                    start.countDown();
                    List<Object> outcomes = new ArrayList<>();
                    for (Future<Object> result : results) outcomes.add(result.get(5, TimeUnit.SECONDS));
                    List<JdbcRunAdmission.Receipt> accepted = outcomes.stream()
                            .filter(JdbcRunAdmission.Receipt.class::isInstance)
                            .map(JdbcRunAdmission.Receipt.class::cast).toList();
                    assertEquals(1, accepted.size());
                    assertTrue(accepted.getFirst().created());
                    assertEquals(1, outcomes.stream().filter("RESOURCE_BUSY"::equals).count());
                    assertEquals(1, count(dataSource, "SELECT count(*) FROM tb_agent_run"));
                    assertEquals(1, scheduler.occupiedCapacity());
                    release.countDown();
                    await(() -> "SUCCEEDED".equals(status(dataSource, accepted.getFirst().runId()))
                            && scheduler.occupiedCapacity() == 0);
                } finally { release.countDown(); submitters.shutdownNow(); }
            } finally { release.countDown(); }
        });
    }

    @Test void timeoutSweepEndsDatabaseRunWhileBlockedWorkerStillOwnsCapacity() throws Exception {
        withDatabase(dataSource -> {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ModelGateway blocked = request -> {
                if (request.messages().size() == 1) {
                    entered.countDown();
                    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (release.getCount() > 0 && System.nanoTime() < end) {
                        try { release.await(100, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException ignored) { }
                    }
                }
                return responseFor(request);
            };
            try (RunScheduler scheduler = scheduler(dataSource, blocked)) {
                scheduler.start();
                Fixture fixture = fixture(dataSource, "timeout");
                String run = scheduler.submit(fixture.request("check")).runId();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                    sql.execute("UPDATE tb_agent_run SET deadline_at = now() - interval '1 second' WHERE id='" + run + "'");
                }
                await(() -> "FAILED".equals(status(dataSource, run)));
                assertEquals(1, scheduler.occupiedCapacity());
                release.countDown();
                await(() -> scheduler.occupiedCapacity() == 0);
                assertEquals("RUN_TIMEOUT", errorCode(dataSource, run));
            } finally { release.countDown(); }
        });
    }

    @Test void lateCancelledWorkerCannotClearNextRunForSameProject() throws Exception {
        withDatabase(dataSource -> {
            Flyway.configure().dataSource(dataSource).load().migrate();
            CountDownLatch enteredA = new CountDownLatch(1);
            CountDownLatch enteredB = new CountDownLatch(1);
            CountDownLatch releaseA = new CountDownLatch(1);
            CountDownLatch releaseB = new CountDownLatch(1);
            Fixture fixture = fixture(dataSource, "same-project");
            String nextChapter = (String) new JdbcContentService(dataSource)
                    .createChapter("owner", fixture.project(), "下一章").get("id");
            ModelGateway delayed = request -> {
                if (request.messages().size() == 1) {
                    String instruction = request.messages().getFirst().content().stream()
                            .filter(TextPart.class::isInstance).map(TextPart.class::cast)
                            .findFirst().orElseThrow().text();
                    boolean oldRun = instruction.endsWith(fixture.chapter());
                    (oldRun ? enteredA : enteredB).countDown();
                    CountDownLatch gate = oldRun ? releaseA : releaseB;
                    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                    while (gate.getCount() > 0 && System.nanoTime() < end) {
                        try { gate.await(100, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException ignored) { /* Provider does not promptly exit. */ }
                    }
                }
                return responseFor(request);
            };
            try (RunScheduler scheduler = scheduler(dataSource, delayed)) {
                scheduler.start();
                String runA = scheduler.submit(fixture.request("check A")).runId();
                assertTrue(enteredA.await(5, TimeUnit.SECONDS));
                assertEquals("RUNNING", scheduler.cancel(fixture.project(), runA).status());
                try (Connection connection = dataSource.getConnection(); PreparedStatement sql = connection.prepareStatement(
                        "UPDATE tb_agent_run SET deadline_at = now() - interval '1 second' WHERE id = ?::uuid")) {
                    sql.setString(1, runA); sql.executeUpdate();
                }
                await(() -> "CANCELLED".equals(status(dataSource, runA)));
                assertEquals(1, scheduler.occupiedCapacity());

                String runB = scheduler.submit(new JdbcRunAdmission.Request("owner", fixture.project(), nextChapter, 1,
                        "check B " + nextChapter, UUID.randomUUID().toString())).runId();
                assertTrue(enteredB.await(5, TimeUnit.SECONDS));
                assertEquals(2, scheduler.occupiedCapacity());
                assertEquals(1, count(dataSource, "SELECT count(*) FROM tb_project WHERE id='" + fixture.project()
                        + "' AND active_run_id='" + runB + "'"));

                assertEquals("CANCELLED", scheduler.cancel(fixture.project(), runA).status());
                releaseA.countDown();
                await(() -> scheduler.occupiedCapacity() == 1);
                assertEquals("CANCELLED", status(dataSource, runA));
                assertEquals("RUNNING", status(dataSource, runB));
                assertEquals(1, count(dataSource, "SELECT count(*) FROM tb_project WHERE id='" + fixture.project()
                        + "' AND active_run_id='" + runB + "'"));
                releaseB.countDown();
                await(() -> "SUCCEEDED".equals(status(dataSource, runB)) && scheduler.occupiedCapacity() == 0);
                assertEquals(0, count(dataSource, "SELECT count(*) FROM tb_project WHERE id='" + fixture.project()
                        + "' AND active_run_id IS NOT NULL"));
            } finally { releaseA.countDown(); releaseB.countDown(); }
        });
    }

    @Test void concurrentRunsMayReuseProviderAndToolCallIdsWithoutMixingResults() throws Exception {
        withDatabase(dataSource -> {
            Flyway.configure().dataSource(dataSource).load().migrate();
            JdbcContentService content = new JdbcContentService(dataSource);
            Fixture a = fixture(dataSource, "first-project");
            Fixture b = fixture(dataSource, "second-project");
            String firstBody = "甲项目独有的河岸线索";
            String secondBody = "乙项目独有的山谷线索";
            content.saveChapter("owner", a.chapter(), firstBody, 1);
            content.saveChapter("owner", b.chapter(), secondBody, 1);
            CountDownLatch bothStarted = new CountDownLatch(2);
            ModelGateway sharedIds = request -> {
                String instruction = request.messages().getFirst().content().stream()
                        .filter(TextPart.class::isInstance).map(TextPart.class::cast)
                        .findFirst().orElseThrow().text();
                boolean firstRun = instruction.endsWith(a.chapter());
                String chapterId = firstRun ? a.chapter() : b.chapter();
                String ownBody = firstRun ? firstBody : secondBody;
                String otherBody = firstRun ? secondBody : firstBody;
                if (request.messages().size() == 1) {
                    bothStarted.countDown();
                    try {
                        if (!bothStarted.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Runs did not overlap");
                    } catch (InterruptedException e) { throw new IllegalStateException(e); }
                    return new Response("", List.of(tool("shared-tool-call", "read_chapter",
                            Map.of("chapterId", chapterId))), FinishReason.TOOL_CALLS,
                            "shared-provider-request", "tool_use", null);
                }
                String result = data(request, "shared-tool-call").toString();
                if (!result.contains(ownBody) || result.contains(otherBody))
                    throw new IllegalStateException("A tool result crossed run boundaries");
                return new Response("已按本作品正文完成分析", List.of(), FinishReason.FINAL,
                        "shared-provider-final", "end_turn", null);
            };
            try (RunScheduler scheduler = scheduler(dataSource, sharedIds)) {
                scheduler.start();
                String runA = scheduler.submit(new JdbcRunAdmission.Request("owner", a.project(), a.chapter(), 2,
                        "check A " + a.chapter(), UUID.randomUUID().toString())).runId();
                String runB = scheduler.submit(new JdbcRunAdmission.Request("owner", b.project(), b.chapter(), 2,
                        "check B " + b.chapter(), UUID.randomUUID().toString())).runId();
                await(() -> "SUCCEEDED".equals(status(dataSource, runA))
                        && "SUCCEEDED".equals(status(dataSource, runB)) && scheduler.occupiedCapacity() == 0);
                JdbcRunQuery query = new JdbcRunQuery(dataSource);
                for (String runId : List.of(runA, runB)) {
                    boolean firstRun = runId.equals(runA);
                    String ownBody = firstRun ? firstBody : secondBody;
                    String otherBody = firstRun ? secondBody : firstBody;
                    String chapterId = firstRun ? a.chapter() : b.chapter();
                    Map<String,Object> trace = query.trace("owner", runId);
                    List<?> calls = (List<?>) trace.get("modelCalls");
                    assertEquals(List.of("shared-provider-request", "shared-provider-final"), calls.stream()
                            .map(item -> ((Map<?,?>) item).get("providerRequestId")).toList());
                    List<?> steps = (List<?>) trace.get("steps");
                    assertEquals(1, steps.size());
                    Map<?,?> step = (Map<?,?>) steps.getFirst();
                    assertEquals("shared-tool-call", step.get("toolCallId"));
                    assertTrue(step.get("result").toString().contains(ownBody));
                    assertFalse(step.get("result").toString().contains(otherBody));
                    List<?> sources = (List<?>) trace.get("sources");
                    assertEquals(1, sources.size());
                    assertEquals(chapterId, ((Map<?,?>) sources.getFirst()).get("chapterId"));
                }
            }
        });
    }

    @Test void oversizedChapterScopeFailsWithoutPartialSearchAndCanFallBackToRead() throws Exception {
        withDatabase(dataSource -> {
            Flyway.configure().dataSource(dataSource).load().migrate();
            Fixture fixture = fixture(dataSource, "over-thousand-chapters");
            try (Connection connection = dataSource.getConnection(); PreparedStatement chapters = connection.prepareStatement("""
                    INSERT INTO tb_chapter(project_id, chapter_order, title, content)
                    SELECT ?::uuid, n, 'chapter ' || n, '游泳' FROM generate_series(2, 1001) AS n
                    """); PreparedStatement project = connection.prepareStatement(
                    "UPDATE tb_project SET next_chapter_order = 1002 WHERE id = ?::uuid")) {
                chapters.setString(1, fixture.project());
                assertEquals(1000, chapters.executeUpdate());
                project.setString(1, fixture.project()); project.executeUpdate();
            }
            ModelGateway fallback = request -> {
                if (request.messages().size() == 1) return calls("scope-1",
                        tool("search", "search_chapters", Map.of("keyword", "游泳", "scope", "ALL_CHAPTERS")),
                        tool("read", "read_chapter", Map.of("chapterId", fixture.chapter())));
                ToolReply search = request.messages().getLast().replies().stream()
                        .filter(reply -> reply.callId().equals("search")).findFirst().orElseThrow();
                assertEquals(false, search.result().get("success"));
                assertEquals("SEARCH_SCOPE_TOO_LARGE", ((Map<?,?>) search.result().get("error")).get("code"));
                assertEquals("body", data(request, "read").get("content"));
                return new Response("字面搜索范围超过上限；已读取目标章，其他章节未核查。", List.of(),
                        FinishReason.FINAL, "scope-2", "end_turn", null);
            };
            try (RunScheduler scheduler = scheduler(dataSource, fallback)) {
                scheduler.start();
                String runId = scheduler.submit(fixture.request("scope check")).runId();
                await(() -> "SUCCEEDED".equals(status(dataSource, runId)));
                Map<String,Object> detail = new JdbcRunQuery(dataSource).detail("owner", runId);
                assertEquals(2, detail.get("toolCallCount"));
                List<?> steps = (List<?>) detail.get("steps");
                assertEquals("SEARCH_SCOPE_TOO_LARGE", ((Map<?,?>) steps.getFirst()).get("errorCode"));
                List<?> sources = (List<?>) detail.get("sources");
                assertEquals(1, sources.size());
                assertEquals("FULL_CHAPTER", ((Map<?,?>) sources.getFirst()).get("kind"));
                assertEquals(0, count(dataSource, "SELECT count(*) FROM tb_agent_run_chapter_view WHERE run_id='" + runId + "'"));
            }
        });
    }

    @Test void thousandLongChaptersCanBeSearchedWithoutPartialResultsOrTimeout() throws Exception {
        withDatabase(dataSource -> {
            Flyway.configure().dataSource(dataSource).load().migrate();
            Fixture fixture = fixture(dataSource, "thousand-long-chapters");
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement chapters = connection.prepareStatement("""
                         INSERT INTO tb_chapter(project_id, chapter_order, title, content)
                         SELECT ?::uuid, n, 'chapter ' || n, repeat('甲', 5998)
                           FROM generate_series(2, 1000) AS n
                         """);
                 PreparedStatement revisions = connection.prepareStatement("""
                         INSERT INTO tb_chapter_revision(chapter_id, version, content, source)
                         SELECT id, version, content, 'CREATE' FROM tb_chapter
                          WHERE project_id = ?::uuid AND chapter_order >= 2
                         """);
                 PreparedStatement project = connection.prepareStatement(
                         "UPDATE tb_project SET next_chapter_order = 1001 WHERE id = ?::uuid")) {
                chapters.setString(1, fixture.project());
                assertEquals(999, chapters.executeUpdate());
                revisions.setString(1, fixture.project());
                assertEquals(999, revisions.executeUpdate());
                project.setString(1, fixture.project()); project.executeUpdate();
            }
            ModelGateway model = request -> {
                if (request.messages().size() == 1) return calls("pressure-1",
                        tool("search", "search_chapters", Map.of("keyword", "不存在", "scope", "ALL_CHAPTERS")),
                        tool("read", "read_chapter", Map.of("chapterId", fixture.chapter())));
                ToolReply search = request.messages().getLast().replies().stream()
                        .filter(reply -> reply.callId().equals("search")).findFirst().orElseThrow();
                assertEquals(true, search.result().get("success"));
                assertEquals(List.of(), ((Map<?,?>) search.result().get("data")).get("matches"));
                assertEquals(false, ((Map<?,?>) search.result().get("data")).get("hasMore"));
                return new Response("本次字面搜索未命中；目标章已完整读取。", List.of(), FinishReason.FINAL,
                        "pressure-2", "end_turn", null);
            };
            try (RunScheduler scheduler = scheduler(dataSource, model)) {
                scheduler.start();
                String runId = scheduler.submit(fixture.request("large scope check")).runId();
                await(() -> "SUCCEEDED".equals(status(dataSource, runId)));
                assertEquals(1000, count(dataSource,
                        "SELECT count(*) FROM tb_agent_run_chapter_view WHERE run_id='" + runId + "'"));
                Map<String,Object> detail = new JdbcRunQuery(dataSource).detail("owner", runId);
                assertEquals(2, detail.get("toolCallCount"));
                assertEquals(1, ((List<?>) detail.get("sources")).size());
                try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement();
                     ResultSet rows = sql.executeQuery("SELECT EXTRACT(EPOCH FROM (finished_at - started_at)) * 1000 " +
                             "FROM tb_agent_step WHERE run_id='" + runId + "' AND tool_name='search_chapters'")) {
                    assertTrue(rows.next());
                    assertTrue(rows.getDouble(1) < 5_500, "Search step exceeded its five-second budget");
                }
            }
        });
    }

    @Test void cancellingAQueryBlockedInPostgresEndsTheRunWithoutPartialEvidence() throws Exception {
        withDatabase(dataSource -> {
            Flyway.configure().dataSource(dataSource).load().migrate();
            Fixture fixture = fixture(dataSource, "blocked-search-cancel");
            CountDownLatch modelEntered = new CountDownLatch(1);
            CountDownLatch releaseModel = new CountDownLatch(1);
            ModelGateway model = request -> {
                modelEntered.countDown();
                try { assertTrue(releaseModel.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return calls("blocked-search", tool("search", "search_settings", Map.of("keyword", "missing")));
            };
            try (RunScheduler scheduler = scheduler(dataSource, model);
                 Connection blocker = dataSource.getConnection(); Statement lock = blocker.createStatement()) {
                scheduler.start();
                String runId = scheduler.submit(fixture.request("cancel blocked search")).runId();
                assertTrue(modelEntered.await(5, TimeUnit.SECONDS));
                blocker.setAutoCommit(false);
                lock.execute("LOCK TABLE tb_setting IN ACCESS EXCLUSIVE MODE");
                releaseModel.countDown();
                await(() -> {
                    try { return count(dataSource, "SELECT count(*) FROM tb_agent_step WHERE run_id='" + runId +
                            "' AND status='STARTED'") == 1; }
                    catch (SQLException e) { throw new IllegalStateException(e); }
                });
                await(() -> {
                    try { return count(dataSource, "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' " +
                            "AND query LIKE '%SELECT s.id, s.type, s.title, s.content%'") > 0; }
                    catch (SQLException e) { throw new IllegalStateException(e); }
                });
                long cancelledAt = System.nanoTime();
                assertTrue(Set.of("RUNNING", "CANCELLED").contains(scheduler.cancel(fixture.project(), runId).status()));
                boolean stoppedQuickly = false;
                while (System.nanoTime() - cancelledAt < TimeUnit.SECONDS.toNanos(2)) {
                    if ("CANCELLED".equals(status(dataSource, runId)) && scheduler.occupiedCapacity() == 0) {
                        stoppedQuickly = true; break;
                    }
                    Thread.sleep(20);
                }
                blocker.rollback();
                assertTrue(stoppedQuickly, "A cancelled database search should release its worker within two seconds");
                assertEquals(0, count(dataSource, "SELECT count(*) FROM tb_agent_context_item WHERE run_id='" + runId + "'"));
                assertEquals(0, count(dataSource, "SELECT count(*) FROM tb_agent_run_chapter_view WHERE run_id='" + runId + "'"));
                assertEquals(1, count(dataSource, "SELECT count(*) FROM tb_agent_step WHERE run_id='" + runId +
                        "' AND status='ABORTED'"));
            } finally { releaseModel.countDown(); }
        });
    }

    @Test void cancellingBlockedViewTransactionRespectsLockTimeout() throws Exception {
        withDatabase(dataSource -> {
            Flyway.configure().dataSource(dataSource).load().migrate();
            Fixture fixture = fixture(dataSource, "blocked-view-cancel");
            CountDownLatch modelEntered = new CountDownLatch(1);
            CountDownLatch releaseModel = new CountDownLatch(1);
            ModelGateway model = request -> {
                modelEntered.countDown();
                try { assertTrue(releaseModel.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return calls("blocked-view", tool("search", "search_chapters",
                        Map.of("keyword", "body", "scope", "ALL_CHAPTERS")));
            };
            try (RunScheduler scheduler = scheduler(dataSource, model);
                 Connection blocker = dataSource.getConnection(); Statement lock = blocker.createStatement()) {
                scheduler.start();
                String runId = scheduler.submit(fixture.request("cancel blocked view")).runId();
                assertTrue(modelEntered.await(5, TimeUnit.SECONDS));
                blocker.setAutoCommit(false);
                lock.execute("LOCK TABLE tb_agent_run_chapter_view IN ACCESS EXCLUSIVE MODE");
                releaseModel.countDown();
                await(() -> {
                    try { return count(dataSource, "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' " +
                            "AND query LIKE '%SELECT chapter_id, version, view_id FROM tb_agent_run_chapter_view%'") > 0; }
                    catch (SQLException e) { throw new IllegalStateException(e); }
                });
                long cancelledAt = System.nanoTime();
                assertTrue(Set.of("RUNNING", "CANCELLED").contains(scheduler.cancel(fixture.project(), runId).status()));
                boolean stoppedQuickly = false;
                while (System.nanoTime() - cancelledAt < TimeUnit.SECONDS.toNanos(2)) {
                    if ("CANCELLED".equals(status(dataSource, runId)) && scheduler.occupiedCapacity() == 0) {
                        stoppedQuickly = true; break;
                    }
                    Thread.sleep(20);
                }
                blocker.rollback();
                assertTrue(stoppedQuickly, "Cancellation must interrupt a blocked view write before waiting for its run lock");
                assertEquals(0, count(dataSource, "SELECT count(*) FROM tb_agent_run_chapter_view WHERE run_id='" + runId + "'"));
                assertEquals(0, count(dataSource, "SELECT count(*) FROM tb_agent_context_item WHERE run_id='" + runId + "'"));
                assertEquals(1, count(dataSource, "SELECT count(*) FROM tb_agent_step WHERE run_id='" + runId +
                        "' AND status='ABORTED'"));
            } finally { releaseModel.countDown(); }
        });
    }

    @Test void failedOrCancelledCandidateCannotBeAccepted() throws Exception {
        withDatabase(dataSource -> {
            CountDownLatch secondCallEntered = new CountDownLatch(1);
            CountDownLatch releaseSecondCall = new CountDownLatch(1);
            ModelGateway model = request -> {
                String instruction = request.messages().getFirst().content().stream()
                        .filter(TextPart.class::isInstance).map(TextPart.class::cast)
                        .findFirst().orElseThrow().text();
                String target = instruction.substring(instruction.lastIndexOf(' ') + 1);
                if (request.messages().size() == 1) return calls("candidate-first",
                        tool("read", "read_chapter", Map.of("chapterId", target)),
                        tool("candidate", "propose_edit", Map.of(
                                "proposedContent", "replacement", "reason", "consistency")));
                if (instruction.contains("truncation"))
                    return new Response("", List.of(), FinishReason.TRUNCATED, "candidate-truncated", "max_tokens", null);
                secondCallEntered.countDown();
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (releaseSecondCall.getCount() > 0 && System.nanoTime() < end) {
                    try { releaseSecondCall.await(100, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ignored) { /* Late provider response after cancellation. */ }
                }
                return new Response("analysis", List.of(), FinishReason.FINAL,
                        "candidate-late", "end_turn", null);
            };
            try (RunScheduler scheduler = scheduler(dataSource, model)) {
                scheduler.start();
                Fixture truncated = fixture(dataSource, "truncation");
                String failedRun = scheduler.submit(truncated.request("truncation")).runId();
                await(() -> "FAILED".equals(status(dataSource, failedRun)));
                assertEquals("LIMIT_EXCEEDED", errorCode(dataSource, failedRun));
                assertCandidateWasNotPublished(dataSource, truncated, failedRun);

                Fixture cancelled = fixture(dataSource, "cancellation");
                String cancelledRun = scheduler.submit(cancelled.request("cancellation")).runId();
                assertTrue(secondCallEntered.await(5, TimeUnit.SECONDS));
                assertEquals("RUNNING", scheduler.cancel(cancelled.project(), cancelledRun).status());
                releaseSecondCall.countDown();
                await(() -> "CANCELLED".equals(status(dataSource, cancelledRun))
                        && scheduler.occupiedCapacity() == 0);
                assertCandidateWasNotPublished(dataSource, cancelled, cancelledRun);
                assertEquals(0, count(dataSource, "SELECT count(*) FROM tb_edit_proposal"));
            } finally { releaseSecondCall.countDown(); }
        });
    }

    private static void assertCandidateWasNotPublished(PGSimpleDataSource dataSource, Fixture fixture, String runId)
            throws SQLException {
        Map<String,Object> detail = new JdbcRunQuery(dataSource).detail("owner", runId);
        assertNull(detail.get("proposalId"));
        assertTrue(((List<?>) detail.get("steps")).stream().anyMatch(item -> {
            Map<?,?> step = (Map<?,?>) item;
            return "propose_edit".equals(step.get("toolName")) && "SUCCEEDED".equals(step.get("status"));
        }));
        assertEquals("body", new JdbcContentService(dataSource).chapter("owner", fixture.chapter()).get("content"));
        assertEquals(1, new JdbcContentService(dataSource).chapter("owner", fixture.chapter()).get("version"));
        JdbcEditProposalService.ProposalException missing = assertThrows(
                JdbcEditProposalService.ProposalException.class,
                () -> new JdbcEditProposalService(dataSource).accept("owner", runId));
        assertEquals("RESOURCE_NOT_FOUND", missing.code());
    }

    private static Response responseFor(ModelGateway.Request request) {
        if (request.messages().size() == 1) {
            String chapter = request.messages().getFirst().content().stream()
                    .filter(TextPart.class::isInstance).map(TextPart.class::cast).findFirst()
                    .map(TextPart::text).orElseThrow();
            // The test instruction carries its target chapter ID as a suffix.
            String id = chapter.substring(chapter.lastIndexOf(' ') + 1);
            return new Response("", List.of(new ToolCall("read", "read_chapter",
                    JsonMapper.builder().build().createObjectNode().put("chapterId", id))),
                    FinishReason.TOOL_CALLS, "script-1", "tool_use", null);
        }
        return new Response("分析完成", List.of(), FinishReason.FINAL, "script-2", "end_turn", null);
    }

    private static RunScheduler scheduler(PGSimpleDataSource source, ModelGateway model) {
        return new RunScheduler(source, model, Clock.systemUTC(), "system", "scripted", 100,
                "0123456789abcdef0123456789abcdef".getBytes());
    }
    private static DataSource failConnectionsWhen(PGSimpleDataSource source, AtomicBoolean unavailable) {
        return new DataSource() {
            private void check() throws SQLException {
                if (unavailable.get()) throw new SQLException("Simulated database outage");
            }
            public Connection getConnection() throws SQLException { check(); return source.getConnection(); }
            public Connection getConnection(String user, String password) throws SQLException {
                check(); return source.getConnection(user, password);
            }
            public PrintWriter getLogWriter() throws SQLException { return source.getLogWriter(); }
            public void setLogWriter(PrintWriter writer) throws SQLException { source.setLogWriter(writer); }
            public void setLoginTimeout(int seconds) throws SQLException { source.setLoginTimeout(seconds); }
            public int getLoginTimeout() throws SQLException { return source.getLoginTimeout(); }
            public Logger getParentLogger() { return source.getParentLogger(); }
            public <T> T unwrap(Class<T> type) throws SQLException { return source.unwrap(type); }
            public boolean isWrapperFor(Class<?> type) throws SQLException { return source.isWrapperFor(type); }
        };
    }
    private static void failAgentEventInsert(PGSimpleDataSource source, String eventType) throws SQLException {
        try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("""
                    CREATE FUNCTION fail_selected_agent_event() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                      IF NEW.type = '%s' THEN
                        RAISE EXCEPTION 'Injected event insert failure';
                      END IF;
                      RETURN NEW;
                    END
                    $$
                    """.formatted(eventType));
            sql.execute("""
                    CREATE TRIGGER fail_selected_agent_event BEFORE INSERT ON tb_agent_event
                    FOR EACH ROW EXECUTE FUNCTION fail_selected_agent_event()
                    """);
        }
    }
    private record Fixture(String project, String chapter) {
        JdbcRunAdmission.Request request(String label) {
            return new JdbcRunAdmission.Request("owner", project, chapter, 1,
                    label + " " + chapter, UUID.randomUUID().toString());
        }
    }
    private static Fixture fixture(PGSimpleDataSource source, String name) throws SQLException {
        String project = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO tb_project(id, owner_id, name, next_chapter_order) VALUES ('" + project
                    + "','owner','" + name + "',2)");
            sql.execute("INSERT INTO tb_chapter(id, project_id, chapter_order, title, content) VALUES " +
                    "('" + chapter + "','" + project + "',1,'chapter','body')");
            sql.execute("INSERT INTO tb_chapter_revision(chapter_id, version, content, source) VALUES " +
                    "('" + chapter + "',1,'body','CREATE')");
        }
        return new Fixture(project, chapter);
    }
    private static int count(PGSimpleDataSource source, String sql) throws SQLException {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) { rows.next(); return rows.getInt(1); }
    }
    private static String status(PGSimpleDataSource source, String runId) {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT status FROM tb_agent_run WHERE id='" + runId + "'")) {
            return rows.next() ? rows.getString(1) : "MISSING";
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }
    private static String errorCode(PGSimpleDataSource source, String runId) {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT error_code FROM tb_agent_run WHERE id='" + runId + "'")) {
            return rows.next() ? rows.getString(1) : null;
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }
    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        fail("Timed out waiting for run state");
    }
    private interface DatabaseWork { void run(PGSimpleDataSource source) throws Exception; }
    private static void withDatabase(DatabaseWork work) throws Exception {
        String baseUrl = System.getenv("MOJIE_TEST_JDBC_URL");
        assumeTrue(baseUrl != null && !baseUrl.isBlank());
        String user = System.getenv().getOrDefault("MOJIE_TEST_DB_USER", "postgres");
        String password = System.getenv().getOrDefault("MOJIE_TEST_DB_PASSWORD", "");
        String schema = "mojie_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection admin = DriverManager.getConnection(baseUrl, user, password);
             Statement sql = admin.createStatement()) { sql.execute("CREATE SCHEMA " + schema); }
        try {
            PGSimpleDataSource source = new PGSimpleDataSource();
            source.setURL(baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema);
            source.setUser(user); source.setPassword(password);
            work.run(source);
        } finally {
            try (Connection admin = DriverManager.getConnection(baseUrl, user, password);
                 Statement sql = admin.createStatement()) { sql.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }
}
