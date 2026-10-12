package com.mojie.infrastructure.model;

import java.time.Duration;

public record StreamTimeouts(Duration firstEvent, Duration idle) {
  public StreamTimeouts {
    if (firstEvent == null || idle == null || firstEvent.isZero() || firstEvent.isNegative()
        || idle.isZero() || idle.isNegative())
      throw new IllegalArgumentException("Stream timeouts must be positive");
  }

  public static StreamTimeouts defaults() {
    return new StreamTimeouts(Duration.ofSeconds(180), Duration.ofSeconds(60));
  }
}
