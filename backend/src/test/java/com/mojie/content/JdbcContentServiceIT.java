package com.mojie.content;

import com.mojie.agent.run.infra.JdbcRunAdmission;
import com.mojie.agent.run.infra.JdbcRunQuery;
import com.mojie.agent.run.infra.JdbcRunLifecycle;
import com.mojie.agent.run.infra.JdbcRunTerminalStore;
import com.mojie.agent.tool.*;
import com.mojie.agent.tool.infra.JdbcContentRepository;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JdbcContentServiceIT {
    @Test void authorWritesPreserveHistoryAndFreezeSettingsDuringRun() throws Exception {
        withDatabase(source -> {
            Flyway.configure().dataSource(source).load().migrate();
            JdbcContentService content = new JdbcContentService(source);
            String owner = "author";
            String project = (String) content.createProject(owner, "作品").get("id");
            String chapter = (String) content.createChapter(owner, project, "第一章").get("id");
            assertEquals(1, content.chapter(owner, chapter).get("version"));
            Map<String,Object> saved = content.saveChapter(owner, chapter, "林舟不会游泳。", 1);
            assertEquals(2, saved.get("version"));
            assertEquals("", content.revision(owner, chapter, 1).get("content"));
            assertEquals("林舟不会游泳。", content.revision(owner, chapter, 2).get("content"));
            JdbcContentService.ContentException conflict = assertThrows(JdbcContentService.ContentException.class,
                    () -> content.saveChapter(owner, chapter, "覆盖", 1));
            assertEquals("VERSION_CONFLICT", conflict.code());
            String setting = (String) content.createSetting(owner, project, "CHARACTER", "林舟", "不会游泳").get("id");
            assertEquals(1, content.settings(owner, project, null, null, null).size());
            UUID processId = UUID.randomUUID();
            JdbcRunAdmission admission = new JdbcRunAdmission(source, Clock.systemUTC(), processId);
            JdbcRunAdmission.Receipt receipt = admission.create(new JdbcRunAdmission.Request(owner, project, chapter, 2,
                    "检查设定", UUID.randomUUID().toString()), new JdbcRunAdmission.Config("System", "[]", "model", Map.of("maxOutputTokens", 1000)));
            assertEquals("QUEUED", receipt.status());
            Map<String,Object> queuedTrace = new JdbcRunQuery(source).trace(owner, receipt.runId());
            assertTrue(queuedTrace.get("messages").toString().contains(chapter));
            assertEquals("RESOURCE_BUSY", assertThrows(JdbcContentService.ContentException.class,
                    () -> content.updateSetting(owner, setting, "CHARACTER", "林舟", "会游泳")).code());
            assertEquals("RESOURCE_BUSY", assertThrows(JdbcContentService.ContentException.class,
                    () -> content.createChapter(owner, project, "第二章")).code());
            assertEquals(3, content.saveChapter(owner, chapter, "林舟学会了游泳。", 2).get("version"));
            JdbcRunQuery query = new JdbcRunQuery(source);
            assertEquals(1, query.list(owner, project, chapter, null, 20).size());
            Map<String,Object> detail = query.detail(owner, receipt.runId());
            assertEquals(1L, detail.get("lastEventSeq"));
            assertEquals("run.queued", query.events(owner, receipt.runId(), 0, 20).getFirst().type());
            assertEquals(0, query.events(owner, receipt.runId(), 1, 20).size());
            assertThrows(JdbcRunQuery.NotFound.class, () -> query.detail("other-author", receipt.runId()));
            JdbcRunLifecycle lifecycle = new JdbcRunLifecycle(source, new JdbcRunTerminalStore(source, Clock.systemUTC()));
            assertTrue(lifecycle.start(project, receipt.runId(), processId));
            JdbcContentRepository repository = new JdbcContentRepository(source);
            assertEquals(1, repository.chapters(project, 2000).size());
            assertEquals("林舟不会游泳。", repository.chapterRevision(project, chapter, 2, 2000).content());
            assertEquals(1, repository.settings(project, 2000).size());
            ToolContext context = new ToolContext(receipt.runId(), project, chapter, 2, "林舟不会游泳。");
            AgentTool search = ContentTools.all(repository, "test-cursor-secret".getBytes()).stream()
                    .filter(tool -> tool.spec().name().equals("search_chapters")).findFirst().orElseThrow();
            assertTrue(search.execute(new JsonMapper().readTree("{\"keyword\":\"林舟\",\"scope\":\"ALL_CHAPTERS\"}"), context).success());
        });
    }
    private interface Work { void run(PGSimpleDataSource source) throws Exception; }
    private static void withDatabase(Work work) throws Exception {
        String url = System.getenv("MOJIE_TEST_JDBC_URL");
        assumeTrue(url != null && !url.isBlank());
        String user = System.getenv().getOrDefault("MOJIE_TEST_DB_USER", "postgres");
        String password = System.getenv().getOrDefault("MOJIE_TEST_DB_PASSWORD", "");
        String schema = "mojie_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection admin = DriverManager.getConnection(url, user, password); Statement sql = admin.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
        }
        try {
            PGSimpleDataSource source = new PGSimpleDataSource();
            source.setURL(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
            source.setUser(user); source.setPassword(password);
            work.run(source);
        } finally {
            try (Connection admin = DriverManager.getConnection(url, user, password); Statement sql = admin.createStatement()) {
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
