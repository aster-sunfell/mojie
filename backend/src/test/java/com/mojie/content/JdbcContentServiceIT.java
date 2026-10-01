package com.mojie.content;

import com.mojie.agent.run.infra.JdbcRunAdmission;
import com.mojie.agent.run.infra.JdbcRunQuery;
import com.mojie.agent.run.infra.JdbcRunLifecycle;
import com.mojie.agent.run.infra.JdbcRunTerminalStore;
import com.mojie.agent.tool.*;
import com.mojie.agent.tool.infra.JdbcContentRepository;
import com.mojie.common.Page;
import com.mojie.proposal.infra.JdbcProposalReview;
import com.mojie.proposal.infra.JdbcEditProposalService;
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
    @Test void partialReviewSavesSurviveManualEditsAndKeepEveryRelatedRevision() throws Exception {
        withDatabase(source -> {
            Flyway.configure().dataSource(source).load().migrate();
            JdbcContentService content = new JdbcContentService(source);
            String owner = "review-author";
            String project = (String) content.createProject(owner, "修改审阅").get("id");
            String chapter = (String) content.createChapter(owner, project, "第一章").get("id");
            String base = "林舟游过河。\n\n他拿起红灯。\n";
            content.saveChapter(owner, chapter, base, 1);
            String proposal = proposal(source, owner, project, chapter, 2, base.replace("游过河", "走过桥").replace("红", "蓝"));
            JdbcRunQuery query = new JdbcRunQuery(source);
            assertEquals(2, ((List<?>) query.proposal(owner, proposal).get("items")).size());
            var first = List.of(new JdbcProposalReview.Review(proposal, List.of(new JdbcProposalReview.Choice(0, "APPLIED"))));
            String partial = base.replace("游过河", "走过桥") + "用户自己补写。";
            assertEquals(3, content.saveChapter(owner, chapter, partial, 2, first).get("version"));
            assertEquals("PARTIALLY_APPLIED", query.proposal(owner, proposal).get("status"));
            assertEquals("MIXED", content.revision(owner, chapter, 3).get("source"));
            assertEquals(proposal, content.revision(owner, chapter, 3).get("proposalId"));
            // A retry cannot create another revision or reverse a saved decision.
            assertEquals(3, content.saveChapter(owner, chapter, partial, 3, first).get("version"));
            assertEquals("PROPOSAL_STATE_CONFLICT", assertThrows(JdbcEditProposalService.ProposalException.class,
                    () -> new JdbcEditProposalService(source).accept(owner, proposal)).code());
            String manual = partial + "继续手动完善。";
            content.saveChapter(owner, chapter, manual, 3);
            assertEquals("PARTIALLY_APPLIED", query.proposal(owner, proposal).get("status"));
            var second = List.of(new JdbcProposalReview.Review(proposal, List.of(new JdbcProposalReview.Choice(1, "APPLIED"))));
            assertEquals(5, content.saveChapter(owner, chapter, manual.replace("红", "蓝"), 4, second).get("version"));
            assertEquals("APPLIED", query.proposal(owner, proposal).get("status"));
            assertEquals(5, query.proposal(owner, proposal).get("appliedVersion"));
            assertEquals("MIXED", content.revision(owner, chapter, 5).get("source"));
            assertEquals(1, ((List<?>) content.revision(owner, chapter, 5).get("proposalReviews")).size());
            assertTrue(content.chapter(owner, chapter).get("content").toString().endsWith("用户自己补写。继续手动完善。"));
        });
    }

    @Test void reviewChoicesValidateScopeAndRollBackTogetherWithTextOnConflict() throws Exception {
        withDatabase(source -> {
            Flyway.configure().dataSource(source).load().migrate();
            JdbcContentService content = new JdbcContentService(source);
            String owner = "review-author";
            String project = (String) content.createProject(owner, "修改审阅").get("id");
            String chapter = (String) content.createChapter(owner, project, "第一章").get("id");
            content.saveChapter(owner, chapter, "旧文", 1);
            String proposal = proposal(source, owner, project, chapter, 2, "新文");
            var accepted = List.of(new JdbcProposalReview.Review(proposal, List.of(new JdbcProposalReview.Choice(0, "APPLIED"))));
            assertEquals("VERSION_CONFLICT", assertThrows(JdbcContentService.ContentException.class,
                    () -> content.saveChapter(owner, chapter, "不能覆盖", 1, accepted)).code());
            assertEquals("VALIDATION_ERROR", assertThrows(JdbcContentService.ContentException.class,
                    () -> content.saveChapter(owner, chapter, "不能覆盖", 2,
                            List.of(new JdbcProposalReview.Review(proposal, List.of(new JdbcProposalReview.Choice(99, "APPLIED")))))).code());
            assertEquals("RESOURCE_NOT_FOUND", assertThrows(JdbcContentService.ContentException.class,
                    () -> content.saveChapter("another-author", chapter, "不能覆盖", 2, accepted)).code());
            String other = (String) content.createChapter(owner, project, "第二章").get("id");
            assertEquals("RESOURCE_NOT_FOUND", assertThrows(JdbcContentService.ContentException.class,
                    () -> content.saveChapter(owner, other, "不能覆盖", 1, accepted)).code());
            assertEquals("旧文", content.chapter(owner, chapter).get("content"));
            assertEquals("PENDING", new JdbcRunQuery(source).proposal(owner, proposal).get("status"));
            var ignored = List.of(new JdbcProposalReview.Review(proposal, List.of(new JdbcProposalReview.Choice(0, "IGNORED"))));
            // Ignoring a suggestion alone keeps the text and chapter version unchanged.
            assertEquals(2, content.saveChapter(owner, chapter, "旧文", 2, ignored).get("version"));
            assertEquals("REJECTED", new JdbcRunQuery(source).proposal(owner, proposal).get("status"));
            assertEquals("PROPOSAL_STATE_CONFLICT", assertThrows(JdbcContentService.ContentException.class,
                    () -> content.saveChapter(owner, chapter, "不能覆盖", 2, accepted)).code());
            assertEquals(2, content.chapter(owner, chapter).get("version"));
            assertEquals(2, content.revisions(owner, chapter, 20).size());
        });
    }

    private static String proposal(PGSimpleDataSource source, String owner, String project, String chapter,
                                    int version, String proposed) throws Exception {
        var admission = new JdbcRunAdmission(source, Clock.systemUTC(), UUID.randomUUID());
        var receipt = admission.create(new JdbcRunAdmission.Request(owner, project, chapter, version,
                "检查正文", UUID.randomUUID().toString()), new JdbcRunAdmission.Config("System", "[]", "model", Map.of()));
        String id = UUID.randomUUID().toString();
        try (Connection c = source.getConnection()) {
            try (PreparedStatement s = c.prepareStatement("UPDATE tb_agent_run SET status = 'SUCCEEDED', finished_at = now() WHERE id = ?::uuid")) {
                s.setString(1, receipt.runId()); s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("UPDATE tb_project SET active_run_id = NULL WHERE id = ?::uuid")) {
                s.setString(1, project); s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO tb_edit_proposal(id, run_id, project_id, target_chapter_id, base_version,
                                                 base_content, proposed_content, reason, status)
                    SELECT ?::uuid, id, project_id, target_chapter_id, base_version, base_content, ?, '修正矛盾', 'PENDING'
                      FROM tb_agent_run WHERE id = ?::uuid
                    """)) {
                s.setString(1, id); s.setString(2, proposed); s.setString(3, receipt.runId()); s.executeUpdate();
            }
        }
        return id;
    }
    @Test void projectSearchCoversAllPagesAndBindsCursorsToItsKeyword() throws Exception {
        withDatabase(source -> {
            Flyway.configure().dataSource(source).load().migrate();
            JdbcContentService content = new JdbcContentService(source);
            String owner = "search-author";
            String first = (String) content.createProject(owner, "林舟 First").get("id");
            String second = (String) content.createProject(owner, "林舟 Second").get("id");
            content.createProject(owner, "其他作品");
            content.createProject("another-author", "林舟 Third");
            String deleted = (String) content.createProject(owner, "林舟 Deleted").get("id");
            content.deleteProject(owner, deleted);
            Page<Map<String,Object>> page = content.projectPage(owner, "林舟", null, 1);
            assertNotNull(page.nextCursor());
            Page<Map<String,Object>> next = content.projectPage(owner, "林舟", page.nextCursor(), 1);
            assertEquals(Set.of(first, second), Set.of(page.items().getFirst().get("id"), next.items().getFirst().get("id")));
            assertNull(next.nextCursor());
            assertThrows(IllegalArgumentException.class,
                    () -> content.projectPage(owner, "其他", page.nextCursor(), 1));
            assertThrows(IllegalArgumentException.class,
                    () -> content.projectPage(owner, null, page.nextCursor(), 1));
            assertEquals(first, content.projectPage(owner, " first ", null, 20).items().getFirst().get("id"));
            assertTrue(content.projectPage(owner, "不存在", null, 20).items().isEmpty());
            String literal = (String) content.createProject(owner, "完成 100%_初稿").get("id");
            assertEquals(literal, content.projectPage(owner, "%_", null, 20).items().getFirst().get("id"));
            assertThrows(JdbcContentService.ContentException.class,
                    () -> content.projectPage(owner, "墨".repeat(81), null, 20));
        });
    }

    @Test void authorWritesPreserveHistoryAndFreezeSettingsDuringRun() throws Exception {
        withDatabase(source -> {
            Flyway.configure().dataSource(source).load().migrate();
            JdbcContentService content = new JdbcContentService(source);
            String owner = "author";
            String project = (String) content.createProject(owner, "作品").get("id");
            String otherProject = (String) content.createProject(owner, "另一本").get("id");
            Page<Map<String,Object>> firstProjectPage = content.projectPage(owner, null, 1);
            Page<Map<String,Object>> secondProjectPage = content.projectPage(owner, firstProjectPage.nextCursor(), 1);
            assertEquals(Set.of(project, otherProject), Set.of(firstProjectPage.items().getFirst().get("id"),
                    secondProjectPage.items().getFirst().get("id")));
            assertNull(secondProjectPage.nextCursor());
            assertDoesNotThrow(() -> java.time.Instant.parse((String) firstProjectPage.items().getFirst().get("updatedAt")));
            String chapter = (String) content.createChapter(owner, project, "第一章").get("id");
            String secondChapter = (String) content.createChapter(owner, project, "第二章").get("id");
            Page<Map<String,Object>> firstChapterPage = content.chapterPage(owner, project, null, 1);
            assertEquals(chapter, firstChapterPage.items().getFirst().get("id"));
            Page<Map<String,Object>> secondChapterPage = content.chapterPage(owner, project, firstChapterPage.nextCursor(), 1);
            assertEquals(secondChapter, secondChapterPage.items().getFirst().get("id"));
            assertNull(secondChapterPage.nextCursor());
            assertThrows(IllegalArgumentException.class, () -> content.chapterPage(owner, project,
                    firstChapterPage.nextCursor() + "tampered", 1));
            assertEquals(1, content.chapter(owner, chapter).get("version"));
            Map<String,Object> saved = content.saveChapter(owner, chapter, "林舟不会游泳。", 1);
            assertEquals(2, saved.get("version"));
            assertEquals(2, content.saveChapter(owner, chapter, "林舟不会游泳。", 2).get("version"));
            assertEquals(2, content.revisions(owner, chapter, 20).size());
            Page<Map<String,Object>> firstRevisionPage = content.revisionPage(owner, chapter, null, 1);
            assertEquals(2, firstRevisionPage.items().getFirst().get("version"));
            Page<Map<String,Object>> secondRevisionPage = content.revisionPage(owner, chapter, firstRevisionPage.nextCursor(), 1);
            assertEquals(1, secondRevisionPage.items().getFirst().get("version"));
            assertNull(secondRevisionPage.nextCursor());
            assertEquals("", content.revision(owner, chapter, 1).get("content"));
            assertEquals("林舟不会游泳。", content.revision(owner, chapter, 2).get("content"));
            JdbcContentService.ContentException conflict = assertThrows(JdbcContentService.ContentException.class,
                    () -> content.saveChapter(owner, chapter, "覆盖", 1));
            assertEquals("VERSION_CONFLICT", conflict.code());
            String setting = (String) content.createSetting(owner, project, "CHARACTER", "林舟", "不会游泳").get("id");
            content.createSetting(owner, project, "WORLD", "河流", "水很深");
            assertEquals(2, content.settings(owner, project, null, null, null).size());
            Page<Map<String,Object>> firstSettingPage = content.settingPage(owner, project, null, null, null, 1);
            assertNotNull(firstSettingPage.nextCursor());
            assertEquals(1, content.settingPage(owner, project, null, null, firstSettingPage.nextCursor(), 1).items().size());
            assertThrows(IllegalArgumentException.class, () -> content.settingPage(owner, project, "CHARACTER", null,
                    firstSettingPage.nextCursor(), 1));
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
            assertEquals(2, repository.chapters(project, 2000).size());
            assertEquals("林舟不会游泳。", repository.chapterRevision(project, chapter, 2, 2000).content());
            assertEquals(2, repository.settings(project, 2000).size());
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
