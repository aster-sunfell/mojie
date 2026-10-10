package com.mojie.content.api;

import com.mojie.content.service.*;
import com.mojie.proposal.model.ProposalReview.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public final class ProjectController {
  private final ProjectService projects;

  public ProjectController(ProjectService projects) {
    this.projects = projects;
  }

  private static Map<String, Object> data(Object value) {
    return Map.of("data", value);
  }

  public record Title(String title) {}

  @GetMapping("/projects")
  public Map<String, Object> projects(
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String keyword) {
    return data(projects.projectPage(keyword, cursor, limit));
  }

  @PostMapping("/projects")
  public ResponseEntity<Map<String, Object>> createProject(@RequestBody Title body) {
    return ResponseEntity.status(201).body(data(projects.createProject(body.title())));
  }

  @GetMapping("/projects/{id}")
  public Map<String, Object> project(@PathVariable String id) {
    return data(projects.project(id));
  }

  @PatchMapping("/projects/{id}")
  public Map<String, Object> renameProject(@PathVariable String id, @RequestBody Title body) {
    return data(projects.renameProject(id, body.title()));
  }

  @DeleteMapping("/projects/{id}")
  public ResponseEntity<Void> deleteProject(@PathVariable String id) {
    projects.deleteProject(id);
    return ResponseEntity.noContent().build();
  }
}
