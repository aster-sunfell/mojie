package com.mojie.infrastructure.runtime;

import com.mojie.agent.service.port.RunExecutor;
import com.mojie.common.error.BusinessException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class BoundedRunExecutor implements RunExecutor, AutoCloseable {
  private final RunRegistry registry;
  private final Semaphore permits = new Semaphore(10);
  private final ThreadPoolExecutor workers =
      new ThreadPoolExecutor(
          2,
          2,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(8),
          new ThreadPoolExecutor.AbortPolicy());
  private final AtomicBoolean ready = new AtomicBoolean();
  private final AtomicReference<RuntimeException> sweepFailure = new AtomicReference<>();

  public BoundedRunExecutor(RunRegistry registry) {
    this.registry = registry;
  }

  private final class Permit implements Reservation {
    private final AtomicInteger state = new AtomicInteger(); // 0 caller, 1 handle, 2 released

    public void close() {
      if (state.compareAndSet(0, 2)) permits.release();
    }

    void transfer() {
      if (!state.compareAndSet(0, 1))
        throw new IllegalStateException("Reservation already consumed");
    }

    void releaseByHandle() {
      if (state.compareAndSet(1, 2)) permits.release();
    }
  }

  public Reservation reserve() {
    if (!healthy())
      throw new BusinessException("SERVICE_UNAVAILABLE", "Agent runner is unavailable");
    if (!permits.tryAcquire())
      throw new BusinessException("RUN_CAPACITY_EXCEEDED", "Run queue is full");
    return new Permit();
  }

  public void schedule(Reservation reservation, Execution execution) {
    if (!(reservation instanceof BoundedRunExecutor.Permit permit))
      throw new IllegalArgumentException("Unknown reservation");
    permit.transfer();
    RunHandle handle =
        new RunHandle(execution, permit::releaseByHandle, h -> registry.handles.remove(h.runId, h));
    if (registry.handles.putIfAbsent(handle.runId, handle) != null) {
      handle.releaseOnce();
      throw new IllegalStateException("Run handle already registered");
    }
    try {
      workers.execute(handle.task);
    } catch (RejectedExecutionException e) {
      registry.handles.remove(handle.runId, handle);
      handle.releaseOnce();
      throw e;
    }
  }

  public void signalCancel(String projectId, String runId, boolean terminal) {
    RunHandle handle = registry.handles.get(runId);
    if (handle == null || (projectId != null && !projectId.equals(handle.projectId))) return;
    handle.signalCancel();
    if (terminal
        && handle.phase.compareAndSet(
            RunHandle.Phase.REGISTERED, RunHandle.Phase.CANCELLED_BEFORE_START)) {
      handle.task.cancel(false);
      workers.remove(handle.task);
      registry.handles.remove(runId, handle);
      handle.releaseOnce();
    } else if (handle.phase.get() == RunHandle.Phase.STARTED) handle.task.cancel(true);
  }

  public void deferTerminal(PendingTerminal pending) {
    registry.pending.put(pending.runId(), pending);
  }

  public List<PendingTerminal> pendingTerminals() {
    return List.copyOf(registry.pending.values());
  }

  public void terminalRecovered(PendingTerminal pending) {
    registry.pending.remove(pending.runId(), pending);
  }

  public boolean healthy() {
    return ready.get() && sweepFailure.get() == null && registry.pending.isEmpty();
  }

  public int occupiedCapacity() {
    return 10 - permits.availablePermits();
  }

  public int runningThreads() {
    return workers.getActiveCount();
  }

  public void markReady() {
    ready.set(true);
  }

  public void sweepFailed(RuntimeException failure) {
    sweepFailure.set(failure);
  }

  public void sweepRecovered() {
    sweepFailure.set(null);
  }

  public void close() {
    ready.set(false);
    for (RunHandle handle : registry.handles.values())
      signalCancel(handle.projectId, handle.runId, true);
    workers.shutdownNow();
  }
}
