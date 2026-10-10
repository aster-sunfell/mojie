package com.mojie.agent.repository;

import com.mojie.agent.model.ModelMetadata;

public interface ModelMetadataRepository {
  ModelMetadata find(String provider, String baseUrl, String modelId);

  void save(String provider, String baseUrl, String modelId, ModelMetadata metadata);
}
