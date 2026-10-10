package com.mojie.agent.service;

import com.mojie.agent.model.CancelResult;
import com.mojie.agent.model.RunAdmission.*;
import com.mojie.agent.service.loop.LoopOutcome;
import com.mojie.agent.service.port.RunExecutor;
import com.mojie.common.error.BusinessException;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

public final class RunService {
  private final RunAdmissionService admission;
  private final RunLifecycleService lifecycle;
  private final RunCompletionService completion;
  private final RunQueryService query;
  private final RunExecutor executor;
  private final AgentRunner runner;
  private final Supplier<Config> config;
  private final boolean modelConfigured;

  public RunService(
      RunAdmissionService admission,
      RunLifecycleService lifecycle,
      RunCompletionService completion,
      RunQueryService query,
      RunExecutor executor,
      AgentRunner runner,
      Supplier<Config> config,
      boolean modelConfigured) {
    this.admission = admission;
    this.lifecycle = lifecycle;
    this.completion = completion;
    this.query = query;
    this.executor = executor;
    this.runner = runner;
    this.config = config;
    this.modelConfigured = modelConfigured;
  }

  public Receipt submit(Request request) {
    if (!modelConfigured) throw new BusinessException("MODEL_ERROR", "Model is not configured");
    if (request.expectedVersion() == null)
      throw new IllegalArgumentException("expectedVersion is required");
    ensureReady();
    Optional<Receipt> existing = admission.findExisting(request);
    if (existing.isPresent()) return existing.get();
    Config selectedConfig = config.get();
    try (var reservation = executor.reserve()) {
      Receipt receipt = admission.create(request, selectedConfig);
      if (!receipt.created()) return receipt;
      try {
        executor.schedule(
            reservation,
            new RunExecutor.Execution(
                request.projectId(),
                receipt.runId(),
                control -> runner.execute(request.projectId(), receipt.runId(), control)));
      } catch (RejectedExecutionException failure) {
        try {
          completion.finish(
              request.projectId(),
              receipt.runId(),
              new LoopOutcome.Failed("SCHEDULING_FAILED", "Run executor rejected the task"));
        } catch (RuntimeException unavailable) {
          executor.deferTerminal(
              new RunExecutor.PendingTerminal(
                  request.projectId(),
                  receipt.runId(),
                  "SCHEDULING_FAILED",
                  "Run executor rejected the task"));
          throw unavailable;
        }
        return new Receipt(receipt.runId(), "FAILED", true);
      }
      return receipt;
    }
  }

  public CancelResult cancel(String runId) {
    String projectId = query.scoped(runId).get("projectId").toString();
    return cancelProjectRun(projectId, runId);
  }

  public CancelResult cancelProjectRun(String projectId, String runId) {
    ensureReady();
    CancelResult result = lifecycle.requestCancel(projectId, runId);
    executor.signalCancel(projectId, runId, "CANCELLED".equals(result.status()));
    return result;
  }

  private void ensureReady() {
    if (!executor.healthy())
      throw new BusinessException("SERVICE_UNAVAILABLE", "Agent runner is unavailable");
  }
}
