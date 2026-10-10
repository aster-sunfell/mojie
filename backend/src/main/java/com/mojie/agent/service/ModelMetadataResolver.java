package com.mojie.agent.service;

import com.mojie.agent.model.ModelApiConfig;
import com.mojie.agent.model.ModelMetadata;
import com.mojie.agent.repository.ModelMetadataRepository;
import com.mojie.agent.service.port.ModelMetadataClient;
import com.mojie.common.error.BusinessException;
import java.time.Clock;
import java.time.Duration;

/** One selected model's metadata; admission reads memory without making network requests. */
public final class ModelMetadataResolver {
  private final ModelApiConfig config;
  private final Integer override;
  private final ModelMetadataRepository repository;
  private final ModelMetadataClient client;
  private final Clock clock;
  private final Duration refreshInterval;
  private final Duration maxCacheAge;
  private volatile ModelMetadata current;

  public ModelMetadataResolver(
      ModelApiConfig config,
      Integer override,
      ModelMetadataRepository repository,
      ModelMetadataClient client,
      Clock clock,
      Duration refreshInterval,
      Duration maxCacheAge) {
    this.config = config;
    this.override = override;
    this.repository = repository;
    this.client = client;
    this.clock = clock;
    this.refreshInterval = refreshInterval;
    this.maxCacheAge = maxCacheAge;
    if (override != null && override <= 0)
      throw new IllegalArgumentException("Invalid context window");
  }

  public void loadCache() {
    if (config.configured() && override == null)
      current = repository.find(config.provider(), config.baseUrl(), config.modelId());
  }

  public synchronized void refreshIfNeeded() throws Exception {
    if (!config.configured() || override != null) return;
    ModelMetadata previous = current;
    if (previous != null && age(previous).compareTo(refreshInterval) < 0) return;
    ModelMetadata resolved = client.fetch(config);
    repository.save(config.provider(), config.baseUrl(), config.modelId(), resolved);
    current = resolved;
  }

  public boolean available() {
    return config.configured()
        && (override != null || current != null && age(current).compareTo(maxCacheAge) <= 0);
  }

  public ModelMetadata requireMetadata() {
    if (override != null && config.configured())
      return new ModelMetadata(override, "configuration", clock.instant());
    ModelMetadata resolved = current;
    if (resolved == null || age(resolved).compareTo(maxCacheAge) > 0)
      throw new BusinessException(
          "MODEL_METADATA_UNAVAILABLE",
          "Model context information is temporarily unavailable; content editing remains"
              + " available");
    return resolved;
  }

  private Duration age(ModelMetadata metadata) {
    return Duration.between(metadata.fetchedAt(), clock.instant());
  }
}
