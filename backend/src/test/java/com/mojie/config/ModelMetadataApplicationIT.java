package com.mojie.config;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.MojieApplication;
import com.mojie.agent.repository.jdbc.JdbcRunReadRepository;
import com.mojie.agent.service.ModelMetadataResolver;
import com.mojie.support.TestDatabase;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

class ModelMetadataApplicationIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void applicationRecoversMetadataSnapshotsRunsAndReusesCacheAfterRestart() throws Exception {
    try (var database = new TestDatabase();
        var provider = new Provider()) {
      var source = (PGSimpleDataSource) database.source();
      String runId;
      try (var context = start(source, provider, null)) {
        String base = origin(context);
        var resolver = context.getBean(ModelMetadataResolver.class);
        assertEquals(
            "UP", request(base, "GET", "/health", null).path("data").path("status").asText());
        assertFalse(
            request(base, "GET", "/system/capabilities", null)
                .path("data")
                .path("modelMetadataAvailable")
                .asBoolean());
        String project =
            request(base, "POST", "/projects", Map.of("title", "metadata recovery"))
                .path("data")
                .path("id")
                .asText();
        String chapter =
            request(base, "POST", "/projects/" + project + "/chapters", Map.of("title", "target"))
                .path("data")
                .path("id")
                .asText();
        Map<String, Object> submission =
            Map.of(
                "projectId",
                project,
                "targetChapterId",
                chapter,
                "expectedVersion",
                1,
                "instruction",
                "check",
                "clientRequestId",
                UUID.randomUUID().toString());
        assertEquals(
            "MODEL_METADATA_UNAVAILABLE",
            request(base, "POST", "/agent/runs", submission).path("code").asText());
        provider.window.set(164000);
        await(resolver::available);
        assertTrue(
            request(base, "GET", "/system/capabilities", null)
                .path("data")
                .path("modelMetadataAvailable")
                .asBoolean());
        runId =
            request(base, "POST", "/agent/runs", submission).path("data").path("runId").asText();
        assertFalse(runId.isBlank());
        String accepted = runId;
        await(
            () -> {
              try {
                return "SUCCEEDED"
                    .equals(
                        request(base, "GET", "/agent/runs/" + accepted, null)
                            .path("data")
                            .path("status")
                            .asText());
              } catch (Exception e) {
                return false;
              }
            });
        var snapshot = new JdbcRunReadRepository(source).modelSelection(runId).configSnapshot();
        assertEquals(164000, snapshot.path("contextWindowTokens").asInt());
        assertEquals(
            "provider:" + provider.origin() + "/v1/models",
            snapshot.path("contextWindowSource").asText());
        assertTrue(snapshot.hasNonNull("contextWindowFetchedAt"));
        assertFalse(snapshot.toString().contains("test-credential"));
        provider.window.set(200000);
        await(() -> resolver.requireMetadata().contextWindowTokens() == 200000);
        assertEquals(
            164000,
            new JdbcRunReadRepository(source)
                .modelSelection(runId)
                .configSnapshot()
                .path("contextWindowTokens")
                .asInt());
        provider.window.set(0); // failed refresh retains the original retrieval time
        assertEquals(
            runId,
            request(base, "POST", "/agent/runs", submission).path("data").path("runId").asText());
        assertEquals(0, provider.catalogCalls.get());
      }
      int callsBeforeRestart = provider.metadataCalls.get();
      try (var context = start(source, provider, null)) {
        assertTrue(context.getBean(ModelMetadataResolver.class).available());
        assertEquals(
            200000,
            context.getBean(ModelMetadataResolver.class).requireMetadata().contextWindowTokens());
        assertEquals(
            "SUCCEEDED",
            request(origin(context), "GET", "/agent/runs/" + runId, null)
                .path("data")
                .path("status")
                .asText());
        assertTrue(provider.metadataCalls.get() >= callsBeforeRestart);
      }
    } finally {
      http.close();
    }
  }

  @Test
  void explicitOverrideStartsWithoutAnyMetadataRequest() throws Exception {
    try (var database = new TestDatabase();
        var provider = new Provider();
        var context = start((PGSimpleDataSource) database.source(), provider, 32768)) {
      var resolver = context.getBean(ModelMetadataResolver.class);
      assertTrue(resolver.available());
      assertEquals(32768, resolver.requireMetadata().contextWindowTokens());
      assertEquals("configuration", resolver.requireMetadata().source());
      assertEquals(0, provider.metadataCalls.get());
    } finally {
      http.close();
    }
  }

  private ServletWebServerApplicationContext start(
      PGSimpleDataSource source, Provider provider, Integer override) {
    String jdbc =
        source.getURL()
            + (source.getURL().contains("?") ? "&" : "?")
            + "currentSchema="
            + source.getCurrentSchema();
    return (ServletWebServerApplicationContext)
        new SpringApplication(MojieApplication.class)
            .run(
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--spring.datasource.url=" + jdbc,
                "--spring.datasource.username=" + source.getUser(),
                "--spring.datasource.password=" + source.getPassword(),
                "--mojie.model.api-path=/v1/messages",
                "--mojie.model.api-key=test-credential",
                "--mojie.model.anthropic-api-key=",
                "--mojie.model.base-url=" + provider.origin(),
                "--mojie.model.id=model",
                "--mojie.model.context-tokens=" + (override == null ? "" : override),
                "--mojie.model.metadata.refresh-interval=100ms",
                "--mojie.model.metadata.max-cache-age=7d",
                "--mojie.model.metadata.request-timeout=1s",
                "--mojie.model.metadata.catalog-url=" + provider.origin() + "/catalog",
                "--logging.level.root=ERROR",
                "--spring.main.banner-mode=off");
  }

  private String origin(ServletWebServerApplicationContext context) {
    return "http://127.0.0.1:" + context.getWebServer().getPort() + "/api/v1";
  }

  private JsonNode request(String base, String method, String path, Object body) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(3));
    if (body != null) builder.header("Content-Type", "application/json");
    var response =
        http.send(
            builder
                .method(
                    method,
                    body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    JsonNode result = JSON.readTree(response.body());
    if (result.path("code").asText().equals("MODEL_METADATA_UNAVAILABLE"))
      assertEquals(503, response.statusCode());
    else assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, result.toString());
    return result;
  }

  private void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) fail("Timed out waiting for background recovery");
      Thread.sleep(20);
    }
  }

  private static final class Provider implements AutoCloseable {
    final HttpServer server;
    final AtomicInteger window = new AtomicInteger(),
        metadataCalls = new AtomicInteger(),
        catalogCalls = new AtomicInteger();

    Provider() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            try {
              int status = 200;
              String payload;
              if (exchange.getRequestURI().getPath().equals("/v1/models")) {
                metadataCalls.incrementAndGet();
                int selected = window.get();
                if (selected == 0) status = 503;
                payload = "{\"data\":[{\"id\":\"model\",\"context_length\":" + selected + "}]}";
              } else if (exchange.getRequestURI().getPath().equals("/catalog")) {
                catalogCalls.incrementAndGet();
                payload = "{}";
              } else {
                var body = JSON.readTree(exchange.getRequestBody());
                boolean hasToolResult = body.path("messages").toString().contains("tool_result");
                String target =
                    body.path("messages")
                        .get(0)
                        .path("content")
                        .get(0)
                        .path("text")
                        .asText()
                        .split("目标章节 ID: ")[1]
                        .substring(0, 36);
                payload =
                    JSON.writeValueAsString(
                        Map.of(
                            "id",
                            "metadata-message",
                            "type",
                            "message",
                            "role",
                            "assistant",
                            "model",
                            "model",
                            "content",
                            hasToolResult
                                ? List.of(Map.of("type", "text", "text", "checked"))
                                : List.of(
                                    Map.of(
                                        "type",
                                        "tool_use",
                                        "id",
                                        "read",
                                        "name",
                                        "read_chapter",
                                        "input",
                                        Map.of("chapterId", target))),
                            "stop_reason",
                            hasToolResult ? "end_turn" : "tool_use",
                            "usage",
                            Map.of("input_tokens", 20, "output_tokens", 20)));
              }
              byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(status, bytes.length);
              exchange.getResponseBody().write(bytes);
            } finally {
              exchange.close();
            }
          });
      server.start();
    }

    String origin() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void close() {
      server.stop(0);
    }
  }
}
