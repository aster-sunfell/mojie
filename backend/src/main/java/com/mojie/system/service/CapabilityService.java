package com.mojie.system.service;

import com.mojie.agent.service.port.RuntimeStatus;
import com.mojie.system.model.Capabilities;
import java.util.function.BooleanSupplier;

public final class CapabilityService {
  private final RuntimeStatus runtime;
  private final boolean configured;
  private final String modelId;
  private final BooleanSupplier metadataAvailable;

  public CapabilityService(
      RuntimeStatus runtime,
      boolean configured,
      String modelId,
      BooleanSupplier metadataAvailable) {
    this.runtime = runtime;
    this.configured = configured;
    this.modelId = modelId;
    this.metadataAvailable = metadataAvailable;
  }

  public Capabilities capabilities() {
    return new Capabilities(
        configured && runtime.healthy(),
        modelId,
        metadataAvailable.getAsBoolean(),
        2000,
        6000,
        2000,
        8,
        12);
  }
}
