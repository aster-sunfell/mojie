package com.mojie.infrastructure.model;

import com.mojie.agent.service.port.ModelGateway.TextDelta;
import java.util.function.Consumer;
import java.util.concurrent.*;

/** First text is delivered immediately; subsequent deltas are coalesced before durable writes. */
public final class ModelTextBuffer implements AutoCloseable {
  private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(2, task -> {
    Thread thread = new Thread(task, "mojie-model-text-flush");
    thread.setDaemon(true);
    return thread;
  });
  static { TIMER.setRemoveOnCancelPolicy(true); }
  private final Consumer<TextDelta> sink;
  private final Thread owner = Thread.currentThread();
  private final StringBuilder pending = new StringBuilder();
  private long lastFlush;
  private final ScheduledFuture<?> timer;
  private RuntimeException failure;
  private boolean closed;

  public ModelTextBuffer(Consumer<TextDelta> sink) {
    this.sink = sink;
    timer = TIMER.scheduleAtFixedRate(this::scheduledFlush, 100, 100, TimeUnit.MILLISECONDS);
  }

  public synchronized void accept(TextDelta delta) {
    if (failure != null) throw failure;
    if (closed) throw new IllegalStateException("Model text buffer is closed");
    String text = delta.text();
    if (text == null || text.isEmpty()) return;
    pending.append(text);
    if (lastFlush == 0 || System.nanoTime() - lastFlush >= 100_000_000L || pending.length() >= 4096)
      flush();
  }

  private synchronized void scheduledFlush() {
    if (closed || failure != null || owner.isInterrupted()) return;
    try { flush(); }
    catch (RuntimeException e) { failure = e; }
  }

  private void flush() {
    if (pending.isEmpty()) return;
    sink.accept(new TextDelta(pending.toString()));
    pending.setLength(0);
    lastFlush = System.nanoTime();
  }

  @Override public synchronized void close() {
    closed = true;
    timer.cancel(false);
    if (failure != null) throw failure;
    if (!owner.isInterrupted()) flush();
  }
}
