package com.mojie.content.service;

import static com.mojie.common.model.QueryResult.fields;
import static com.mojie.content.model.ContentRules.*;
import static com.mojie.content.service.ProjectAccessService.*;

import com.mojie.common.pagination.*;
import com.mojie.common.transaction.TransactionRunner;
import com.mojie.content.repository.*;
import java.time.OffsetDateTime;
import java.util.*;

public final class ProjectService {
  private final ProjectRepository projects;
  private final ProjectAccessService access;
  private final TransactionRunner transactions;
  private final PageCursor cursors;

  public ProjectService(
      ProjectRepository projects,
      ProjectAccessService access,
      TransactionRunner transactions,
      PageCursor cursors) {
    this.projects = projects;
    this.access = access;
    this.transactions = transactions;
    this.cursors = cursors;
  }

  public List<Map<String, Object>> projects(Integer requestedLimit) {
    return projectPage(null, requestedLimit).items();
  }

  public Page<Map<String, Object>> projectPage(String cursor, Integer requestedLimit) {
    return projectPage(null, cursor, requestedLimit);
  }

  public Page<Map<String, Object>> projectPage(
      String keyword, String cursor, Integer requestedLimit) {
    int size = limit(requestedLimit);
    String query = keyword == null ? "" : text(keyword.trim(), "keyword", 0, 80);
    String scope = "projects" + (query.isEmpty() ? "" : ":keyword:" + query);
    String[] marker = pair(cursors.decode(scope, cursor));
    if (marker[0] != null) {
      try {
        OffsetDateTime.parse(marker[0]);
        UUID.fromString(marker[1]);
      } catch (RuntimeException e) {
        throw new IllegalArgumentException("Invalid cursor", e);
      }
    }
    return transactions.required(
        () ->
            page(
                fields(projects.findPage(query, marker[0], marker[1], size + 1)),
                size,
                scope,
                item -> item.get("updatedAt") + "|" + item.get("id"),
                cursors));
  }

  public Map<String, Object> project(String id) {
    String projectId = uuid(id);
    return transactions.required(() -> required(fields(projects.findVisible(projectId))));
  }

  public Map<String, Object> createProject(String title) {
    String name = text(title, "title", 1, 80);
    return transactions.required(
        () -> {
          String id = projects.insert(name);
          return required(fields(projects.findById(id)));
        });
  }

  public Map<String, Object> renameProject(String id, String title) {
    String projectId = uuid(id), name = text(title, "title", 1, 80);
    return transactions.required(
        () -> {
          access.lock(projectId, false);
          requireOne(projects.rename(projectId, name));
          return required(fields(projects.findById(projectId)));
        });
  }

  public void deleteProject(String id) {
    String projectId = uuid(id);
    transactions.required(
        () -> {
          access.lock(projectId, true);
          access.requireNoProjectProposals(projectId);
          requireOne(projects.softDelete(projectId));
          return null;
        });
  }
}
