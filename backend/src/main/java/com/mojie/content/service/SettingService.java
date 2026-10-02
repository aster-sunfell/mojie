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
      String owner, String id, String filterType, String keyword, Integer requestedLimit) {
    return settingPage(owner, id, filterType, keyword, null, requestedLimit).items();
  }

  public Page<Map<String, Object>> settingPage(
      String owner,
      String id,
      String filterType,
      String keyword,
      String cursor,
      Integer requestedLimit) {
    String projectId = uuid(id);
    int size = limit(requestedLimit);
    if (filterType != null) type(filterType);
    String scope = "settings:" + owner + ":" + projectId + ":" + filterType + ":" + keyword;
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
          required(fields(projects.findOwnedId(owner, projectId)));
          return page(
              fields(settings.findPage(projectId, filterType, keyword, marker, size + 1)),
              size,
              scope,
              item -> item.get("id").toString(),
              cursors);
        });
  }

  public Map<String, Object> createSetting(
      String owner, String id, String settingType, String title, String content) {
    String projectId = uuid(id),
        kind = type(settingType),
        name = text(title, "title", 1, 80),
        body = text(content, "content", 1, 2000);
    return transactions.required(
        () -> {
          access.lock(owner, projectId, true);
          String settingId = settings.insert(projectId, kind, name, body);
          return required(fields(settings.findById(settingId)));
        });
  }

  public Map<String, Object> updateSetting(
      String owner, String id, String settingType, String title, String content) {
    String settingId = uuid(id),
        kind = type(settingType),
        name = text(title, "title", 1, 80),
        body = text(content, "content", 1, 2000);
    return transactions.required(
        () -> {
          String projectId =
              String.valueOf(
                  required(fields(settings.findOwnedProject(owner, settingId))).get("projectId"));
          access.lock(owner, projectId, true);
          requireOne(settings.update(settingId, kind, name, body));
          return required(fields(settings.findById(settingId)));
        });
  }

  public void deleteSetting(String owner, String id) {
    String settingId = uuid(id);
    transactions.required(
        () -> {
          String projectId =
              String.valueOf(
                  required(fields(settings.findOwnedProject(owner, settingId))).get("projectId"));
          access.lock(owner, projectId, true);
          requireOne(settings.softDelete(settingId));
          return null;
        });
  }
}
