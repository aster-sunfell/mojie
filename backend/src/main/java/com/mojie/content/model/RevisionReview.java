package com.mojie.content.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.mojie.common.model.QueryResult;
import java.util.*;

public record RevisionReview(String proposalId, JsonNode decisions) implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("proposalId", proposalId);
    fields.put("decisions", decisions);
    return fields;
  }
}
