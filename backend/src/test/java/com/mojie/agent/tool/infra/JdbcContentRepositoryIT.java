package com.mojie.agent.tool.infra;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.agent.loop.*;
import com.mojie.agent.model.ModelGateway;
import com.mojie.agent.model.ModelGateway.*;
import com.mojie.agent.run.infra.JdbcRunTerminalStore;
import com.mojie.agent.tool.ContentRepository;
import com.mojie.agent.tool.ContentTools;
import com.mojie.agent.tool.ToolContext;
import com.mojie.agent.trace.infra.JdbcLoopTrace;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in PostgreSQL 18 integration check; each invocation owns an isolated schema. */
class JdbcContentRepositoryIT {
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
            assertEquals(4, Flyway.configure().dataSource(dataSource).schemas(schema).load().migrate().migrationsExecuted);
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM pg_tables WHERE schemaname = '" + schema + "' AND tablename LIKE 'tb_%'")) {
                    rows.next(); assertEquals(13, rows.getInt(1));
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
                    sql.execute("INSERT INTO tb_project(id, owner_id, name) VALUES ('" + project + "', 'owner', 'book')");
                    sql.execute("INSERT INTO tb_chapter(id, project_id, chapter_order, title, content) VALUES " +
                            "('" + prior + "','" + project + "',1,'prior','old prior')," +
                            "('" + target + "','" + project + "',2,'target','old target')");
                    sql.execute("INSERT INTO tb_chapter_revision(chapter_id, version, content, source) VALUES " +
                            "('" + prior + "',1,'old prior','CREATE'),('" + target + "',1,'old target','CREATE')");
                    sql.execute("INSERT INTO tb_agent_prompt_snapshot(hash, kind, content) VALUES " +
                            "('" + promptHash + "','SYSTEM_PROMPT','prompt'),('" + schemaHash + "','TOOL_SCHEMA','tools')");
                    sql.execute("INSERT INTO tb_agent_run(id, owner_id, project_id, target_chapter_id, instruction, " +
                            "client_request_id, request_digest, base_version, base_content, status, deadline_at, model_id, " +
                            "prompt_hash, tool_schema_hash, config_snapshot) VALUES (" +
                            "'" + run + "','owner','" + project + "','" + target + "','check'," +
                            "'" + UUID.randomUUID() + "','" + "c".repeat(64) + "',1,'old target','RUNNING',now()+interval '3 minutes'," +
                            "'scripted','" + promptHash + "','" + schemaHash + "','{}'::jsonb)");
                    sql.execute("INSERT INTO tb_agent_message(run_id, message_seq, role, content) VALUES " +
                            "('" + run + "',1,'USER','[{\"type\":\"text\",\"text\":\"check\"}]'::jsonb)");
                    sql.execute("UPDATE tb_project SET active_run_id='" + run + "' WHERE id='" + project + "'");
                }
                connection.commit();
            }

            JdbcContentRepository repository = new JdbcContentRepository(dataSource);
            ToolContext.Chapter firstRead = repository.chapterRevision(project, prior, 1);
            assertEquals("old prior", firstRead.content());
            try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                sql.execute("UPDATE tb_chapter SET version=2, content='new prior' WHERE id='" + prior + "'");
                sql.execute("INSERT INTO tb_chapter_revision(chapter_id, version, content, source) VALUES " +
                        "('" + prior + "',2,'new prior','MANUAL')");
            }
            ContentRepository.ChapterView view = repository.captureChapterView(run, project, target, 1,
                    Map.of(prior, firstRead));
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
                    new JdbcLoopTrace(dataSource), LoopLimits.defaults(), Clock.systemUTC(), () -> false);
            LoopOutcome outcome = loop.run(new LoopInput(context, "check", "prompt", "scripted", 100,
                    Instant.now().plusSeconds(180)));
            assertInstanceOf(LoopOutcome.Completed.class, outcome, () -> outcome.toString());
            assertEquals(2, calls.get());
            JdbcRunTerminalStore terminal = new JdbcRunTerminalStore(dataSource, Clock.systemUTC());
            JdbcRunTerminalStore.Result committed = terminal.finish(project, run, outcome);
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
                    new JdbcLoopTrace(dataSource), LoopLimits.defaults(), Clock.systemUTC(), () -> false)
                    .run(new LoopInput(new ToolContext(proposalRun, project, target, 1, "old target"),
                            "check", "prompt", "scripted", 100, Instant.now().plusSeconds(180)));
            assertInstanceOf(LoopOutcome.Completed.class, proposed, () -> proposed.toString());
            JdbcRunTerminalStore.Result published = terminal.finish(project, proposalRun, proposed);
            assertEquals("SUCCEEDED", published.status());
            assertNotNull(published.proposalId());
            try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                try (ResultSet rows = sql.executeQuery("SELECT status, proposed_content FROM tb_edit_proposal WHERE id='" + published.proposalId() + "'")) {
                    rows.next(); assertEquals("PENDING", rows.getString(1)); assertEquals("new target", rows.getString(2));
                }
                try (ResultSet rows = sql.executeQuery("SELECT content, version FROM tb_chapter WHERE id='" + target + "'")) {
                    rows.next(); assertEquals("old target", rows.getString(1)); assertEquals(1, rows.getInt(2));
                }
            }

            String cancelRun = insertRun(dataSource, project, target, promptHash, schemaHash);
            try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                sql.execute("UPDATE tb_agent_run SET cancel_requested=true WHERE id='" + cancelRun + "'");
            }
            JdbcRunTerminalStore.Result cancelled = terminal.finish(project, cancelRun,
                    new LoopOutcome.Completed("late", new ToolContext.Candidate("forbidden", "late"), 0, 0, 0));
            assertEquals("CANCELLED", cancelled.status());
            assertNull(cancelled.proposalId());
            try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                try (ResultSet rows = sql.executeQuery("SELECT count(*) FROM tb_edit_proposal WHERE run_id='" + cancelRun + "'")) {
                    rows.next(); assertEquals(0, rows.getInt(1));
                }
            }
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
    private static String insertRun(PGSimpleDataSource dataSource, String project, String target,
                                    String promptHash, String schemaHash) throws SQLException {
        String run = UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO tb_agent_run(id, owner_id, project_id, target_chapter_id, instruction, " +
                    "client_request_id, request_digest, base_version, base_content, status, deadline_at, model_id, " +
                    "prompt_hash, tool_schema_hash, config_snapshot) VALUES (" +
                    "'" + run + "','owner','" + project + "','" + target + "','check'," +
                    "'" + UUID.randomUUID() + "','" + "d".repeat(64) + "',1,'old target','RUNNING',now()+interval '3 minutes'," +
                    "'scripted','" + promptHash + "','" + schemaHash + "','{}'::jsonb)");
            sql.execute("INSERT INTO tb_agent_message(run_id, message_seq, role, content) VALUES " +
                    "('" + run + "',1,'USER','[{\"type\":\"text\",\"text\":\"check\"}]'::jsonb)");
            sql.execute("UPDATE tb_project SET active_run_id='" + run + "' WHERE id='" + project + "'");
        }
        return run;
    }
}
