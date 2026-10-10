package com.mojie.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mojie.model")
public record ModelProperties(
    String apiPath,
    String baseUrl,
    String apiKey,
    String id,
    String anthropicApiKey,
    String anthropicBaseUrl,
    Integer contextTokens,
    Metadata metadata) {
  public ModelProperties {
    if (contextTokens != null && contextTokens <= 0)
      throw new IllegalArgumentException("mojie.model.context-tokens must be a positive integer");
  }

  public com.mojie.agent.model.ModelApiConfig apiConfig() {
    return com.mojie.agent.model.ModelApiConfig.from(
        java.util.Map.of(
            "MOJIE_MODEL_API_PATH",
            text(apiPath),
            "MOJIE_MODEL_BASE_URL",
            text(baseUrl),
            "MOJIE_MODEL_API_KEY",
            text(apiKey),
            "MOJIE_MODEL_ID",
            text(id),
            "ANTHROPIC_API_KEY",
            text(anthropicApiKey),
            "MOJIE_ANTHROPIC_BASE_URL",
            text(anthropicBaseUrl)));
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }

  public record Metadata(
      URI catalogUrl, Duration requestTimeout, Duration refreshInterval, Duration maxCacheAge) {
    public Metadata {
      if (catalogUrl == null
          || !java.util.Set.of("https", "http").contains(catalogUrl.getScheme())
          || catalogUrl.getHost() == null
          || catalogUrl.getUserInfo() != null
          || catalogUrl.getQuery() != null
          || catalogUrl.getFragment() != null)
        throw new IllegalArgumentException("Invalid model metadata catalog URL");
      for (Duration duration : new Duration[] {requestTimeout, refreshInterval, maxCacheAge})
        if (duration == null || duration.isNegative() || duration.isZero())
          throw new IllegalArgumentException("Model metadata durations must be positive");
      if (maxCacheAge.compareTo(refreshInterval) < 0)
        throw new IllegalArgumentException(
            "Model metadata max-cache-age must cover refresh-interval");
    }
  }

  @Override
  public String toString() {
    return "ModelProperties[id=" + id + ", apiKey=<redacted>, anthropicApiKey=<redacted>]";
  }
}
