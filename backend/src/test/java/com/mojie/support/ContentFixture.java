package com.mojie.support;

import com.mojie.common.pagination.Page;
import com.mojie.config.ContentConfiguration;
import com.mojie.proposal.model.ProposalReview.Review;
import java.util.*;
import javax.sql.DataSource;

public final class ContentFixture {
  private final ContentConfiguration.Services services;

  public ContentFixture(DataSource source) {
    services = TestServices.content(source);
  }

  public List<Map<String, Object>> projects(String owner, Integer requestedLimit) {
    return services.projects().projects(owner, requestedLimit);
  }

  public Page<Map<String, Object>> projectPage(
      String owner, String cursor, Integer requestedLimit) {
    return services.projects().projectPage(owner, cursor, requestedLimit);
  }

  public Page<Map<String, Object>> projectPage(
      String owner, String keyword, String cursor, Integer requestedLimit) {
    return services.projects().projectPage(owner, keyword, cursor, requestedLimit);
  }

  public Map<String, Object> project(String owner, String id) {
    return services.projects().project(owner, id);
  }

  public Map<String, Object> createProject(String owner, String title) {
    return services.projects().createProject(owner, title);
  }

  public Map<String, Object> renameProject(String owner, String id, String title) {
    return services.projects().renameProject(owner, id, title);
  }

  public void deleteProject(String owner, String id) {
    services.projects().deleteProject(owner, id);
  }

  public List<Map<String, Object>> chapters(String owner, String id, Integer requestedLimit) {
    return services.chapters().chapters(owner, id, requestedLimit);
  }

  public Page<Map<String, Object>> chapterPage(
      String owner, String id, String cursor, Integer requestedLimit) {
    return services.chapters().chapterPage(owner, id, cursor, requestedLimit);
  }

  public Map<String, Object> chapter(String owner, String id) {
    return services.chapters().chapter(owner, id);
  }

  public Map<String, Object> createChapter(String owner, String id, String title) {
    return services.chapters().createChapter(owner, id, title);
  }

  public Map<String, Object> renameChapter(String owner, String id, String title) {
    return services.chapters().renameChapter(owner, id, title);
  }

  public Map<String, Object> saveChapter(
      String owner, String id, String content, int expectedVersion) {
    return services.saves().saveChapter(owner, id, content, expectedVersion);
  }

  public Map<String, Object> saveChapter(
      String owner, String id, String content, int expectedVersion, List<Review> proposalReviews) {
    return services.saves().saveChapter(owner, id, content, expectedVersion, proposalReviews);
  }

  public void deleteChapter(String owner, String id) {
    services.chapters().deleteChapter(owner, id);
  }

  public List<Map<String, Object>> revisions(String owner, String id, Integer requestedLimit) {
    return services.chapters().revisions(owner, id, requestedLimit);
  }

  public Page<Map<String, Object>> revisionPage(
      String owner, String id, String cursor, Integer requestedLimit) {
    return services.chapters().revisionPage(owner, id, cursor, requestedLimit);
  }

  public Map<String, Object> revision(String owner, String id, int version) {
    return services.chapters().revision(owner, id, version);
  }

  public List<Map<String, Object>> settings(
      String owner, String id, String filterType, String keyword, Integer requestedLimit) {
    return services.settings().settings(owner, id, filterType, keyword, requestedLimit);
  }

  public Page<Map<String, Object>> settingPage(
      String owner,
      String id,
      String filterType,
      String keyword,
      String cursor,
      Integer requestedLimit) {
    return services.settings().settingPage(owner, id, filterType, keyword, cursor, requestedLimit);
  }

  public Map<String, Object> createSetting(
      String owner, String id, String settingType, String title, String content) {
    return services.settings().createSetting(owner, id, settingType, title, content);
  }

  public Map<String, Object> updateSetting(
      String owner, String id, String settingType, String title, String content) {
    return services.settings().updateSetting(owner, id, settingType, title, content);
  }

  public void deleteSetting(String owner, String id) {
    services.settings().deleteSetting(owner, id);
  }
}
