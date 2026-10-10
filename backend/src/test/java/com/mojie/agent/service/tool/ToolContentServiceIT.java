package com.mojie.agent.service.tool;

import com.mojie.agent.model.ChapterView;
import com.mojie.support.*;
import com.mojie.agent.service.*;
import com.mojie.agent.model.*;
import com.mojie.agent.model.RunAdmission.*;
import com.mojie.agent.model.RunAdmission.Request;
import com.mojie.agent.service.tool.ToolContentService;
import com.mojie.common.error.*;
import com.mojie.proposal.service.ProposalService;
import com.mojie.proposal.model.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.agent.service.loop.*;
import com.mojie.agent.service.port.ModelGateway;
import com.mojie.agent.service.port.ModelGateway.*;
import com.mojie.agent.service.tool.ToolContentReader;
import com.mojie.agent.service.tool.ContentTools;
import com.mojie.agent.service.tool.ToolContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import java.sql.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.io.PrintWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.*;
import java.util.logging.Logger;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in PostgreSQL 18 integration check; each invocation owns an isolated schema. */
class ToolContentServiceIT {
    @Test void migrationsAndVersionViewUseImmutableRevisions() throws Exception {
        String baseUrl = System.getenv("MOJIE_TEST_JDBC_URL");
        assumeTrue(baseUrl != null && !baseUrl.isBlank(), "Set MOJIE_TEST_JDBC_URL to run PostgreSQL integration tests");
        String user = System.getenv().getOrDefault("MOJIE_TEST_DB_USER", "postgres");
        String password = System.getenv().getOrDefault("MOJIE_TEST_DB_PASSWORD", "");
        String schema = "mojie_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection admin = DriverManager.getConnection(baseUrl, user, password);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        try {
            PGSimpleDataSource dataSource = new PGSimpleDataSource();
            dataSource.setURL(baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema);
            dataSource.setUser(user);
            dataSource.setPassword(password);
            assertEquals(6, Flyway.configure().dataSource(dataSource).schemas(schema).load().migrate().migrationsExecuted);
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM pg_tables WHERE schemaname = '" + schema + "' AND tablename LIKE 'tb_%'")) {
                    rows.next(); assertEquals(14, rows.getInt(1));
                }
            }

            String project = UUID.randomUUID().toString();
            String target = UUID.randomUUID().toString();
            String prior = UUID.randomUUID().toString();
            String run = UUID.randomUUID().toString();
            String promptHash = "a".repeat(64), schemaHash = "b".repeat(64);
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (Statement sql = connection.createStatement()) {
                    sql.execute("INSERT INTO tb_project(id, name) VALUES ('" + project + "', 'book')");
                    sql.execute("INSERT INTO tb_chapter(id, project_id, chapter_order, title, content) VALUES " +
                            "('" + prior + "','" + project + "',1,'prior','old prior')," +
                            "('" + target + "','" + project + "',2,'target','old target')");
                    sql.execute("INSERT INTO tb_chapter_revision(chapter_id, version, content, source) VALUES " +
                            "('" + prior + "',1,'old prior','CREATE'),('" + target + "',1,'old target','CREATE')");
                    sql.execute("INSERT INTO tb_agent_prompt_snapshot(hash, kind, content) VALUES " +
                            "('" + promptHash + "','SYSTEM_PROMPT','prompt'),('" + schemaHash + "','TOOL_SCHEMA','tools')");
                    sql.execute("INSERT INTO tb_agent_run(id, project_id, target_chapter_id, instruction, " +
                            "client_request_id, request_digest, base_version, base_content, status, deadline_at, model_id, " +
                            "prompt_hash, tool_schema_hash, config_snapshot) VALUES (" +
                            "'" + run + "','" + project + "','" + target + "','check'," +
                            "'" + UUID.randomUUID() + "','" + "c".repeat(64) + "',1,'old target','RUNNING',now()+interval '3 minutes'," +
                            "'scripted','" + promptHash + "','" + schemaHash + "','{}'::jsonb)");
                    sql.execute("INSERT INTO tb_agent_message(run_id, message_seq, role, content) VALUES " +
                            "('" + run + "',1,'USER','[{\"type\":\"text\",\"text\":\"check\"}]'::jsonb)");
                    sql.execute("UPDATE tb_project SET active_run_id='" + run + "' WHERE id='" + project + "'");
                }
                connection.commit();
            }

            ToolContentService repository = TestServices.toolContent(dataSource);
            assertEquals(List.of(prior, target), repository.chapters(project).stream()
                    .map(ToolContentReader.ChapterMetadata::id).toList());
            ToolContext.Chapter firstRead = repository.chapterRevision(project, prior, 1);
            assertEquals("old prior", firstRead.content());
            assertEquals(List.of("old prior", "old target"), repository.chapterRevisions(project, List.of(
                    new ToolContentReader.ChapterVersion(prior, 1),
                    new ToolContentReader.ChapterVersion(target, 1)), 2_000).stream()
                    .map(ToolContext.Chapter::content).toList());
            assertTrue(repository.chapterRevisions(UUID.randomUUID().toString(), List.of(
                    new ToolContentReader.ChapterVersion(prior, 1)), 2_000).isEmpty());
            try (Connection blocker = dataSource.getConnection(); Statement lock = blocker.createStatement()) {
                blocker.setAutoCommit(false);
                lock.execute("LOCK TABLE tb_chapter_revision IN ACCESS EXCLUSIVE MODE");
                assertThrows(ToolContentReader.SearchTimeoutException.class,
                        () -> repository.chapterRevisions(project, List.of(
                                new ToolContentReader.ChapterVersion(prior, 1)), 100));
                blocker.rollback();
            }
            assertEquals("old prior", repository.chapterRevisions(project, List.of(
                    new ToolContentReader.ChapterVersion(prior, 1)), 2_000).getFirst().content());
            CountDownLatch interruptedReadFinished = new CountDownLatch(1);
            AtomicBoolean cancelRequested = new AtomicBoolean();
            ToolContext queryContext = new ToolContext(UUID.randomUUID().toString(), project, target, 1, "old target");
            queryContext.executionClock(Clock.systemUTC(), Instant.now().plusSeconds(10), cancelRequested::get);
            java.util.concurrent.atomic.AtomicReference<RuntimeException> interruptedReadError =
                    new java.util.concurrent.atomic.AtomicReference<>();
            try (Connection blocker = dataSource.getConnection(); Statement lock = blocker.createStatement()) {
                blocker.setAutoCommit(false);
                lock.execute("LOCK TABLE tb_chapter_revision IN ACCESS EXCLUSIVE MODE");
                Thread reader = new Thread(() -> {
                    try { repository.chapterRevisions(project, List.of(
                            new ToolContentReader.ChapterVersion(prior, 1)), 5_000, queryContext); }
                    catch (RuntimeException failure) { interruptedReadError.set(failure); }
                    finally { interruptedReadFinished.countDown(); }
                }, "search-cancellation-check");
                reader.start();
                Thread.sleep(200);
                assertEquals(1, interruptedReadFinished.getCount());
                cancelRequested.set(true);
                queryContext.cancelActiveSearch();
                reader.interrupt();
                boolean stoppedQuickly = interruptedReadFinished.await(2, TimeUnit.SECONDS);
                blocker.rollback();
                reader.join(6_000);
                assertTrue(stoppedQuickly, "An interrupted database read should stop within two seconds");
                assertFalse(reader.isAlive());
                assertNotNull(interruptedReadError.get());
            }
            try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                sql.execute("UPDATE tb_chapter SET version=2, content='new prior' WHERE id='" + prior + "'");
                sql.execute("INSERT INTO tb_chapter_revision(chapter_id, version, content, source) VALUES " +
                        "('" + prior + "',2,'new prior','MANUAL')");
            }
            ChapterView view = repository.captureChapterView(run, project, target, 1,
                    Map.of(prior, firstRead));
            assertEquals(2L, repository.chapters(project, 2_000).getFirst().version());
            assertEquals(1L, view.versions().get(prior));
            assertEquals(1L, view.versions().get(target));
            assertEquals(view, repository.captureChapterView(run, project, target, 1, Map.of()));
            assertEquals("old prior", repository.chapterRevision(project, prior, view.versions().get(prior)).content());
            assertNull(repository.chapterRevision(UUID.randomUUID().toString(), prior, 1));

            AtomicInteger calls = new AtomicInteger();
            ModelGateway model = request -> {
                if (calls.incrementAndGet() == 1) return new Response("", List.of(new ToolCall("read-target", "read_chapter",
                        JsonMapper.builder().build().createObjectNode().put("chapterId", target))), FinishReason.TOOL_CALLS,
                        "provider-1", "tool_use", null);
                assertEquals(3, request.messages().size());
                return new Response("分析完成", List.of(), FinishReason.FINAL, "provider-2", "end_turn", null);
            };
            ToolContext context = new ToolContext(run, project, target, 1, "old target");
            AgentLoop loop = new AgentLoop(model, ContentTools.all(repository, "0123456789abcdef".getBytes()),
                    TestServices.trace(dataSource), LoopLimits.defaults(), Clock.systemUTC(), () -> false);
            LoopOutcome outcome = loop.run(new LoopInput(context, "check", "prompt", "scripted", 100,
                    Instant.now().plusSeconds(180)));
            assertInstanceOf(LoopOutcome.Completed.class, outcome, () -> outcome.toString());
            assertEquals(2, calls.get());
            RunCompletionService terminal = TestServices.completion(dataSource, Clock.systemUTC());
            assertThrows(StorageException.class, () -> TestServices.completion(
                    loseFirstCommitAcknowledgement(dataSource), Clock.systemUTC()).finish(project, run, outcome));
            // The commit reached PostgreSQL even though its acknowledgement was lost. Retry must read it back.
            TerminalResult committed = terminal.finish(project, run,
                    new LoopOutcome.Failed("PERSISTENCE_ERROR", "Commit result was unknown"));
            assertEquals("SUCCEEDED", committed.status());
            assertNull(committed.proposalId());
            assertEquals(committed, terminal.finish(project, run, outcome));
            try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                assertCount(sql, "tb_agent_model_call", 2);
                assertCount(sql, "tb_agent_message", 4);
                assertCount(sql, "tb_agent_step", 1);
                assertCount(sql, "tb_agent_context_item", 1);
                assertCount(sql, "tb_agent_event", 7);
                try (ResultSet rows = sql.executeQuery("SELECT last_event_seq, model_call_count, tool_call_count FROM tb_agent_run WHERE id='" + run + "'")) {
                    rows.next(); assertEquals(7, rows.getLong(1)); assertEquals(2, rows.getInt(2)); assertEquals(1, rows.getInt(3));
                }
                try (ResultSet rows = sql.executeQuery("SELECT active_run_id FROM tb_project WHERE id='" + project + "'")) {
                    rows.next(); assertNull(rows.getString(1));
                }
                try (ResultSet rows = sql.executeQuery("SELECT input_message_seqs FROM tb_agent_model_call WHERE run_id='" + run + "' AND call_index=2")) {
                    rows.next(); assertArrayEquals(new Long[]{1L, 2L, 3L}, (Long[]) rows.getArray(1).getArray());
                }
            }

            String proposalRun = insertRun(dataSource, project, target, promptHash, schemaHash);
            AtomicInteger proposalCalls = new AtomicInteger();
            ModelGateway proposalModel = request -> {
                if (proposalCalls.incrementAndGet() == 1) return new Response("", List.of(
                        new ToolCall("read", "read_chapter", JsonMapper.builder().build().createObjectNode().put("chapterId", target)),
                        new ToolCall("propose", "propose_edit", JsonMapper.builder().build().createObjectNode()
                                .put("proposedContent", "new target").put("reason", "fix"))),
                        FinishReason.TOOL_CALLS, "provider-3", "tool_use", null);
                return new Response("建议修改", List.of(), FinishReason.FINAL, "provider-4", "end_turn", null);
            };
            LoopOutcome proposed = new AgentLoop(proposalModel, ContentTools.all(repository, "0123456789abcdef".getBytes()),
                    TestServices.trace(dataSource), LoopLimits.defaults(), Clock.systemUTC(), () -> false)
                    .run(new LoopInput(new ToolContext(proposalRun, project, target, 1, "old target"),
                            "check", "prompt", "scripted", 100, Instant.now().plusSeconds(180)));
            assertInstanceOf(LoopOutcome.Completed.class, proposed, () -> proposed.toString());
            TerminalResult published = terminal.finish(project, proposalRun, proposed);
            assertEquals("SUCCEEDED", published.status());
            assertNotNull(published.proposalId());
            ProposalService decisions = TestServices.proposals(dataSource);
            String rejectedId = UUID.randomUUID().toString();
            try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                sql.execute("INSERT INTO tb_edit_proposal(id, run_id, project_id, target_chapter_id, base_version, " +
                        "base_content, proposed_content, reason, status) VALUES ('" + rejectedId + "','" + run + "','" +
                        project + "','" + target + "',1,'old target','rejected text','unneeded','PENDING')");
            }
            assertEquals("REJECTED", decisions.reject(rejectedId).status());
            assertEquals("REJECTED", decisions.reject(rejectedId).status());
            assertEquals("PROPOSAL_STATE_CONFLICT", assertThrows(BusinessException.class,
                    () -> decisions.accept(rejectedId)).code());
            try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                try (ResultSet rows = sql.executeQuery("SELECT status, proposed_content FROM tb_edit_proposal WHERE id='" + published.proposalId() + "'")) {
                    rows.next(); assertEquals("PENDING", rows.getString(1)); assertEquals("new target", rows.getString(2));
                }
                try (ResultSet rows = sql.executeQuery("SELECT content, version FROM tb_chapter WHERE id='" + target + "'")) {
                    rows.next(); assertEquals("old target", rows.getString(1)); assertEquals(1, rows.getInt(2));
                }
            }
            assertEquals(new ProposalDecision("APPLIED", 2), decisions.accept(published.proposalId()));
            assertEquals(new ProposalDecision("APPLIED", 2), decisions.accept(published.proposalId()));
            try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                try (ResultSet rows = sql.executeQuery("SELECT content, version FROM tb_chapter WHERE id='" + target + "'")) {
                    rows.next(); assertEquals("new target", rows.getString(1)); assertEquals(2, rows.getInt(2));
                }
                try (ResultSet rows = sql.executeQuery("SELECT count(*) FROM tb_chapter_revision WHERE proposal_id='" + published.proposalId() + "'")) {
                    rows.next(); assertEquals(1, rows.getInt(1));
                }
            }

            String staleRun = UUID.randomUUID().toString(), staleProposal = UUID.randomUUID().toString();
            try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                sql.execute("INSERT INTO tb_agent_run(id, project_id, target_chapter_id, instruction, " +
                        "client_request_id, request_digest, base_version, base_content, status, deadline_at, finished_at, " +
                        "model_id, prompt_hash, tool_schema_hash, config_snapshot) VALUES (" +
                        "'" + staleRun + "','" + project + "','" + target + "','stale'," +
                        "'" + UUID.randomUUID() + "','" + "e".repeat(64) + "',1,'old target','SUCCEEDED',now(),now()," +
                        "'scripted','" + promptHash + "','" + schemaHash + "','{}'::jsonb)");
                sql.execute("INSERT INTO tb_edit_proposal(id, run_id, project_id, target_chapter_id, base_version, " +
                        "base_content, proposed_content, reason, status) VALUES ('" + staleProposal + "','" + staleRun + "','" +
                        project + "','" + target + "',1,'old target','stale text','stale','PENDING')");
            }
            assertEquals("CONFLICTED", decisions.accept(staleProposal).status());
            try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                try (ResultSet rows = sql.executeQuery("SELECT status FROM tb_edit_proposal WHERE id='" + staleProposal + "'")) {
                    rows.next(); assertEquals("CONFLICTED", rows.getString(1));
                }
                try (ResultSet rows = sql.executeQuery("SELECT version FROM tb_chapter WHERE id='" + target + "'")) {
                    rows.next(); assertEquals(2, rows.getInt(1));
                }
            }

            String raceRun = UUID.randomUUID().toString(), raceProposal = UUID.randomUUID().toString();
            try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                sql.execute("INSERT INTO tb_agent_run(id, project_id, target_chapter_id, instruction, " +
                        "client_request_id, request_digest, base_version, base_content, status, deadline_at, finished_at, " +
                        "model_id, prompt_hash, tool_schema_hash, config_snapshot) VALUES (" +
                        "'" + raceRun + "','" + project + "','" + target + "','race'," +
                        "'" + UUID.randomUUID() + "','" + "f".repeat(64) + "',2,'new target','SUCCEEDED',now(),now()," +
                        "'scripted','" + promptHash + "','" + schemaHash + "','{}'::jsonb)");
                sql.execute("INSERT INTO tb_edit_proposal(id, run_id, project_id, target_chapter_id, base_version, " +
                        "base_content, proposed_content, reason, status) VALUES ('" + raceProposal + "','" + raceRun + "','" +
                        project + "','" + target + "',2,'new target','race text','race','PENDING')");
            }
            ExecutorService contenders = Executors.newFixedThreadPool(2);
            CountDownLatch startRace = new CountDownLatch(1);
            try {
                Future<Object> accept = contenders.submit(() -> {
                    startRace.await();
                    try { return decisions.accept(raceProposal); }
                    catch (BusinessException e) { return e.code(); }
                });
                Future<Object> reject = contenders.submit(() -> {
                    startRace.await();
                    try { return decisions.reject(raceProposal); }
                    catch (BusinessException e) { return e.code(); }
                });
                startRace.countDown();
                Object acceptedRace = accept.get(5, TimeUnit.SECONDS);
                Object rejectedRace = reject.get(5, TimeUnit.SECONDS);
                assertTrue((acceptedRace instanceof ProposalDecision
                                && "PROPOSAL_STATE_CONFLICT".equals(rejectedRace))
                        || (rejectedRace instanceof ProposalDecision
                                && "PROPOSAL_STATE_CONFLICT".equals(acceptedRace)));
            } finally { contenders.shutdownNow(); }
            try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                try (ResultSet rows = sql.executeQuery("SELECT status FROM tb_edit_proposal WHERE id='" + raceProposal + "'")) {
                    rows.next(); assertTrue(Set.of("APPLIED", "REJECTED").contains(rows.getString(1)));
                }
                try (ResultSet rows = sql.executeQuery("SELECT count(*) FROM tb_chapter_revision WHERE proposal_id='" + raceProposal + "'")) {
                    rows.next(); assertTrue(rows.getInt(1) <= 1);
                }
            }

            String cancelRun = insertRun(dataSource, project, target, promptHash, schemaHash);
            try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                sql.execute("UPDATE tb_agent_run SET cancel_requested=true WHERE id='" + cancelRun + "'");
            }
            TerminalResult cancelled = terminal.finish(project, cancelRun,
                    new LoopOutcome.Completed("late", new ToolContext.Candidate("forbidden", "late"), 0, 0, 0));
            assertEquals("CANCELLED", cancelled.status());
            assertNull(cancelled.proposalId());
            try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                try (ResultSet rows = sql.executeQuery("SELECT count(*) FROM tb_edit_proposal WHERE run_id='" + cancelRun + "'")) {
                    rows.next(); assertEquals(0, rows.getInt(1));
                }
            }

            String admittedProject = UUID.randomUUID().toString();
            String admittedChapter = UUID.randomUUID().toString();
            try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                sql.execute("INSERT INTO tb_project(id, name) VALUES ('" + admittedProject + "','admitted')");
                sql.execute("INSERT INTO tb_chapter(id, project_id, chapter_order, title, content) VALUES " +
                        "('" + admittedChapter + "','" + admittedProject + "',1,'chapter','base')");
                sql.execute("INSERT INTO tb_chapter_revision(chapter_id, version, content, source) VALUES " +
                        "('" + admittedChapter + "',1,'base','CREATE')");
            }
            UUID processId = UUID.randomUUID();
            RunAdmissionService admission = TestServices.admission(dataSource, Clock.systemUTC(), processId);
            Request request = new Request(admittedProject, admittedChapter,
                    1, "检查章节", UUID.randomUUID().toString());
            Config config = new Config("prompt", "{\"tools\":[]}", "scripted", Map.of("maxTokens", 100));
            Receipt accepted = admission.create(request, config);
            assertTrue(accepted.created());
            assertEquals("QUEUED", accepted.status());
            try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                sql.execute("UPDATE tb_chapter SET version=2, content='changed' WHERE id='" + admittedChapter + "'");
                sql.execute("INSERT INTO tb_chapter_revision(chapter_id, version, content, source) VALUES " +
                        "('" + admittedChapter + "',2,'changed','MANUAL')");
            }
            assertEquals(accepted.runId(), admission.create(request, config).runId());
            assertFalse(admission.create(request, config).created());
            BusinessException conflict = assertThrows(BusinessException.class,
                    () -> admission.create(new Request(admittedProject, admittedChapter, 1,
                            "不同要求", request.clientRequestId()), config));
            assertEquals("IDEMPOTENCY_CONFLICT", conflict.code());
            BusinessException busy = assertThrows(BusinessException.class,
                    () -> admission.create(new Request(admittedProject, admittedChapter, 2,
                            "新任务", UUID.randomUUID().toString()), config));
            assertEquals("RESOURCE_BUSY", busy.code());
            try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                try (ResultSet rows = sql.executeQuery("SELECT active_run_id FROM tb_project WHERE id='" + admittedProject + "'")) {
                    rows.next(); assertEquals(accepted.runId(), rows.getString(1));
                }
                try (ResultSet rows = sql.executeQuery("SELECT base_version, base_content, last_event_seq FROM tb_agent_run WHERE id='" + accepted.runId() + "'")) {
                    rows.next(); assertEquals(1, rows.getInt(1)); assertEquals("base", rows.getString(2)); assertEquals(1, rows.getLong(3));
                }
            }
            RunLifecycleService lifecycle = TestServices.lifecycle(dataSource, terminal);
            assertTrue(lifecycle.start(admittedProject, accepted.runId(), processId));
            assertFalse(lifecycle.start(admittedProject, accepted.runId(), processId));
            assertEquals(new CancelResult("RUNNING", true),
                    lifecycle.requestCancel(admittedProject, accepted.runId()));
            assertEquals("CANCELLED", terminal.finish(admittedProject, accepted.runId(),
                    new LoopOutcome.Completed("late", null, 0, 0, 0)).status());

            Receipt queued = admission.create(new Request(admittedProject,
                    admittedChapter, 2, "queued", UUID.randomUUID().toString()), config);
            assertEquals(new CancelResult("CANCELLED", true),
                    lifecycle.requestCancel(admittedProject, queued.runId()));
            assertFalse(lifecycle.start(admittedProject, queued.runId(), processId));

            Receipt overdue = admission.create(new Request(admittedProject,
                    admittedChapter, 2, "overdue", UUID.randomUUID().toString()), config);
            try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                sql.execute("UPDATE tb_agent_run SET deadline_at = now() - interval '1 second' WHERE id='" + overdue.runId() + "'");
            }
            assertTrue(lifecycle.sweepTimeouts().contains(overdue.runId()));
            try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement();
                 ResultSet rows = sql.executeQuery("SELECT status, error_code FROM tb_agent_run WHERE id='" + overdue.runId() + "'")) {
                rows.next(); assertEquals("FAILED", rows.getString(1)); assertEquals("RUN_TIMEOUT", rows.getString(2));
            }

            Receipt abandoned = admission.create(new Request(admittedProject,
                    admittedChapter, 2, "abandoned", UUID.randomUUID().toString()), config);
            assertTrue(lifecycle.failPreviousProcessRuns(UUID.randomUUID()).contains(abandoned.runId()));
            try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement();
                 ResultSet rows = sql.executeQuery("SELECT status, error_code FROM tb_agent_run WHERE id='" + abandoned.runId() + "'")) {
                rows.next(); assertEquals("FAILED", rows.getString(1)); assertEquals("SERVICE_RESTARTED", rows.getString(2));
            }
            QueryFixture runQuery = new QueryFixture(dataSource);
            var firstRunPage = runQuery.page(admittedProject, null, null, null, 2);
            var secondRunPage = runQuery.page(admittedProject, null, null, firstRunPage.nextCursor(), 2);
            assertEquals(2, firstRunPage.items().size());
            assertEquals(2, secondRunPage.items().size());
            assertNotNull(firstRunPage.nextCursor());
            assertTrue(Collections.disjoint(firstRunPage.items().stream().map(item -> item.get("id")).toList(),
                    secondRunPage.items().stream().map(item -> item.get("id")).toList()));
            assertThrows(IllegalArgumentException.class, () -> runQuery.page(admittedProject, null,
                    null, firstRunPage.nextCursor() + "tampered", 2));
            assertThrows(IllegalArgumentException.class, () -> runQuery.page(admittedProject, null,
                    "FAILED", firstRunPage.nextCursor(), 2));
        } finally {
            try (Connection admin = DriverManager.getConnection(baseUrl, user, password);
                 Statement statement = admin.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
    private static void assertCount(Statement statement, String table, int expected) throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rows.next(); assertEquals(expected, rows.getInt(1), table);
        }
    }
    private static DataSource loseFirstCommitAcknowledgement(PGSimpleDataSource delegate) {
        AtomicBoolean pending = new AtomicBoolean(true);
        return new DataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection raw = delegate.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, args) -> {
                            try {
                                if (method.getName().equals("commit") && pending.compareAndSet(true, false)) {
                                    raw.commit();
                                    throw new SQLException("Commit acknowledgement lost");
                                }
                                return method.invoke(raw, args);
                            } catch (InvocationTargetException e) { throw e.getCause(); }
                        });
            }
            @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }
            @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
            @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
            @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
            @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
            @Override public Logger getParentLogger() { return Logger.getGlobal(); }
            @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
            @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
        };
    }
    private static String insertRun(PGSimpleDataSource dataSource, String project, String target,
                                    String promptHash, String schemaHash) throws SQLException {
        String run = UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO tb_agent_run(id, project_id, target_chapter_id, instruction, " +
                    "client_request_id, request_digest, base_version, base_content, status, deadline_at, model_id, " +
                    "prompt_hash, tool_schema_hash, config_snapshot) VALUES (" +
                    "'" + run + "','" + project + "','" + target + "','check'," +
                    "'" + UUID.randomUUID() + "','" + "d".repeat(64) + "',1,'old target','RUNNING',now()+interval '3 minutes'," +
                    "'scripted','" + promptHash + "','" + schemaHash + "','{}'::jsonb)");
            sql.execute("INSERT INTO tb_agent_message(run_id, message_seq, role, content) VALUES " +
                    "('" + run + "',1,'USER','[{\"type\":\"text\",\"text\":\"check\"}]'::jsonb)");
            sql.execute("UPDATE tb_project SET active_run_id='" + run + "' WHERE id='" + project + "'");
        }
        return run;
    }
}
