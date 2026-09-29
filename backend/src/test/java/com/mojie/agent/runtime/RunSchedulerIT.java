package com.mojie.agent.runtime;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.agent.model.ModelGateway;
import com.mojie.agent.model.ModelGateway.*;
import com.mojie.agent.run.infra.JdbcRunAdmission;
import com.mojie.agent.run.infra.JdbcRunQuery;
import com.mojie.content.JdbcContentService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
