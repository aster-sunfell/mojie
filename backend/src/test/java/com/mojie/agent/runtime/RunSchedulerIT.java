package com.mojie.agent.runtime;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.agent.model.ModelGateway;
import com.mojie.agent.model.ModelGateway.*;
import com.mojie.agent.run.infra.JdbcRunAdmission;
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
            sql.execute("INSERT INTO tb_project(id, owner_id, name) VALUES ('" + project + "','owner','" + name + "')");
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
