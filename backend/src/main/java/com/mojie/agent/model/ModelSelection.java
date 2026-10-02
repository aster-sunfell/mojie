package com.mojie.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.mojie.common.model.QueryResult;
import java.util.*;

public record ModelSelection(String modelId, JsonNode configSnapshot) implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("modelId", modelId);
    fields.put("configSnapshot", configSnapshot);
    return fields;
  }
}
