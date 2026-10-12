package com.mojie.agent.model;

import com.mojie.common.model.QueryResult;
import java.util.LinkedHashMap;
import java.util.Map;

/** Recoverable public draft from the latest task model call, never provider reasoning. */
public record RunOutput(int modelCallIndex, String text, long lastEventSeq,
                        String status, String finishReason) implements QueryResult {
  public Map<String, Object> fields() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("modelCallIndex", modelCallIndex);
    result.put("text", text);
    result.put("lastEventSeq", lastEventSeq);
    result.put("status", status);
    result.put("finishReason", finishReason);
    return result;
  }
}
