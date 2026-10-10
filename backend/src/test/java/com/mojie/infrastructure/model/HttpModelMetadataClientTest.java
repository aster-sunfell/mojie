package com.mojie.infrastructure.model;

import static org.junit.jupiter.api.Assertions.*;

import com.mojie.agent.model.ModelApiConfig;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class HttpModelMetadataClientTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC);

  private ModelApiConfig config(String base, String id) {
    return new ModelApiConfig("/v1/chat/completions", base, "credential-canary", id);
  }

  @Test
  void supplierMetadataTakesPrecedenceAndMatchesTheExactModel() throws Exception {
    try (var server = new Server()) {
      server.body.set(
          """
          {"data":[{"id":"model-other","context_length":999999},
                   {"id":"model","context_length":163840,"max_tokens":8192}]}
          """);
      try (var client = server.client(Duration.ofSeconds(1))) {
        var result = client.fetch(config(server.origin(), "model"));
        assertEquals(163840, result.contextWindowTokens());
        assertEquals("provider:" + server.origin() + "/v1/models", result.source());
        assertEquals("Bearer credential-canary", server.authorization.get());
        assertEquals(0, server.catalogCalls.get());
      }
    }
  }

  @Test
  void chinaCatalogAndProVariantAreMatchedWithoutSendingCredentials() throws Exception {
    try (var server = new Server()) {
      server.body.set(
          """
          {"siliconflow":{"api":"https://api.siliconflow.com/v1","models":{
            "model":{"id":"model","limit":{"context":1000}}}},
           "siliconflow-cn":{"api":"https://api.siliconflow.cn/v1","models":{
            "model":{"id":"model","limit":{"context":164000}},
            "Pro/model":{"id":"Pro/model","limit":{"context":200000}}}}}
          """);
      try (var client = server.client(Duration.ofSeconds(1))) {
        assertEquals(
            164000,
            client
                .fetchCatalog(config("https://api.siliconflow.cn", "model"))
                .contextWindowTokens());
        assertEquals(
            200000,
            client
                .fetchCatalog(config("https://api.siliconflow.cn", "Pro/model"))
                .contextWindowTokens());
        assertEquals(
            1000,
            client
                .fetchCatalog(config("https://api.siliconflow.com", "model"))
                .contextWindowTokens());
        assertNull(server.authorization.get());
        assertNull(server.anthropicKey.get());
      }
    }
  }

  @Test
  void missingWrongOrInvalidCatalogEntriesAreRejected() throws Exception {
    try (var server = new Server();
        var client = server.client(Duration.ofSeconds(1))) {
      for (String limit : new String[] {"0", "-1", "1.5", "\"163840\"", "2147483648", "null"}) {
        server.body.set(
            "{\"siliconflow-cn\":{\"models\":{\"model\":{\"id\":\"model\",\"limit\":{\"context\":"
                + limit
                + "}}}}}");
        assertThrows(
            IOException.class,
            () -> client.fetchCatalog(config("https://api.siliconflow.cn", "model")));
      }
      server.body.set("{\"siliconflow-cn\":{\"api\":\"https://proxy.example/v1\",\"models\":{}}}");
      assertThrows(
          IOException.class,
          () -> client.fetchCatalog(config("https://api.siliconflow.cn", "model")));
      server.body.set(
          "{\"siliconflow-cn\":{\"models\":{\"model\":{\"id\":\"another\",\"limit\":{\"context\":1000}}}}}");
      assertThrows(
          IOException.class,
          () -> client.fetchCatalog(config("https://api.siliconflow.cn", "model")));
      server.body.set("{}");
      assertThrows(
          IOException.class,
          () -> client.fetchCatalog(config("https://api.siliconflow.cn", "model")));
      server.body.set("not-json");
      assertThrows(
          IOException.class,
          () -> client.fetchCatalog(config("https://api.siliconflow.cn", "model")));
    }
  }

  @Test
  void outputOnlyLimitsAndUnknownOriginsNeverFallBackToAnUnrelatedCatalog() throws Exception {
    try (var server = new Server();
        var client = server.client(Duration.ofSeconds(1))) {
      server.body.set(
          "{\"data\":[{\"id\":\"model\",\"max_tokens\":100000,\"max_input_tokens\":50000}]}");
      assertThrows(IOException.class, () -> client.fetch(config(server.origin(), "model")));
      assertEquals(0, server.catalogCalls.get());
    }
  }

  @Test
  void redirectsAreNotFollowedAndRequestsHaveABoundedTimeout() throws Exception {
    try (var server = new Server();
        var client = server.client(Duration.ofMillis(100))) {
      server.status.set(302);
      assertThrows(
          IOException.class,
          () -> client.fetchCatalog(config("https://api.siliconflow.cn", "model")));
      assertEquals(1, server.catalogCalls.get());
      server.status.set(200);
      server.delayMillis.set(500);
      assertThrows(
          HttpTimeoutException.class,
          () -> client.fetchCatalog(config("https://api.siliconflow.cn", "model")));
    }
  }

  private static final class Server implements AutoCloseable {
    final HttpServer http;
    final AtomicReference<String> body = new AtomicReference<>("{}");
    final AtomicReference<String> authorization = new AtomicReference<>();
    final AtomicReference<String> anthropicKey = new AtomicReference<>();
    final AtomicInteger catalogCalls = new AtomicInteger(),
        status = new AtomicInteger(200),
        delayMillis = new AtomicInteger();

    Server() throws IOException {
      http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      http.createContext(
          "/",
          exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            anthropicKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            if ("/catalog".equals(exchange.getRequestURI().getPath()))
              catalogCalls.incrementAndGet();
            try {
              Thread.sleep(delayMillis.get());
              byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.getResponseHeaders().set("Location", origin() + "/catalog");
              exchange.sendResponseHeaders(status.get(), bytes.length);
              exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              exchange.close();
            }
          });
      http.start();
    }

    String origin() {
      return "http://127.0.0.1:" + http.getAddress().getPort();
    }

    HttpModelMetadataClient client(Duration timeout) {
      return new HttpModelMetadataClient(URI.create(origin() + "/catalog"), timeout, CLOCK);
    }

    public void close() {
      http.stop(0);
    }
  }
}
