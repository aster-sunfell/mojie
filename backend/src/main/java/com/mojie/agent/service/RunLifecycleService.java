package com.mojie.agent.service;

import com.mojie.agent.model.*;
import com.mojie.agent.repository.*;
import com.mojie.agent.service.loop.LoopOutcome;
import com.mojie.common.transaction.TransactionRunner;
import java.util.*;

public final class RunLifecycleService {
  private final RunRepository runs;
  private final RunEventRepository events;
  private final RunCompletionService completion;
  private final TransactionRunner transactions;

  public RunLifecycleService(
      RunRepository runs,
      RunEventRepository events,
      RunCompletionService completion,
      TransactionRunner transactions) {
    this.runs = runs;
    this.events = events;
    this.completion = completion;
    this.transactions = transactions;
  }

  public boolean start(String projectId, String runId, UUID processId) {
    return transactions.required(
        () -> {
          lockProject(projectId);
          if (runs.claimQueued(projectId, runId, processId) != 1) return false;
          append(runId, "run.started", "{\"status\":\"RUNNING\"}");
          return true;
        });
  }

  public CancelResult requestCancel(String projectId, String runId) {
    return transactions.required(
        () -> {
          lockProject(projectId);
          RunRecord row = Objects.requireNonNull(runs.lockRun(projectId, runId), "Run unavailable");
          if ("QUEUED".equals(row.status())) {
            requireOne(runs.cancelQueued(runId));
            append(runId, "run.cancelled", "{\"status\":\"CANCELLED\"}");
            runs.releaseSlot(projectId, runId);
            return new CancelResult("CANCELLED", true);
          }
          if ("RUNNING".equals(row.status())) {
            if (!row.cancelRequested()) requireOne(runs.requestCancel(runId));
            return new CancelResult("RUNNING", true);
          }
          return new CancelResult(row.status(), row.cancelRequested());
        });
  }

  public List<String> sweepTimeouts() {
    List<String> ended = new ArrayList<>();
    for (ActiveRun run : runs.overdue()) {
      TerminalResult result =
          completion.finish(
              run.projectId(),
              run.runId(),
              new LoopOutcome.Failed("RUN_TIMEOUT", "Run deadline reached"));
      if ("FAILED".equals(result.status()) || "CANCELLED".equals(result.status()))
        ended.add(run.runId());
    }
    return ended;
  }

  public List<String> failPreviousProcessRuns(UUID processId) {
    List<String> ended = new ArrayList<>();
    for (ActiveRun run : runs.previousProcess(processId)) {
      completion.finish(
          run.projectId(),
          run.runId(),
          new LoopOutcome.Failed("SERVICE_RESTARTED", "Previous process stopped"));
      ended.add(run.runId());
    }
    return ended;
  }

  private void lockProject(String id) {
    if (!runs.lockProject(id)) throw new IllegalStateException("Project unavailable");
  }

  private void append(String runId, String type, String payload) {
    requireOne(events.insert(runId, events.nextSequence(runId), type, payload));
  }

  private static void requireOne(int count) {
    if (count != 1) throw new IllegalStateException("Run record changed concurrently");
  }
}
