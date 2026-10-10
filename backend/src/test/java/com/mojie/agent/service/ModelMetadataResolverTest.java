package com.mojie.agent.service;

import static org.junit.jupiter.api.Assertions.*;

import com.mojie.agent.model.*;
import com.mojie.agent.repository.ModelMetadataRepository;
import com.mojie.agent.service.port.ModelMetadataClient;
import com.mojie.common.error.BusinessException;
import java.io.IOException;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ModelMetadataResolverTest {
  private final ModelApiConfig config =
      new ModelApiConfig(
          "/v1/messages", "https://api.siliconflow.cn", "secret", "deepseek-ai/DeepSeek-V3.2");
  private final MutableClock clock = new MutableClock();
  private final MemoryRepository repository = new MemoryRepository();

  private ModelMetadataResolver resolver(
      ModelApiConfig selected, Integer override, ModelMetadataClient client) {
    return new ModelMetadataResolver(
        selected, override, repository, client, clock, Duration.ofHours(24), Duration.ofDays(7));
  }

  @Test
  void emptyCacheBlocksAdmissionUntilBackgroundFetchPersistsTheResult() throws Exception {
    var calls = new AtomicInteger();
    var resolver =
        resolver(
            config,
            null,
            c -> {
              calls.incrementAndGet();
              return new ModelMetadata(
                  164000, "catalog:https://models.dev/api.json", clock.instant());
            });
    resolver.loadCache();
    assertFalse(resolver.available());
    assertEquals(
        "MODEL_METADATA_UNAVAILABLE",
        assertThrows(BusinessException.class, resolver::requireMetadata).code());
    resolver.refreshIfNeeded();
    assertTrue(resolver.available());
    assertEquals(164000, resolver.requireMetadata().contextWindowTokens());
    assertEquals(resolver.requireMetadata(), repository.cached);
    assertEquals(
        "siliconflow-cn|https://api.siliconflow.cn|deepseek-ai/DeepSeek-V3.2", repository.key);
    resolver.refreshIfNeeded();
    assertEquals(1, calls.get());
  }

  @Test
  void cacheSurvivesRestartAndExpiresWithoutExtendingItsAgeOnFailure() throws Exception {
    repository.cached = new ModelMetadata(163840, "provider:test", clock.instant());
    var resolver =
        resolver(
            config,
            null,
            c -> {
              throw new IOException("offline");
            });
    resolver.loadCache();
    resolver.refreshIfNeeded(); // fresh cache does not fetch
    assertTrue(resolver.available());
    var original = resolver.requireMetadata();
    clock.advance(Duration.ofDays(2));
    assertThrows(IOException.class, resolver::refreshIfNeeded);
    assertEquals(original, resolver.requireMetadata());
    clock.advance(Duration.ofDays(6));
    assertFalse(resolver.available());
    assertThrows(BusinessException.class, resolver::requireMetadata);
    assertEquals(original.fetchedAt(), repository.cached.fetchedAt());
  }

  @Test
  void refreshingMetadataDoesNotChangeAnEarlierTaskSnapshot() throws Exception {
    var calls = new AtomicInteger();
    var resolver =
        resolver(
            config,
            null,
            c ->
                new ModelMetadata(
                    calls.incrementAndGet() == 1 ? 163840 : 200000,
                    "provider:test",
                    clock.instant()));
    resolver.refreshIfNeeded();
    var snapshot = resolver.requireMetadata().snapshot();
    clock.advance(Duration.ofDays(1));
    resolver.refreshIfNeeded();
    assertEquals(163840, snapshot.get("contextWindowTokens"));
    assertEquals(200000, resolver.requireMetadata().contextWindowTokens());
    assertTrue(snapshot.containsKey("contextWindowFetchedAt"));
  }

  @Test
  void overrideAndUnconfiguredModelNeverReadTheCacheOrCallTheNetwork() throws Exception {
    ModelMetadataClient forbidden =
        c -> {
          fail("Unexpected network request");
          return null;
        };
    var resolver = resolver(config, 32768, forbidden);
    resolver.loadCache();
    resolver.refreshIfNeeded();
    assertTrue(resolver.available());
    assertEquals("configuration", resolver.requireMetadata().source());
    assertEquals(32768, resolver.requireMetadata().contextWindowTokens());
    var disabled =
        resolver(new ModelApiConfig("/v1/messages", null, null, "unconfigured"), null, forbidden);
    disabled.loadCache();
    disabled.refreshIfNeeded();
    assertFalse(disabled.available());
    assertNull(repository.key);
  }

  @Test
  void failedPersistenceDoesNotExposeUncachedMetadata() {
    var broken =
        new ModelMetadataRepository() {
          public ModelMetadata find(String p, String b, String m) {
            return null;
          }

          public void save(String p, String b, String m, ModelMetadata v) {
            throw new IllegalStateException("offline");
          }
        };
    var resolver =
        new ModelMetadataResolver(
            config,
            null,
            broken,
            c -> new ModelMetadata(100000, "provider:test", clock.instant()),
            clock,
            Duration.ofDays(1),
            Duration.ofDays(7));
    assertThrows(IllegalStateException.class, resolver::refreshIfNeeded);
    assertFalse(resolver.available());
  }

  private static final class MemoryRepository implements ModelMetadataRepository {
    ModelMetadata cached;
    String key;

    public ModelMetadata find(String p, String b, String m) {
      key = p + "|" + b + "|" + m;
      return cached;
    }

    public void save(String p, String b, String m, ModelMetadata v) {
      key = p + "|" + b + "|" + m;
      cached = v;
    }
  }

  private static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-10-10T00:00:00Z");

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }
}
