package com.mojie.agent.service;

import com.mojie.agent.model.RunEvent;
import com.mojie.agent.repository.RunEventRepository;
import java.util.*;

public final class RunEventQueryService {
  private final RunQueryService runs;
  private final RunEventRepository events;

  public RunEventQueryService(RunQueryService runs, RunEventRepository events) {
    this.runs = runs;
    this.events = events;
  }

  public void requireOwned(String owner, String runId) {
    runs.scoped(owner, runId);
  }

  public Map<String, Object> detail(String owner, String runId) {
    return runs.scoped(owner, runId);
  }

  public List<RunEvent> events(String owner, String runId, long afterSeq, int limit) {
    if (afterSeq < 0 || limit < 1 || limit > 100)
      throw new IllegalArgumentException("Invalid event cursor");
    requireOwned(owner, runId);
    return events.after(runId, afterSeq, limit);
  }
}
