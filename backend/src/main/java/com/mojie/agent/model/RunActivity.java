package com.mojie.agent.model;

import com.mojie.common.model.QueryResult;
import java.util.Map;

/** Public commentary from task responses, excluding provider reasoning and final answers. */
public record RunActivity(String id, int modelCallIndex, String text) implements QueryResult {
  public Map<String, Object> fields() {
    return Map.of("id", id, "modelCallIndex", modelCallIndex, "text", text);
  }
}
