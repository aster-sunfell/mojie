package com.mojie.proposal;

import static org.junit.jupiter.api.Assertions.*;

import com.mojie.common.error.BusinessException;
import com.mojie.common.repository.jdbc.JdbcAccess;
import com.mojie.config.ContentConfiguration;
import com.mojie.conversation.repository.jdbc.JdbcConversationRepository;
import com.mojie.proposal.model.ProposalReview.*;
import com.mojie.proposal.repository.jdbc.JdbcProposalRepository;
import com.mojie.support.*;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class ProposalInboxIT {
  private static final String BASE = "甲。\n\n乙。\n";
  private static final String CANDIDATE = "新甲。\n\n新乙。\n";

  @Test
  void allHistoricalConversationsAreCountedAndPaginationIsScopedAndComplete() throws Exception {
    try (var database = new TestDatabase()) {
      var f = new Fixture(database.source());
      var ids = new HashSet<String>();
      for (int i = 0; i < 55; i++) ids.add(f.proposal(f.conversation(), "SUCCEEDED"));
      String failed = f.proposal(f.conversation(), "FAILED");
      String rejected = f.proposal(f.conversation(), "SUCCEEDED");
      f.content.proposals().reject(rejected);
      var inbox = f.content.proposalInbox();
      assertEquals(55, inbox.counts(f.project).pendingCount());
      assertEquals(1, inbox.counts(f.project).targetCount());
      assertEquals(55L, f.content.projects().projectPage(null, 20).items().getFirst().get("pendingProposalCount"));
      var first = inbox.page(f.project, "all", null, 20);
      assertNotNull(first.nextCursor());
      var seen = new HashSet<String>();
      String cursor = null;
      do {
        var page = inbox.page(f.project, "all", cursor, 20);
        for (var item : page.items()) {
          assertTrue(seen.add(item.get("id").toString()));
          assertEquals(2L, item.get("remainingItemCount"));
          assertNotNull(item.get("conversationId"));
          assertEquals("旧讨论", item.get("conversationTitle"));
          assertFalse(item.containsKey("baseContent"));
          assertFalse(item.containsKey("proposedContent"));
          assertFalse(item.containsKey("reviewDecisions"));
        }
        cursor = page.nextCursor();
      } while (cursor != null);
      assertEquals(ids, seen);
      assertFalse(seen.contains(failed));
      assertFalse(seen.contains(rejected));
      assertThrows(IllegalArgumentException.class, () -> inbox.page(f.project, "pending", first.nextCursor(), 20));
      String other = f.content.projects().createProject("另一个作品").get("id").toString();
      assertEquals(0, inbox.counts(other).pendingCount());
      assertTrue(inbox.page(other, "all", null, 20).items().isEmpty());
      assertThrows(IllegalArgumentException.class, () -> inbox.page(other, "all", first.nextCursor(), 20));
    }
  }

  @Test
  void partialDecisionsAndConflictsUpdateProjectAndConversationCountsOnlyAfterSaving() throws Exception {
    try (var database = new TestDatabase()) {
      var f = new Fixture(database.source());
      String conversation = f.conversation();
      String partial = f.proposal(conversation, "SUCCEEDED");
      String conflict = f.proposal(conversation, "SUCCEEDED");
      var inbox = f.content.proposalInbox();
      var conversations = new JdbcConversationRepository(database.source());
      assertEquals(2, conversations.get(conversation, false).pendingProposalCount());
      assertEquals(2, conversations.page(f.project, null, null, 1).getFirst().pendingProposalCount());
      f.content.saves().saveChapter(f.chapter, BASE.replace("甲", "新甲"), 2,
          List.of(new Review(partial, List.of(new Choice(0, "APPLIED")))));
      assertEquals(2, inbox.counts(f.project).pendingCount());
      var partialItem = inbox.page(f.project, "all", null, 20).items().stream()
          .filter(item -> partial.equals(item.get("id"))).findFirst().orElseThrow();
      assertEquals("PARTIALLY_APPLIED", partialItem.get("status"));
      assertEquals(1L, partialItem.get("remainingItemCount"));
      f.proposals.markConflicted(conflict);
      assertEquals(1, inbox.counts(f.project).pendingCount());
      assertEquals(1, inbox.counts(f.project).conflictedCount());
      assertEquals(1, inbox.page(f.project, "pending", null, 20).items().size());
      assertEquals(conflict, inbox.page(f.project, "conflicted", null, 20).items().getFirst().get("id"));
      assertEquals(1, conversations.get(conversation, false).conflictedProposalCount());
      f.content.proposals().reject(partial);
      f.content.proposals().reject(conflict);
      assertEquals(0, inbox.counts(f.project).pendingCount());
      assertEquals(0, inbox.counts(f.project).conflictedCount());
      assertEquals(0, conversations.get(conversation, false).pendingProposalCount());
      assertTrue(inbox.page(f.project, "all", null, 20).items().isEmpty());
      f.content.projects().deleteProject(f.project);
      assertEquals("RESOURCE_NOT_FOUND", assertThrows(BusinessException.class, () -> inbox.counts(f.project)).code());
      assertEquals("RESOURCE_NOT_FOUND", assertThrows(BusinessException.class, () -> inbox.page(f.project, "all", null, 20)).code());
    }
  }

  @Test
  void newChapterAndSettingDraftsAreListedWithoutRequiringAnExistingTarget() throws Exception {
    try (var database = new TestDatabase()) {
      var f = new Fixture(database.source());
      String run = f.run(f.conversation(), "SUCCEEDED");
      String chapterDraft = UUID.randomUUID().toString(), settingDraft = UUID.randomUUID().toString();
      f.proposals.insertCreate(chapterDraft, run, f.project, "CHAPTER", null, "新章节", "新章节正文", "开篇");
      f.proposals.insertCreate(settingDraft, run, f.project, "SETTING", "WORLD", "新世界", "设定正文", "背景");
      var inbox = f.content.proposalInbox();
      assertEquals(2, inbox.counts(f.project).targetCount());
      var items = inbox.page(f.project, "all", null, 20).items();
      assertEquals(2, items.size());
      assertTrue(items.stream().allMatch(item -> "CREATE".equals(item.get("operation"))));
      assertTrue(items.stream().allMatch(item -> item.get("targetChapterId") == null && item.get("targetSettingId") == null));
      assertTrue(items.stream().allMatch(item -> item.get("remainingItemCount") == null));
      assertEquals(Set.of("新章节", "新世界"), new HashSet<>(items.stream().map(item -> item.get("targetTitle")).toList()));
      assertThrows(BusinessException.class, () -> inbox.page(f.project, "unknown", null, 20));
      assertThrows(BusinessException.class, () -> inbox.page(f.project, "all", null, 51));
    }
  }

  private static final class Fixture {
    final ContentConfiguration.Services content;
    final JdbcAccess db;
    final JdbcProposalRepository proposals;
    final String project, chapter;

    Fixture(DataSource source) {
      Flyway.configure().dataSource(source).load().migrate();
      content = TestServices.content(source);
      db = new JdbcAccess(source);
      proposals = new JdbcProposalRepository(source);
      project = content.projects().createProject("建议汇总测试").get("id").toString();
      chapter = content.chapters().createChapter(project, "第二章").get("id").toString();
      content.saves().saveChapter(chapter, BASE, 1);
      db.update("INSERT INTO tb_agent_prompt_snapshot(hash,kind,content) VALUES"
          + "(repeat('a',64),'SYSTEM_PROMPT','测试'),(repeat('b',64),'TOOL_SCHEMA','[]')");
    }

    String conversation() {
      return db.first("INSERT INTO tb_conversation(project_id,title,model_id,model_config)"
          + " VALUES(?::uuid,'旧讨论','test','{}') RETURNING id", project).get("id").toString();
    }

    String run(String conversation, String status) {
      return db.first("""
          INSERT INTO tb_agent_run(project_id,target_chapter_id,base_version,base_content,
              conversation_id,instruction,client_request_id,request_digest,status,deadline_at,finished_at,
              model_id,prompt_hash,tool_schema_hash,config_snapshot,task_type,created_at)
          VALUES(?::uuid,?::uuid,2,?,?::uuid,'检查正文',?::uuid,repeat('c',64),?,now(),now(),
              'test',repeat('a',64),repeat('b',64),'{}','CHAPTER_EDIT','2026-01-01T00:00:00Z') RETURNING id
          """, project, chapter, BASE, conversation, UUID.randomUUID().toString(), status).get("id").toString();
    }

    String proposal(String conversation, String status) {
      String id = UUID.randomUUID().toString();
      proposals.insert(id, run(conversation, status), project, chapter, 2, BASE, CANDIDATE, "修正内容");
      db.update("UPDATE tb_edit_proposal SET created_at='2026-01-01T00:00:00Z' WHERE id=?::uuid", id);
      return id;
    }
  }
}
