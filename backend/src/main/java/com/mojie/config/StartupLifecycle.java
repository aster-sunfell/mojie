package com.mojie.config;

import com.mojie.agent.service.RunLifecycleService;
import com.mojie.infrastructure.runtime.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;

public final class StartupLifecycle {
  private final Flyway flyway;
  private final RunLifecycleService lifecycle;
  private final TimeoutSweeper sweeper;
  private final BoundedRunExecutor executor;
  private final UUID processId;
  private boolean started;

  public StartupLifecycle(
      Flyway flyway,
      RunLifecycleService lifecycle,
      TimeoutSweeper sweeper,
      BoundedRunExecutor executor,
      UUID processId) {
    this.flyway = flyway;
    this.lifecycle = lifecycle;
    this.sweeper = sweeper;
    this.executor = executor;
    this.processId = processId;
  }

  public synchronized void start() {
    if (started) return;
    flyway.migrate();
    lifecycle.failPreviousProcessRuns(processId);
    sweeper.start();
    executor.markReady();
    started = true;
  }
}
