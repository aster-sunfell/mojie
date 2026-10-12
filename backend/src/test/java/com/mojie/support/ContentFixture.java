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

  public List<Map<String, Object>> projects(Integer requestedLimit) {
    return services.projects().projects(requestedLimit);
  }

  public Page<Map<String, Object>> projectPage(
      String cursor, Integer requestedLimit) {
    return services.projects().projectPage(cursor, requestedLimit);
  }

  public Page<Map<String, Object>> projectPage(
      String keyword, String cursor, Integer requestedLimit) {
    return services.projects().projectPage(keyword, cursor, requestedLimit);
  }

  public Map<String, Object> project(String id) {
    return services.projects().project(id);
  }

  public Map<String, Object> createProject(String title) {
    return services.projects().createProject(title);
  }

  public Map<String, Object> renameProject(String id, String title) {
    return services.projects().renameProject(id, title);
  }

  public void deleteProject(String id) {
    services.projects().deleteProject(id);
  }

  public List<Map<String, Object>> chapters(String id, Integer requestedLimit) {
    return services.chapters().chapters(id, requestedLimit);
  }

  public Page<Map<String, Object>> chapterPage(
      String id, String cursor, Integer requestedLimit) {
    return services.chapters().chapterPage(id, cursor, requestedLimit);
  }

  public Map<String, Object> chapter(String id) {
    return services.chapters().chapter(id);
  }

  public Map<String, Object> createChapter(String id, String title) {
    return services.chapters().createChapter(id, title);
  }

  public Map<String, Object> renameChapter(String id, String title) {
    return services.chapters().renameChapter(id, title);
  }

  public Map<String, Object> saveChapter(
      String id, String content, int expectedVersion) {
    return services.saves().saveChapter(id, content, expectedVersion);
  }

  public Map<String, Object> saveChapter(
      String id, String content, int expectedVersion, List<Review> proposalReviews) {
    return services.saves().saveChapter(id, content, expectedVersion, proposalReviews);
  }

  public void deleteChapter(String id) {
    services.chapters().deleteChapter(id);
  }

  public List<Map<String, Object>> revisions(String id, Integer requestedLimit) {
    return services.chapters().revisions(id, requestedLimit);
  }

  public Page<Map<String, Object>> revisionPage(
      String id, String cursor, Integer requestedLimit) {
    return services.chapters().revisionPage(id, cursor, requestedLimit);
  }

  public Map<String, Object> revision(String id, int version) {
    return services.chapters().revision(id, version);
  }

  public List<Map<String, Object>> settings(
      String id, String filterType, String keyword, Integer requestedLimit) {
    return services.settings().settings(id, filterType, keyword, requestedLimit);
  }

  public Page<Map<String, Object>> settingPage(
      String id,
      String filterType,
      String keyword,
      String cursor,
      Integer requestedLimit) {
    return services.settings().settingPage(id, filterType, keyword, cursor, requestedLimit);
  }

  public Map<String, Object> createSetting(
      String id, String settingType, String title, String content) {
    return services.settings().createSetting(id, settingType, title, content);
  }

  public Map<String, Object> updateSetting(
      String id, String settingType, String title, String content) {
    return services.settings().updateSetting(id, settingType, title, content, ((Number) services.settings().setting(id).get("version")).intValue());
  }

  public void deleteSetting(String id) {
    services.settings().deleteSetting(id);
  }
}
