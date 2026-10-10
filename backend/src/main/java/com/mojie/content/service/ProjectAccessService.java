package com.mojie.content.service;

import static com.mojie.common.model.QueryResult.fields;
import static com.mojie.content.model.ContentRules.error;

import com.mojie.content.repository.ProjectRepository;
import java.util.Map;

public final class ProjectAccessService {
  private final ProjectRepository projects;

  public ProjectAccessService(ProjectRepository projects) {
    this.projects = projects;
  }

  public void lock(String projectId, boolean requireIdle) {
    Map<String, Object> project = required(fields(projects.lockVisible(projectId)));
    if (requireIdle && project.get("activeRunId") != null)
      throw error("RESOURCE_BUSY", "Project has an active run");
  }

  public void requireNoProjectProposals(String id) {
    if (projects.hasProjectProposals(id))
      throw error("RESOURCE_BUSY", "Resource has a pending proposal");
  }

  public void requireNoChapterProposals(String id) {
    if (projects.hasChapterProposals(id))
      throw error("RESOURCE_BUSY", "Resource has a pending proposal");
  }

  public static <T> T required(T value) {
    if (value == null) throw error("RESOURCE_NOT_FOUND", "Resource unavailable");
    return value;
  }

  public static void requireOne(int count) {
    if (count != 1) throw error("RESOURCE_NOT_FOUND", "Resource unavailable");
  }
}
