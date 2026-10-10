package com.mojie.agent.service.port;

import com.mojie.agent.model.ModelApiConfig;
import com.mojie.agent.model.ModelMetadata;

public interface ModelMetadataClient {
  ModelMetadata fetch(ModelApiConfig config) throws Exception;
}
