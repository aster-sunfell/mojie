package com.mojie.infrastructure.runtime;

import com.mojie.agent.service.*;
import com.mojie.agent.service.loop.LoopOutcome;
import java.util.concurrent.*;

public final class TimeoutSweeper implements AutoCloseable {
  private final RunLifecycleService lifecycle;
  private final RunCompletionService completion;
  private final BoundedRunExecutor executor;
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

  public TimeoutSweeper(
      RunLifecycleService lifecycle, RunCompletionService completion, BoundedRunExecutor executor) {
    this.lifecycle = lifecycle;
    this.completion = completion;
    this.executor = executor;
  }

  public void start() {
    timer.scheduleAtFixedRate(this::sweepSafely, 2, 2, TimeUnit.SECONDS);
  }

  private void sweepSafely() {
    try {
      for (String runId : lifecycle.sweepTimeouts()) executor.signalCancel(null, runId, true);
      RuntimeException firstFailure = null;
      for (var pending : executor.pendingTerminals()) {
        try {
          completion.finish(
              pending.projectId(),
              pending.runId(),
              new LoopOutcome.Failed(pending.code(), pending.detail()));
          executor.terminalRecovered(pending);
        } catch (RuntimeException failure) {
          if (firstFailure == null) firstFailure = failure;
        }
      }
      if (firstFailure != null) throw firstFailure;
      executor.sweepRecovered();
    } catch (RuntimeException failure) {
      executor.sweepFailed(failure);
    }
  }

  public void close() {
    timer.shutdownNow();
  }
}
