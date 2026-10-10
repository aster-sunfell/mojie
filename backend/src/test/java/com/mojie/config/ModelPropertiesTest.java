package com.mojie.config;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.*;
import org.springframework.core.io.ClassPathResource;

class ModelPropertiesTest {
  private StandardEnvironment environment(Map<String, Object> overrides) throws Exception {
    var env = new StandardEnvironment();
    env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    for (var source :
        new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yaml")))
      env.getPropertySources().addLast(source);
    env.getPropertySources().addFirst(new MapPropertySource("test", overrides));
    return env;
  }

  @Test
  void yamlPreservesDefaultsAndBindsMetadataSettingsWithoutAWindow() throws Exception {
    var env = environment(Map.of());
    var properties = Binder.get(env).bind("mojie.model", ModelProperties.class).get();
    assertEquals("/v1/messages", properties.apiConfig().apiPath());
    assertFalse(properties.apiConfig().configured());
    assertNull(properties.contextTokens());
    assertEquals(Duration.ofSeconds(5), properties.metadata().requestTimeout());
    assertEquals(Duration.ofHours(24), properties.metadata().refreshInterval());
    assertEquals(Duration.ofDays(7), properties.metadata().maxCacheAge());
    assertEquals(
        "jdbc:postgresql://localhost:5432/mojie", env.getProperty("spring.datasource.url"));
    assertEquals("127.0.0.1", env.getProperty("server.address"));
    assertEquals("false", env.getProperty("spring.flyway.enabled"));
    assertEquals(
        "false", env.getProperty("spring.jackson.serialization.write-dates-as-timestamps"));
    assertEquals("5000", env.getProperty("spring.datasource.hikari.connection-timeout"));
    assertFalse(new ClassPathResource("application.properties").exists());
  }

  @Test
  void deploymentVariablesAndSpringPropertiesBothOverrideTheYaml() throws Exception {
    var env =
        environment(
            Map.of(
                "MOJIE_MODEL_API_PATH",
                "/v1/chat/completions",
                "MOJIE_MODEL_BASE_URL",
                "https://api.siliconflow.cn",
                "MOJIE_MODEL_API_KEY",
                "canary-secret",
                "MOJIE_MODEL_ID",
                "deepseek-ai/DeepSeek-V3.2",
                "MOJIE_MODEL_CONTEXT_TOKENS",
                "32768",
                "mojie.model.metadata.refresh-interval",
                "12h"));
    var properties = Binder.get(env).bind("mojie.model", ModelProperties.class).get();
    assertEquals("siliconflow-cn", properties.apiConfig().provider());
    assertEquals("/v1/chat/completions", properties.apiConfig().apiPath());
    assertEquals(32768, properties.contextTokens());
    assertEquals(Duration.ofHours(12), properties.metadata().refreshInterval());
    assertFalse(properties.toString().contains("canary-secret"));
    assertFalse(properties.apiConfig().toString().contains("canary-secret"));
  }

  @Test
  void invalidWindowAndCacheSettingsFailAtBinding() throws Exception {
    for (String window : new String[] {"0", "-1", "bad", "2147483648"}) {
      var env = environment(Map.of("MOJIE_MODEL_CONTEXT_TOKENS", window));
      assertThrows(
          RuntimeException.class, () -> Binder.get(env).bind("mojie.model", ModelProperties.class));
    }
    var env = environment(Map.of("mojie.model.metadata.max-cache-age", "1h"));
    assertThrows(
        RuntimeException.class, () -> Binder.get(env).bind("mojie.model", ModelProperties.class));
  }
}
