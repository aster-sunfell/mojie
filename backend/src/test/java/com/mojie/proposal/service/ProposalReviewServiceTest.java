package com.mojie.proposal.service;

import static org.junit.jupiter.api.Assertions.*;

import com.mojie.common.error.BusinessException;
import com.mojie.proposal.model.*;
import com.mojie.proposal.model.ProposalReview.*;
import com.mojie.proposal.repository.ProposalRepository;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProposalReviewServiceTest {
  @Test
  void editedAppliedChoicesRemainImmutableAndRetriesDoNotWriteAgain() {
    String id = UUID.randomUUID().toString();
    AtomicReference<Locked> stored =
        new AtomicReference<>(new Locked("甲\n间隔\n乙\n", "A\n间隔\nB\n", "PENDING", "{}"));
    ProposalRepository repository =
        (ProposalRepository)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {ProposalRepository.class},
                (proxy, method, arguments) ->
                    switch (method.getName()) {
                      case "lockReview" -> stored.get();
                      case "updateReview" -> {
                        Locked old = stored.get();
                        stored.set(
                            new Locked(
                                old.baseContent(),
                                old.proposedContent(),
                                (String) arguments[2],
                                (String) arguments[1]));
                        yield 1;
                      }
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    var reviews =
        new ProposalReviewService(repository, (chapter, version, proposal, decisions) -> 1);
    var first = List.of(new Review(id, List.of(new Choice(0, "APPLIED", "自定义\r\n文本"))));
    List<Saved> saved = reviews.save(3, first, reviews.lockReviews("owner", "chapter", first));
    assertEquals(1, saved.size());
    assertTrue(saved.getFirst().applied());
    assertEquals("PARTIALLY_APPLIED", stored.get().status());
    assertTrue(stored.get().reviewDecisions().contains("自定义\\n文本"));
    String committed = stored.get().reviewDecisions();
    assertTrue(reviews.save(4, first, reviews.lockReviews("owner", "chapter", first)).isEmpty());
    assertEquals(committed, stored.get().reviewDecisions());
    var changed = List.of(new Review(id, List.of(new Choice(0, "APPLIED", "替换已确认文本"))));
    assertEquals(
        "PROPOSAL_STATE_CONFLICT",
        assertThrows(
                BusinessException.class,
                () -> reviews.save(4, changed, reviews.lockReviews("owner", "chapter", changed)))
            .code());
    var ignore = List.of(new Review(id, List.of(new Choice(1, "IGNORED"))));
    assertFalse(
        reviews
            .save(4, ignore, reviews.lockReviews("owner", "chapter", ignore))
            .getFirst()
            .applied());
    assertEquals("APPLIED", stored.get().status());
    assertTrue(stored.get().reviewDecisions().contains("\"savedVersion\":3"));
  }
}
