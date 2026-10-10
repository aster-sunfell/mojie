package com.mojie.conversation.api;

import com.mojie.conversation.model.ConversationModels.*;
import com.mojie.conversation.service.ConversationService;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public final class ConversationController {
  private final ConversationService service;

  public ConversationController(ConversationService s) {
    service = s;
  }

  @PostMapping("/projects/{id}/conversations")
  public ResponseEntity<Conversation> create(@PathVariable String id) {
    return ResponseEntity.status(201).body(service.create(id));
  }

  @GetMapping("/projects/{id}/conversations")
  public Object list(
      @PathVariable String id,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") int limit) {
    return service.page(id, status, cursor, limit);
  }

  @GetMapping("/conversations/{id}")
  public Conversation get(@PathVariable String id) {
    return service.get(id);
  }

  public record Patch(
      Long expectedVersion, String title, String status, Boolean discoveryEnabled) {}

  @PatchMapping("/conversations/{id}")
  public Conversation patch(@PathVariable String id, @RequestBody Patch p) {
    if (p.expectedVersion() == null) throw new IllegalArgumentException("expectedVersion required");
    return service.patch(
        id, p.expectedVersion(), p.title(), p.status(), p.discoveryEnabled());
  }

  @GetMapping("/conversations/{id}/messages")
  public Object messages(
      @PathVariable String id,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") int limit) {
    return service.messages(id, cursor, limit);
  }

  @PostMapping("/conversations/{id}/messages")
  public ResponseEntity<Receipt> send(@PathVariable String id, @RequestBody Send p) {
    return ResponseEntity.accepted().body(service.send(id, p));
  }

  public record Retry(String clientRequestId) {}

  @PostMapping("/conversations/{id}/messages/{message}/retry")
  public ResponseEntity<Receipt> retry(
      @PathVariable String id, @PathVariable String message, @RequestBody Retry p) {
    return ResponseEntity.accepted()
        .body(service.retry(id, message, p.clientRequestId()));
  }

  @GetMapping("/conversations/{id}/skills")
  public Object pins(@PathVariable String id) {
    return service.pins(id);
  }

  @PutMapping("/conversations/{id}/skills")
  public Object pins(@PathVariable String id, @RequestBody List<Selection> p) {
    return service.pins(id, p);
  }
}
