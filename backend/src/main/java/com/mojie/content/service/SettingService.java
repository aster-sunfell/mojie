package com.mojie.content.service;

import static com.mojie.common.model.QueryResult.fields;
import static com.mojie.content.model.ContentRules.*;
import static com.mojie.content.service.ProjectAccessService.*;

import com.mojie.common.pagination.*;
import com.mojie.common.transaction.TransactionRunner;
import com.mojie.content.repository.*;
import java.util.*;

public final class SettingService {
  private final ProjectRepository projects;
  private final SettingRepository settings;
  private final ProjectAccessService access;
  private final TransactionRunner transactions;
  private final PageCursor cursors;

  public SettingService(
      ProjectRepository projects,
      SettingRepository settings,
      ProjectAccessService access,
      TransactionRunner transactions,
      PageCursor cursors) {
    this.projects = projects;
    this.settings = settings;
    this.access = access;
    this.transactions = transactions;
    this.cursors = cursors;
  }

  public List<Map<String, Object>> settings(
      String id, String filterType, String keyword, Integer requestedLimit) {
    return settingPage(id, filterType, keyword, null, requestedLimit).items();
  }

  public Page<Map<String, Object>> settingPage(
      String id, String filterType, String keyword, String cursor, Integer requestedLimit) {
    String projectId = uuid(id);
    int size = limit(requestedLimit);
    if (filterType != null) type(filterType);
    String scope = "settings:" + projectId + ":" + filterType + ":" + keyword;
    String marker = cursors.decode(scope, cursor);
    if (marker != null) {
      try {
        UUID.fromString(marker);
      } catch (RuntimeException e) {
        throw new IllegalArgumentException("Invalid cursor");
      }
    }
    return transactions.required(
        () -> {
          required(fields(projects.findVisibleId(projectId)));
          return page(
              fields(settings.findPage(projectId, filterType, keyword, marker, size + 1)),
              size,
              scope,
              item -> item.get("id").toString(),
              cursors);
        });
  }

  public Map<String, Object> createSetting(
      String id, String settingType, String title, String content) {
    String projectId = uuid(id),
        kind = type(settingType),
        name = text(title, "title", 1, 80),
        body = text(content, "content", 1, 2000);
    return transactions.required(
        () -> {
          access.lock(projectId, true);
          String settingId = settings.insert(projectId, kind, name, body);
          requireOne(settings.insertRevision(settingId, 1, kind, name, body, "CREATE", null));
          return required(fields(settings.findById(settingId)));
        });
  }

  public Map<String, Object> updateSetting(
      String id, String settingType, String title, String content, Integer expectedVersion) {
    String settingId = uuid(id),
        kind = type(settingType),
        name = text(title, "title", 1, 80),
        body = text(content, "content", 1, 2000);
    return transactions.required(
        () -> {
          String projectId =
              String.valueOf(
                  required(fields(settings.findVisibleProject(settingId))).get("projectId"));
          access.lock(projectId, true);
          persist(settingId, kind, name, body, expectedVersion, "MANUAL", null);
          return required(fields(settings.findById(settingId)));
        });
  }

  public Map<String, Object> setting(String id) {
    return transactions.required(
        () -> {
          String settingId = uuid(id);
          required(fields(settings.findVisibleProject(settingId)));
          return required(fields(settings.findById(settingId)));
        });
  }

  public java.util.List<com.mojie.content.model.SettingRevision> revisions(String id) {
    return transactions.required(
        () -> {
          String settingId = uuid(id);
          required(fields(settings.findVisibleProject(settingId)));
          return settings.revisions(settingId);
        });
  }

  public com.mojie.content.model.Setting lock(String id) {
    required(fields(settings.findVisibleProject(uuid(id))));
    return required(settings.lock(id));
  }

  /** Called within a project-locked transaction by manual saves or proposal acceptance. */
  public int persist(
      String id,
      String settingType,
      String title,
      String content,
      Integer expectedVersion,
      String source,
      String proposalId) {
    String kind = type(settingType),
        name = text(title, "title", 1, 80),
        body = text(content, "content", 1, 2000);
    if (expectedVersion == null || expectedVersion < 1)
      throw new IllegalArgumentException("expectedVersion required");
    var current = lock(id);
    if (current.version() != expectedVersion)
      throw error("VERSION_CONFLICT", "Setting version changed");
    requireOne(settings.update(id, kind, name, body, expectedVersion));
    int next = expectedVersion + 1;
    requireOne(settings.insertRevision(id, next, kind, name, body, source, proposalId));
    return next;
  }

  public void deleteSetting(String id) {
    String settingId = uuid(id);
    transactions.required(
        () -> {
          String projectId =
              String.valueOf(
                  required(fields(settings.findVisibleProject(settingId))).get("projectId"));
          access.lock(projectId, true);
          requireOne(settings.softDelete(settingId));
          return null;
        });
  }
}
