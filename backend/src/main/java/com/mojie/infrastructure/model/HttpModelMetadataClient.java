package com.mojie.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.agent.model.ModelApiConfig;
import com.mojie.agent.model.ModelMetadata;
import com.mojie.agent.service.port.ModelMetadataClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Clock;
import java.time.Duration;

public final class HttpModelMetadataClient implements ModelMetadataClient, AutoCloseable {
  private final HttpClient http;
  private final URI catalogUrl;
  private final Duration timeout;
  private final Clock clock;
  private final JsonMapper json = JsonMapper.builder().build();

  public HttpModelMetadataClient(URI catalogUrl, Duration timeout, Clock clock) {
    this.catalogUrl = catalogUrl;
    this.timeout = timeout;
    this.clock = clock;
    // Never follow a provider redirect carrying its credentials.
    http =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public ModelMetadata fetch(ModelApiConfig config) throws Exception {
    try {
      ModelMetadata provider = fetchProvider(config);
      if (provider != null) return provider;
    } catch (IOException | IllegalArgumentException e) {
      // Missing provider metadata does not prevent an independent catalog lookup.
    }
    if ("custom".equals(config.provider()))
      throw new IOException(
          "Custom provider has no context metadata; configure an explicit window");
    return fetchCatalog(config);
  }

  ModelMetadata fetchCatalog(ModelApiConfig config) throws Exception {
    // This public request deliberately contains no model API credentials.
    JsonNode catalog = get(HttpRequest.newBuilder(catalogUrl));
    JsonNode provider = catalog.path(config.provider());
    if (!matchesOrigin(provider.path("api").asText(null), config.baseUrl()))
      throw new IOException("Catalog provider origin does not match the configured service");
    JsonNode model = provider.path("models").path(config.modelId());
    if (!config.modelId().equals(model.path("id").asText()))
      throw new IOException("Selected model is missing from the provider catalog");
    int tokens = positiveInteger(model.path("limit").path("context"));
    if (tokens == 0) throw new IOException("Catalog does not contain a valid context window");
    return new ModelMetadata(tokens, "catalog:" + catalogUrl, clock.instant());
  }

  private ModelMetadata fetchProvider(ModelApiConfig config) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(config.baseUrl() + "/v1/models"));
    if ("anthropic".equals(config.provider()))
      request.header("x-api-key", config.apiKey()).header("anthropic-version", "2023-06-01");
    else request.header("Authorization", "Bearer " + config.apiKey());
    JsonNode data = get(request).path("data");
    if (!data.isArray()) return null;
    for (JsonNode model : data) {
      if (!config.modelId().equals(model.path("id").asText())) continue;
      // Do not mistake an output-only max_tokens or max_input_tokens for total context.
      for (JsonNode candidate :
          new JsonNode[] {
            model.path("context_window"),
            model.path("context_length"),
            model.path("limit").path("context")
          }) {
        int tokens = positiveInteger(candidate);
        if (tokens > 0)
          return new ModelMetadata(
              tokens, "provider:" + config.baseUrl() + "/v1/models", clock.instant());
      }
    }
    return null;
  }

  private JsonNode get(HttpRequest.Builder builder) throws IOException, InterruptedException {
    HttpResponse<byte[]> response =
        http.send(
            builder
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "mojie-model-metadata/1.0")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() != 200) throw new IOException("Model metadata request failed");
    return json.readTree(response.body());
  }

  private static int positiveInteger(JsonNode node) {
    return node.isIntegralNumber() && node.canConvertToInt() && node.intValue() > 0
        ? node.intValue()
        : 0;
  }

  private static boolean matchesOrigin(String catalogApi, String configuredBase) {
    if (catalogApi == null) return true; // Some catalog providers omit their API URL.
    try {
      URI catalog = URI.create(catalogApi), configured = URI.create(configuredBase);
      return java.util.Objects.equals(catalog.getScheme(), configured.getScheme())
          && java.util.Objects.equals(catalog.getHost(), configured.getHost())
          && port(catalog) == port(configured);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static int port(URI uri) {
    return uri.getPort() == -1 ? "https".equals(uri.getScheme()) ? 443 : 80 : uri.getPort();
  }

  @Override
  public void close() {
    http.shutdownNow();
  }
}
