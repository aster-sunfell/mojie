package com.mojie.content.api;

import com.mojie.content.service.*;
import com.mojie.proposal.model.ProposalReview.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public final class SettingController {
  private final SettingService settings;

  public SettingController(SettingService settings) {
    this.settings = settings;
  }

  private static Map<String, Object> data(Object value) {
    return Map.of("data", value);
  }

  public record Title(String title) {}

  public record Setting(String type, String title, String content) {}

  @GetMapping("/projects/{id}/settings")
  public Map<String, Object> settings(
      @PathVariable String id,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return data(settings.settingPage(id, type, keyword, cursor, limit));
  }

  @PostMapping("/projects/{id}/settings")
  public ResponseEntity<Map<String, Object>> createSetting(
      @PathVariable String id, @RequestBody Setting body) {
    return ResponseEntity.status(201)
        .body(data(settings.createSetting(id, body.type(), body.title(), body.content())));
  }

  @PatchMapping("/settings/{id}")
  public Map<String, Object> updateSetting(@PathVariable String id, @RequestBody Setting body) {
    return data(settings.updateSetting(id, body.type(), body.title(), body.content()));
  }

  @DeleteMapping("/settings/{id}")
  public ResponseEntity<Void> deleteSetting(@PathVariable String id) {
    settings.deleteSetting(id);
    return ResponseEntity.noContent().build();
  }
}
