package com.mojie.agent.model;

import java.time.Instant;
import java.util.Map;

public record ModelMetadata(int contextWindowTokens, String source, Instant fetchedAt) {
  public ModelMetadata {
    if (contextWindowTokens <= 0 || source == null || source.isBlank() || fetchedAt == null)
      throw new IllegalArgumentException("Invalid model metadata");
  }

  public Map<String, Object> snapshot() {
    return Map.of(
        "contextWindowTokens",
        contextWindowTokens,
        "contextWindowSource",
        source,
        "contextWindowFetchedAt",
        fetchedAt.toString());
  }
}
