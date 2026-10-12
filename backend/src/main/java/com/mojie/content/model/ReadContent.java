package com.mojie.content.model;

public final class ReadContent {
  private ReadContent() {}

  public record ChapterMetadata(String id, String title, int order, long version) {}

  public record ChapterVersion(String id, long version) {}

  public record ChapterText(String id, String title, int order, long version, String content) {}

  public record SettingText(String id, String type, String title, String content, int version) {}
}
