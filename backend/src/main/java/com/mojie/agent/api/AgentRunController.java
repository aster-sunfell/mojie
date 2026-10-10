package com.mojie.agent.api;

import com.mojie.agent.model.RunAdmission.*;
import com.mojie.agent.service.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public final class AgentRunController {
  private final RunService runs;
  private final RunQueryService query;

  public AgentRunController(RunService runs, RunQueryService query) {
    this.runs = runs;
    this.query = query;
  }

  private static Map<String, Object> data(Object value) {
    return Map.of("data", value);
  }

  public record Submit(
      String projectId,
      String targetChapterId,
      Integer expectedVersion,
      String instruction,
      String clientRequestId) {}

  @PostMapping("/agent/runs")
  public ResponseEntity<Map<String, Object>> submit(@RequestBody Submit body) {
    Receipt receipt =
        runs.submit(
            new Request(
                body.projectId(),
                body.targetChapterId(),
                body.expectedVersion() == null ? null : body.expectedVersion().longValue(),
                body.instruction(),
                body.clientRequestId()));
    return ResponseEntity.status(202)
        .body(data(Map.of("runId", receipt.runId(), "status", receipt.status())));
  }

  @GetMapping("/agent/runs")
  public Map<String, Object> runs(
      @RequestParam String projectId,
      @RequestParam(required = false) String chapterId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false, defaultValue = "20") int limit) {
    return data(query.page(projectId, chapterId, status, cursor, limit));
  }

  @GetMapping("/agent/runs/{id}")
  public Map<String, Object> run(@PathVariable String id) {
    return data(query.detail(id));
  }

  @PostMapping("/agent/runs/{id}/cancel")
  public Map<String, Object> cancel(@PathVariable String id) {
    return data(runs.cancel(id));
  }

  @GetMapping("/agent/runs/{id}/trace")
  public Map<String, Object> trace(@PathVariable String id) {
    return data(query.trace(id));
  }
}
