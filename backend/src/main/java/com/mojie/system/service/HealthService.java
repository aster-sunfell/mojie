package com.mojie.system.service;

import com.mojie.agent.service.port.RuntimeStatus;
import com.mojie.system.model.HealthStatus;
import com.mojie.system.repository.DatabaseProbe;

public final class HealthService {
  private final DatabaseProbe database;
  private final RuntimeStatus runtime;

  public HealthService(DatabaseProbe database, RuntimeStatus runtime) {
    this.database = database;
    this.runtime = runtime;
  }

  public HealthStatus health() {
    return new HealthStatus(!database.available() ? "DOWN" : runtime.healthy() ? "UP" : "DEGRADED");
  }
}
