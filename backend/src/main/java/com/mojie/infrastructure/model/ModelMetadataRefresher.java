package com.mojie.infrastructure.model;

import com.mojie.agent.service.ModelMetadataResolver;
import java.time.Duration;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Starts only after schema migration; failures never change content-service readiness. */
public final class ModelMetadataRefresher implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(ModelMetadataRefresher.class);
  private final ModelMetadataResolver resolver;
  private final ScheduledExecutorService timer;
  private final long retryMillis;

  public ModelMetadataRefresher(ModelMetadataResolver resolver, Duration refreshInterval) {
    this.resolver = resolver;
    retryMillis = Math.max(1, Math.min(60_000, refreshInterval.toMillis()));
    timer =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread thread = new Thread(r, "mojie-model-metadata");
              thread.setDaemon(true);
              return thread;
            });
  }

  public void start() {
    resolver.loadCache();
    timer.scheduleWithFixedDelay(this::refresh, 0, retryMillis, TimeUnit.MILLISECONDS);
  }

  private void refresh() {
    try {
      resolver.refreshIfNeeded();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      // Provider response bodies and credentials must never enter logs.
      LOG.warn(
          "Model metadata refresh failed ({}); retaining eligible cached metadata",
          e.getClass().getSimpleName());
    }
  }

  @Override
  public void close() {
    timer.shutdownNow();
  }
}
