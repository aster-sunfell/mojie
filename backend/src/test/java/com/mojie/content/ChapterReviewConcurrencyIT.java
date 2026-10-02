package com.mojie.content;

import static org.junit.jupiter.api.Assertions.*;

import com.mojie.agent.model.RunAdmission.*;
import com.mojie.common.error.BusinessException;
import com.mojie.common.repository.jdbc.JdbcAccess;
import com.mojie.proposal.model.ProposalReview.*;
import com.mojie.support.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class ChapterReviewConcurrencyIT {
  @Test
  void reversedMultiProposalSavesSerializeAndLinkOnlyOneRevision() throws Exception {
    try (TestDatabase database = new TestDatabase()) {
      DataSource source = database.source();
      Flyway.configure().dataSource(source).load().migrate();
      var content = TestServices.content(source);
      String project = content.projects().createProject("owner", "并发审核").get("id").toString();
      String chapter =
          content.chapters().createChapter("owner", project, "目标章").get("id").toString();
      content.saves().saveChapter("owner", chapter, "原文", 1);
      String first = proposal(source, project, chapter, "第一份建议");
      String second = proposal(source, project, chapter, "第二份建议");
      List<Review> forward =
          List.of(
              new Review(first, List.of(new Choice(0, "APPLIED"))),
              new Review(second, List.of(new Choice(0, "APPLIED"))));
      List<Review> reverse = List.of(forward.getLast(), forward.getFirst());
      var start = new CountDownLatch(1);
      try (ExecutorService contenders = Executors.newFixedThreadPool(2)) {
        List<Future<Object>> results = new ArrayList<>();
        for (List<Review> reviews : List.of(forward, reverse))
          results.add(
              contenders.submit(
                  () -> {
                    start.await();
                    try {
                      return content
                          .saves()
                          .saveChapter("owner", chapter, "两份建议合并后的正文", 2, reviews);
                    } catch (BusinessException conflict) {
                      return conflict.code();
                    }
                  }));
        start.countDown();
        List<Object> outcomes =
            List.of(
                results.get(0).get(5, TimeUnit.SECONDS), results.get(1).get(5, TimeUnit.SECONDS));
        assertEquals(1, outcomes.stream().filter(Map.class::isInstance).count());
        assertEquals(1, outcomes.stream().filter("VERSION_CONFLICT"::equals).count());
      }
      assertEquals(3, content.chapters().chapter("owner", chapter).get("version"));
      Map<String, Object> revision = content.chapters().revision("owner", chapter, 3);
      assertEquals("MIXED", revision.get("source"));
      assertEquals(2, ((List<?>) revision.get("proposalReviews")).size());
      assertEquals(3, content.chapters().revisions("owner", chapter, 20).size());
    }
  }

  @Test
  void invalidLaterReviewRollsBackEarlierDecisionContentAndAllLinks() throws Exception {
    try (TestDatabase database = new TestDatabase()) {
      DataSource source = database.source();
      Flyway.configure().dataSource(source).load().migrate();
      var content = TestServices.content(source);
      String project = content.projects().createProject("owner", "原子审核").get("id").toString();
      String chapter =
          content.chapters().createChapter("owner", project, "目标章").get("id").toString();
      content.saves().saveChapter("owner", chapter, "原文", 1);
      String first = proposal(source, project, chapter, "第一份建议");
      String second = proposal(source, project, chapter, "第二份建议");
      var reviews =
          List.of(
              new Review(first, List.of(new Choice(0, "APPLIED"))),
              new Review(second, List.of(new Choice(99, "APPLIED"))));
      assertEquals(
          "VALIDATION_ERROR",
          assertThrows(
                  BusinessException.class,
                  () -> content.saves().saveChapter("owner", chapter, "应当回滚的正文", 2, reviews))
              .code());
      assertEquals("原文", content.chapters().chapter("owner", chapter).get("content"));
      assertEquals(2, content.chapters().chapter("owner", chapter).get("version"));
      assertEquals(2, content.chapters().revisions("owner", chapter, 20).size());
      for (String proposal : List.of(first, second))
        assertEquals("PENDING", content.proposals().detail("owner", proposal).get("status"));
      var db = new JdbcAccess(source);
      assertTrue(
          db.query("SELECT * FROM tb_chapter_revision_review WHERE chapter_id = ?::uuid", chapter)
              .isEmpty());
    }
  }

  private static String proposal(
      DataSource source, String project, String chapter, String proposed) {
    var admission = TestServices.admission(source, Clock.systemUTC(), UUID.randomUUID());
    var receipt =
        admission.create(
            new Request("owner", project, chapter, 2L, "审核", UUID.randomUUID().toString()),
            new Config("System", "[]", "scripted", Map.of()));
    var db = new JdbcAccess(source);
    String proposal = UUID.randomUUID().toString();
    TestServices.transactions(source)
        .required(
            () -> {
              db.update(
                  "UPDATE tb_agent_run SET status = 'SUCCEEDED', finished_at = now() WHERE id ="
                      + " ?::uuid",
                  receipt.runId());
              db.update(
                  "UPDATE tb_project SET active_run_id = NULL WHERE id = ?::uuid AND active_run_id"
                      + " = ?::uuid",
                  project,
                  receipt.runId());
              db.update(
                  """
INSERT INTO tb_edit_proposal(id, run_id, project_id, target_chapter_id, base_version,
        base_content, proposed_content, reason, status)
SELECT ?::uuid, id, project_id, target_chapter_id, base_version, base_content, ?, '审核', 'PENDING'
FROM tb_agent_run WHERE id = ?::uuid
""",
                  proposal,
                  proposed,
                  receipt.runId());
              return null;
            });
    return proposal;
  }
}
