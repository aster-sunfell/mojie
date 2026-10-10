package com.mojie.content.api;

import com.mojie.content.service.*;
import com.mojie.proposal.model.ProposalReview.*;
import com.mojie.proposal.service.ChapterSaveCoordinator;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public final class ChapterController {
  private final ChapterService chapters;

  private final ChapterSaveCoordinator saves;

  public ChapterController(ChapterService chapters, ChapterSaveCoordinator saves) {
    this.chapters = chapters;
    this.saves = saves;
  }

  private static Map<String, Object> data(Object value) {
    return Map.of("data", value);
  }

  public record Title(String title) {}

  public record ChoiceRequest(Integer itemId, String status, String editedContent) {}

  public record ReviewRequest(String proposalId, List<ChoiceRequest> decisions) {
    Review command() {
      return new Review(
          proposalId,
          decisions == null
              ? null
              : decisions.stream()
                  .map(
                      c -> c == null ? null : new Choice(c.itemId(), c.status(), c.editedContent()))
                  .toList());
    }
  }

  public record Body(
      String content, Integer expectedVersion, List<ReviewRequest> proposalReviews) {}

  @GetMapping("/projects/{id}/chapters")
  public Map<String, Object> chapters(
      @PathVariable String id,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return data(chapters.chapterPage(id, cursor, limit));
  }

  @PostMapping("/projects/{id}/chapters")
  public ResponseEntity<Map<String, Object>> createChapter(
      @PathVariable String id, @RequestBody Title body) {
    return ResponseEntity.status(201).body(data(chapters.createChapter(id, body.title())));
  }

  @GetMapping("/chapters/{id}")
  public Map<String, Object> chapter(@PathVariable String id) {
    return data(chapters.chapter(id));
  }

  @PatchMapping("/chapters/{id}")
  public Map<String, Object> renameChapter(@PathVariable String id, @RequestBody Title body) {
    return data(chapters.renameChapter(id, body.title()));
  }

  @PutMapping("/chapters/{id}/content")
  public Map<String, Object> saveChapter(@PathVariable String id, @RequestBody Body body) {
    if (body.expectedVersion() == null)
      throw new IllegalArgumentException("expectedVersion is required");
    return data(
        saves.saveChapter(
            id,
            body.content(),
            body.expectedVersion(),
            body.proposalReviews() == null
                ? null
                : body.proposalReviews().stream()
                    .map(r -> r == null ? null : r.command())
                    .toList()));
  }

  @DeleteMapping("/chapters/{id}")
  public ResponseEntity<Void> deleteChapter(@PathVariable String id) {
    chapters.deleteChapter(id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/chapters/{id}/revisions")
  public Map<String, Object> revisions(
      @PathVariable String id,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return data(chapters.revisionPage(id, cursor, limit));
  }

  @GetMapping("/chapters/{id}/revisions/{version}")
  public Map<String, Object> revision(@PathVariable String id, @PathVariable int version) {
    return data(chapters.revision(id, version));
  }
}
