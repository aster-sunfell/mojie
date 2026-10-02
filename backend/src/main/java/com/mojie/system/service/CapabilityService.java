package com.mojie.system.service;

import com.mojie.agent.service.port.RuntimeStatus;
import com.mojie.system.model.Capabilities;

public final class CapabilityService {
  private final RuntimeStatus runtime;
  private final boolean configured;
  private final String modelId;

  public CapabilityService(RuntimeStatus runtime, boolean configured, String modelId) {
    this.runtime = runtime;
    this.configured = configured;
    this.modelId = modelId;
  }

  public Capabilities capabilities() {
    return new Capabilities(configured && runtime.healthy(), modelId, 2000, 6000, 2000, 8, 12);
  }
}
