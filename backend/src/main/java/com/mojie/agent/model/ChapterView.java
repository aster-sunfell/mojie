package com.mojie.agent.model;

import java.util.Map;

public record ChapterView(String viewId, Map<String, Long> versions) {
  public ChapterView {
    versions = Map.copyOf(versions);
  }
}
