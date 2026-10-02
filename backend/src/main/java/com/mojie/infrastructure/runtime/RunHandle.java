package com.mojie.infrastructure.runtime;

import com.mojie.agent.service.port.RunExecutor.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

final class RunHandle implements ExecutionControl {
  enum Phase {
    REGISTERED,
    STARTED,
    CANCELLED_BEFORE_START,
    EXITED
  }

  final String projectId, runId;
  final AtomicReference<Phase> phase = new AtomicReference<>(Phase.REGISTERED);
  final FutureTask<Void> task;
  private final AtomicBoolean cancel = new AtomicBoolean(), released = new AtomicBoolean();
  private final AtomicReference<Runnable> cancellation = new AtomicReference<>();
  private final Runnable release;

  RunHandle(Execution execution, Runnable release, Consumer<RunHandle> exited) {
    projectId = execution.projectId();
    runId = execution.runId();
    this.release = release;
    task =
        new FutureTask<>(
            () -> {
              if (!phase.compareAndSet(Phase.REGISTERED, Phase.STARTED)) return null;
              try {
                execution.work().accept(this);
              } finally {
                phase.set(Phase.EXITED);
                exited.accept(this);
                releaseOnce();
              }
              return null;
            });
  }

  public boolean cancelled() {
    return cancel.get();
  }

  public void onCancel(Runnable callback) {
    cancellation.set(callback);
    if (cancel.get()) callback.run();
  }

  void signalCancel() {
    cancel.set(true);
    Runnable callback = cancellation.get();
    if (callback != null) callback.run();
  }

  void releaseOnce() {
    if (released.compareAndSet(false, true)) release.run();
  }
}
