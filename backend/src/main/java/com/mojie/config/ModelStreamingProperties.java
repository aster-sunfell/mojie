package com.mojie.config;

import com.mojie.infrastructure.model.StreamTimeouts;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mojie.model.streaming")
public record ModelStreamingProperties(Duration firstEventTimeout, Duration idleTimeout) {
  public ModelStreamingProperties {
    if (firstEventTimeout == null) firstEventTimeout = Duration.ofSeconds(180);
    if (idleTimeout == null) idleTimeout = Duration.ofSeconds(60);
    new StreamTimeouts(firstEventTimeout, idleTimeout);
  }

  public StreamTimeouts timeouts() {
    return new StreamTimeouts(firstEventTimeout, idleTimeout);
  }
}
