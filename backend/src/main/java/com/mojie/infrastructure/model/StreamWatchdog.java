package com.mojie.infrastructure.model;

import com.mojie.agent.service.port.ModelGateway.ModelException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Closes blocked response bodies on cancellation, idle timeout, or the absolute request deadline. */
public final class StreamWatchdog implements AutoCloseable {
  private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(2, task -> {
    Thread thread = new Thread(task, "mojie-model-stream-watchdog");
    thread.setDaemon(true);
    return thread;
  });
  static { TIMER.setRemoveOnCancelPolicy(true); }

  private final Thread owner = Thread.currentThread();
  private final long deadline;
  private final long firstDeadline;
  private final long idleNanos;
  private final AutoCloseable body;
  private final AtomicReference<String> failure = new AtomicReference<>();
  private final ScheduledFuture<?> timer;
  private volatile long lastEvent;

  public StreamWatchdog(AutoCloseable body, long started, long timeoutMillis, StreamTimeouts timeouts) {
    this.body = body;
    deadline = started + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    firstDeadline = Math.min(deadline, started + timeouts.firstEvent().toNanos());
    idleNanos = timeouts.idle().toNanos();
    timer = TIMER.scheduleAtFixedRate(this::check, 0, 25, TimeUnit.MILLISECONDS);
  }

  private void check() {
    String reason = owner.isInterrupted() ? "CANCELLED"
        : System.nanoTime() >= deadline ? "Model request deadline reached"
        : lastEvent == 0 && System.nanoTime() >= firstDeadline ? "Model first event timed out"
        : lastEvent != 0 && System.nanoTime() - lastEvent >= idleNanos ? "Model stream became idle"
        : null;
    if (reason != null && failure.compareAndSet(null, reason)) {
      try { body.close(); } catch (Exception ignored) { /* Reader reports the classified failure. */ }
    }
  }

  public void eventReceived() throws ModelException {
    ensureActive();
    lastEvent = System.nanoTime();
  }

  public void ensureActive() throws ModelException {
    check();
    String reason = failure.get();
    if (owner.isInterrupted() || "CANCELLED".equals(reason))
      throw new CancellationException("Model stream cancelled");
    if (reason != null) throw new ModelException("MODEL_TIMEOUT", reason);
  }

  @Override public void close() {
    timer.cancel(false);
  }
}
