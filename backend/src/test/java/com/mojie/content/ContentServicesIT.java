package com.mojie.content;

import com.mojie.support.*;
import com.mojie.agent.service.*;
import com.mojie.agent.model.*;
import com.mojie.agent.model.RunAdmission.*;
import com.mojie.agent.service.tool.ToolContentService;
import com.mojie.common.error.*;
import com.mojie.proposal.service.ProposalService;
import com.mojie.proposal.model.*;
import com.mojie.agent.service.tool.*;
import com.mojie.common.pagination.Page;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ContentServicesIT {
    @Test void editedSuggestionTextIsPersistedWithoutChangingTheOriginalCandidate() throws Exception {
        withDatabase(source -> {
            Flyway.configure().dataSource(source).load().migrate();
            ContentFixture content = new ContentFixture(source);

            String project = (String) content.createProject("行内编辑").get("id");
            String chapter = (String) content.createChapter(project, "第一章").get("id");
            String base = "林舟游过河。\n\n他拿起红灯。\n";
            content.saveChapter(chapter, base, 1);
            String candidate = base.replace("游过河", "走过桥").replace("红", "蓝");
            String proposal = proposal(source, project, chapter, 2, candidate);
            String edited = "傍晚，林舟沿山路走到旧桥。";
            var reviews = List.of(new ProposalReview.Review(proposal,
                    List.of(new ProposalReview.Choice(0, "APPLIED", edited))));
            String body = base.replace("林舟游过河。", edited + "他检查了桥板。");
            content.saveChapter(chapter, body, 2, reviews);
            Map<String,Object> detail = new QueryFixture(source).proposal(proposal);
            assertEquals(candidate, detail.get("proposedContent"));
            assertEquals("PARTIALLY_APPLIED", detail.get("status"));
            @SuppressWarnings("unchecked") var items = (List<Map<String,Object>>) detail.get("items");
            assertEquals(edited, items.getFirst().get("editedContent"));
            assertEquals("APPLIED", items.getFirst().get("status"));
            assertEquals("PENDING", items.getLast().get("status"));
            assertEquals(3, content.saveChapter(chapter, body, 3, reviews).get("version"));
            var changedReview = List.of(new ProposalReview.Review(proposal,
                    List.of(new ProposalReview.Choice(0, "APPLIED", "改变已保存的确认稿"))));
            assertEquals("PROPOSAL_STATE_CONFLICT", assertThrows(BusinessException.class,
                    () -> content.saveChapter(chapter, "不得覆盖", 3, changedReview)).code());
            assertEquals(body, content.chapter(chapter).get("content"));
            var invalid = List.of(new ProposalReview.Review(proposal,
                    List.of(new ProposalReview.Choice(1, "IGNORED", "忽略不能提交确认稿"))));
            assertEquals("VALIDATION_ERROR", assertThrows(BusinessException.class,
                    () -> content.saveChapter(chapter, body, 3, invalid)).code());
        });
    }
    @Test void partialReviewSavesSurviveManualEditsAndKeepEveryRelatedRevision() throws Exception {
        withDatabase(source -> {
            Flyway.configure().dataSource(source).load().migrate();
            ContentFixture content = new ContentFixture(source);

            String project = (String) content.createProject("修改审阅").get("id");
            String chapter = (String) content.createChapter(project, "第一章").get("id");
            String base = "林舟游过河。\n\n他拿起红灯。\n";
            content.saveChapter(chapter, base, 1);
            String proposal = proposal(source, project, chapter, 2, base.replace("游过河", "走过桥").replace("红", "蓝"));
            QueryFixture query = new QueryFixture(source);
            assertEquals(2, ((List<?>) query.proposal(proposal).get("items")).size());
            var first = List.of(new ProposalReview.Review(proposal, List.of(new ProposalReview.Choice(0, "APPLIED"))));
            String partial = base.replace("游过河", "走过桥") + "用户自己补写。";
            assertEquals(3, content.saveChapter(chapter, partial, 2, first).get("version"));
            assertEquals("PARTIALLY_APPLIED", query.proposal(proposal).get("status"));
            assertEquals("MIXED", content.revision(chapter, 3).get("source"));
            assertEquals(proposal, content.revision(chapter, 3).get("proposalId"));
            // A retry cannot create another revision or reverse a saved decision.
            assertEquals(3, content.saveChapter(chapter, partial, 3, first).get("version"));
            assertEquals("PROPOSAL_STATE_CONFLICT", assertThrows(BusinessException.class,
                    () -> TestServices.proposals(source).accept(proposal)).code());
            String manual = partial + "继续手动完善。";
            content.saveChapter(chapter, manual, 3);
            assertEquals("PARTIALLY_APPLIED", query.proposal(proposal).get("status"));
            var second = List.of(new ProposalReview.Review(proposal, List.of(new ProposalReview.Choice(1, "APPLIED"))));
            assertEquals(5, content.saveChapter(chapter, manual.replace("红", "蓝"), 4, second).get("version"));
            assertEquals("APPLIED", query.proposal(proposal).get("status"));
            assertEquals(5, query.proposal(proposal).get("appliedVersion"));
            assertEquals("MIXED", content.revision(chapter, 5).get("source"));
            assertEquals(1, ((List<?>) content.revision(chapter, 5).get("proposalReviews")).size());
            assertTrue(content.chapter(chapter).get("content").toString().endsWith("用户自己补写。继续手动完善。"));
        });
    }

    @Test void reviewChoicesValidateScopeAndRollBackTogetherWithTextOnConflict() throws Exception {
        withDatabase(source -> {
            Flyway.configure().dataSource(source).load().migrate();
            ContentFixture content = new ContentFixture(source);

            String project = (String) content.createProject("修改审阅").get("id");
            String chapter = (String) content.createChapter(project, "第一章").get("id");
            content.saveChapter(chapter, "旧文", 1);
            String proposal = proposal(source, project, chapter, 2, "新文");
            var accepted = List.of(new ProposalReview.Review(proposal, List.of(new ProposalReview.Choice(0, "APPLIED"))));
            assertEquals("VERSION_CONFLICT", assertThrows(BusinessException.class,
                    () -> content.saveChapter(chapter, "不能覆盖", 1, accepted)).code());
            assertEquals("VALIDATION_ERROR", assertThrows(BusinessException.class,
                    () -> content.saveChapter(chapter, "不能覆盖", 2,
                            List.of(new ProposalReview.Review(proposal, List.of(new ProposalReview.Choice(99, "APPLIED")))))).code());
            assertEquals("RESOURCE_NOT_FOUND", assertThrows(BusinessException.class,
                    () -> content.saveChapter(UUID.randomUUID().toString(), "不能覆盖", 2, accepted)).code());
            String other = (String) content.createChapter(project, "第二章").get("id");
            assertEquals("RESOURCE_NOT_FOUND", assertThrows(BusinessException.class,
                    () -> content.saveChapter(other, "不能覆盖", 1, accepted)).code());
            assertEquals("旧文", content.chapter(chapter).get("content"));
            assertEquals("PENDING", new QueryFixture(source).proposal(proposal).get("status"));
            var ignored = List.of(new ProposalReview.Review(proposal, List.of(new ProposalReview.Choice(0, "IGNORED"))));
            // Ignoring a suggestion alone keeps the text and chapter version unchanged.
            assertEquals(2, content.saveChapter(chapter, "旧文", 2, ignored).get("version"));
            assertEquals("REJECTED", new QueryFixture(source).proposal(proposal).get("status"));
            assertEquals("PROPOSAL_STATE_CONFLICT", assertThrows(BusinessException.class,
                    () -> content.saveChapter(chapter, "不能覆盖", 2, accepted)).code());
            assertEquals(2, content.chapter(chapter).get("version"));
            assertEquals(2, content.revisions(chapter, 20).size());
        });
    }

    private static String proposal(PGSimpleDataSource source, String project, String chapter,
                                    int version, String proposed) throws Exception {
        var admission = TestServices.admission(source, Clock.systemUTC(), UUID.randomUUID());
        var receipt = admission.create(new Request(project, chapter, version,
                "检查正文", UUID.randomUUID().toString()), new Config("System", "[]", "model", Map.of()));
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
            ContentFixture content = new ContentFixture(source);

            String first = (String) content.createProject("林舟 First").get("id");
            String second = (String) content.createProject("林舟 Second").get("id");
            content.createProject("其他作品");
            String deleted = (String) content.createProject("林舟 Deleted").get("id");
            content.deleteProject(deleted);
            Page<Map<String,Object>> page = content.projectPage("林舟", null, 1);
            assertNotNull(page.nextCursor());
            Page<Map<String,Object>> next = content.projectPage("林舟", page.nextCursor(), 1);
            assertEquals(Set.of(first, second), Set.of(page.items().getFirst().get("id"), next.items().getFirst().get("id")));
            assertNull(next.nextCursor());
            assertThrows(IllegalArgumentException.class,
                    () -> content.projectPage("其他", page.nextCursor(), 1));
            assertThrows(IllegalArgumentException.class,
                    () -> content.projectPage(null, page.nextCursor(), 1));
            assertEquals(first, content.projectPage(" first ", null, 20).items().getFirst().get("id"));
            assertTrue(content.projectPage("不存在", null, 20).items().isEmpty());
            String literal = (String) content.createProject("完成 100%_初稿").get("id");
            assertEquals(literal, content.projectPage("%_", null, 20).items().getFirst().get("id"));
            assertThrows(BusinessException.class,
                    () -> content.projectPage("墨".repeat(81), null, 20));
        });
    }

    @Test void authorWritesPreserveHistoryAndFreezeSettingsDuringRun() throws Exception {
        withDatabase(source -> {
            Flyway.configure().dataSource(source).load().migrate();
            ContentFixture content = new ContentFixture(source);

            String project = (String) content.createProject("作品").get("id");
            String otherProject = (String) content.createProject("另一本").get("id");
            Page<Map<String,Object>> firstProjectPage = content.projectPage(null, 1);
            Page<Map<String,Object>> secondProjectPage = content.projectPage(firstProjectPage.nextCursor(), 1);
            assertEquals(Set.of(project, otherProject), Set.of(firstProjectPage.items().getFirst().get("id"),
                    secondProjectPage.items().getFirst().get("id")));
            assertNull(secondProjectPage.nextCursor());
            assertDoesNotThrow(() -> java.time.Instant.parse((String) firstProjectPage.items().getFirst().get("updatedAt")));
            String chapter = (String) content.createChapter(project, "第一章").get("id");
            String secondChapter = (String) content.createChapter(project, "第二章").get("id");
            Page<Map<String,Object>> firstChapterPage = content.chapterPage(project, null, 1);
            assertEquals(chapter, firstChapterPage.items().getFirst().get("id"));
            Page<Map<String,Object>> secondChapterPage = content.chapterPage(project, firstChapterPage.nextCursor(), 1);
            assertEquals(secondChapter, secondChapterPage.items().getFirst().get("id"));
            assertNull(secondChapterPage.nextCursor());
            assertThrows(IllegalArgumentException.class, () -> content.chapterPage(project,
                    firstChapterPage.nextCursor() + "tampered", 1));
            assertEquals(1, content.chapter(chapter).get("version"));
            Map<String,Object> saved = content.saveChapter(chapter, "林舟不会游泳。", 1);
            assertEquals(2, saved.get("version"));
            assertEquals(2, content.saveChapter(chapter, "林舟不会游泳。", 2).get("version"));
            assertEquals(2, content.revisions(chapter, 20).size());
            Page<Map<String,Object>> firstRevisionPage = content.revisionPage(chapter, null, 1);
            assertEquals(2, firstRevisionPage.items().getFirst().get("version"));
            Page<Map<String,Object>> secondRevisionPage = content.revisionPage(chapter, firstRevisionPage.nextCursor(), 1);
            assertEquals(1, secondRevisionPage.items().getFirst().get("version"));
            assertNull(secondRevisionPage.nextCursor());
            assertEquals("", content.revision(chapter, 1).get("content"));
            assertEquals("林舟不会游泳。", content.revision(chapter, 2).get("content"));
            BusinessException conflict = assertThrows(BusinessException.class,
                    () -> content.saveChapter(chapter, "覆盖", 1));
            assertEquals("VERSION_CONFLICT", conflict.code());
            String setting = (String) content.createSetting(project, "CHARACTER", "林舟", "不会游泳").get("id");
            content.createSetting(project, "WORLD", "河流", "水很深");
            assertEquals(2, content.settings(project, null, null, null).size());
            Page<Map<String,Object>> firstSettingPage = content.settingPage(project, null, null, null, 1);
            assertNotNull(firstSettingPage.nextCursor());
            assertEquals(1, content.settingPage(project, null, null, firstSettingPage.nextCursor(), 1).items().size());
            assertThrows(IllegalArgumentException.class, () -> content.settingPage(project, "CHARACTER", null,
                    firstSettingPage.nextCursor(), 1));
            UUID processId = UUID.randomUUID();
            RunAdmissionService admission = TestServices.admission(source, Clock.systemUTC(), processId);
            Receipt receipt = admission.create(new Request(project, chapter, 2,
                    "检查设定", UUID.randomUUID().toString()), new Config("System", "[]", "model", Map.of("maxOutputTokens", 1000)));
            assertEquals("QUEUED", receipt.status());
            Map<String,Object> queuedTrace = new QueryFixture(source).trace(receipt.runId());
            assertTrue(queuedTrace.get("messages").toString().contains(chapter));
            assertEquals("RESOURCE_BUSY", assertThrows(BusinessException.class,
                    () -> content.updateSetting(setting, "CHARACTER", "林舟", "会游泳")).code());
            assertEquals("RESOURCE_BUSY", assertThrows(BusinessException.class,
                    () -> content.createChapter(project, "第二章")).code());
            assertEquals(3, content.saveChapter(chapter, "林舟学会了游泳。", 2).get("version"));
            QueryFixture query = new QueryFixture(source);
            assertEquals(1, query.list(project, chapter, null, 20).size());
            Map<String,Object> detail = query.detail(receipt.runId());
            assertEquals(1L, detail.get("lastEventSeq"));
            assertEquals("run.queued", query.events(receipt.runId(), 0, 20).getFirst().type());
            assertEquals(0, query.events(receipt.runId(), 1, 20).size());
            assertThrows(BusinessException.class, () -> query.detail(UUID.randomUUID().toString()));
            RunLifecycleService lifecycle = TestServices.lifecycle(source, TestServices.completion(source, Clock.systemUTC()));
            assertTrue(lifecycle.start(project, receipt.runId(), processId));
            ToolContentService repository = TestServices.toolContent(source);
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
