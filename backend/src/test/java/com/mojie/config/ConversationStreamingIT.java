package com.mojie.config;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.MojieApplication;
import com.mojie.support.TestDatabase;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

class ConversationStreamingIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newHttpClient();

  @Test void publicDraftsAreDurableReplayableAndNeverPublishedOnCancellationOrTruncation() throws Exception {
    try (var database = new TestDatabase(); var provider = new Provider()) {
      var source = (PGSimpleDataSource) database.source();
      String jdbc = source.getURL() + (source.getURL().contains("?") ? "&" : "?") + "currentSchema=" + source.getCurrentSchema();
      try (var context = (ServletWebServerApplicationContext) new SpringApplication(MojieApplication.class).run(
          "--server.port=0", "--server.address=127.0.0.1",
          "--spring.datasource.url=" + jdbc, "--spring.datasource.username=" + source.getUser(),
          "--spring.datasource.password=" + source.getPassword(), "--spring.main.banner-mode=off", "--logging.level.root=ERROR",
          "--mojie.model.api-key=test-key", "--mojie.model.api-path=/v1/chat/completions",
          "--mojie.model.base-url=http://127.0.0.1:" + provider.server.getAddress().getPort(),
          "--mojie.model.id=test", "--mojie.model.context-tokens=1000000")) {
        String base = "http://127.0.0.1:" + context.getWebServer().getPort() + "/api/v1";
        String project = request(base, "POST", "/projects", Map.of("title", "流式验证")).path("id").asText();
        String conversation = request(base, "POST", "/projects/" + project + "/conversations", null).path("id").asText();
        for (int attempt = 1; attempt <= 3; attempt++) {
          provider.release = new CountDownLatch(1);
          long boundary = request(base, "GET", "/conversations/" + conversation, null).path("lastSeq").asLong();
          String run = request(base, "POST", "/conversations/" + conversation + "/messages", Map.of(
              "content", "讨论构思", "clientRequestId", UUID.randomUUID().toString(), "expectedConversationSeq", boundary))
              .path("runId").asText();
          try {
            JsonNode draft = awaitDraft(base, run);
            assertEquals("RUNNING", draft.path("status").asText());
            assertEquals("第一段😀", draft.path("output").path("text").asText());
            assertEquals("STARTED", draft.path("output").path("status").asText());
            assertTrue(draft.path("output").path("lastEventSeq").asLong() <= draft.path("lastEventSeq").asLong());
            assertEquals("第一段😀", request(base, "GET", "/agent/runs/" + run, null).path("output").path("text").asText());
            try (InputStream replay = http.send(HttpRequest.newBuilder(URI.create(base + "/agent/runs/" + run + "/events?afterSeq=0"))
                .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofInputStream()).body();
                 var worker = Executors.newSingleThreadExecutor()) {
              Future<JsonNode> event = worker.submit(() -> {
                var reader = new BufferedReader(new InputStreamReader(replay, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) if (line.startsWith("data:")) {
                  JsonNode value = JSON.readTree(line.substring(5));
                  if (value.path("type").asText().equals("model.text.delta")) return value;
                }
                throw new AssertionError("No model text event received");
              });
              assertEquals("第一段😀", event.get(3, TimeUnit.SECONDS).path("payload").path("text").asText());
            }
            if (attempt == 2) request(base, "POST", "/agent/runs/" + run + "/cancel", null);
            else provider.release.countDown();
            JsonNode terminal = awaitTerminal(base, run);
            assertEquals(attempt == 1 ? "SUCCEEDED" : attempt == 2 ? "CANCELLED" : "FAILED", terminal.path("status").asText());
            assertEquals(attempt == 1 ? "第一段😀第二段" : "第一段😀", terminal.path("output").path("text").asText());
            JsonNode messages = request(base, "GET", "/conversations/" + conversation + "/messages", null).path("items");
            int replies = 0;
            for (JsonNode message : messages) if (run.equals(message.path("runId").asText()) && "ASSISTANT".equals(message.path("role").asText())) {
              replies++;
              assertEquals("第一段😀第二段", message.path("content").asText());
            }
            assertEquals(attempt == 1 ? 1 : 0, replies);
            if (attempt == 3) assertEquals("INVALID_MODEL_RESPONSE", terminal.path("errorCode").asText());
          } finally { provider.release.countDown(); }
        }
        assertEquals(3, provider.calls.get(), "Each task must use one provider attempt");
      }
    }
  }

  private JsonNode awaitDraft(String base, String run) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
    do {
      JsonNode snapshot = request(base, "GET", "/agent/runs/" + run, null);
      if (snapshot.path("output").path("text").asText().equals("第一段😀")) return snapshot;
      assertTrue(Set.of("QUEUED", "RUNNING").contains(snapshot.path("status").asText()), snapshot.toString());
      Thread.sleep(20);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("No public draft received while the provider was paused");
  }
  private JsonNode awaitTerminal(String base, String run) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
    do {
      JsonNode snapshot = request(base, "GET", "/agent/runs/" + run, null);
      if (!Set.of("QUEUED", "RUNNING").contains(snapshot.path("status").asText())) return snapshot;
      Thread.sleep(20);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Streaming run did not terminate");
  }
  private JsonNode request(String base, String method, String path, Object body) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(4));
    if (body != null) builder.header("Content-Type", "application/json");
    var response = http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
    return JSON.readTree(response.body()).path("data");
  }
  private static final class Provider implements AutoCloseable {
    final HttpServer server;
    final AtomicInteger calls = new AtomicInteger();
    volatile CountDownLatch release = new CountDownLatch(1);
    Provider() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/v1/chat/completions", exchange -> {
        CountDownLatch gate = release;
        int index = calls.incrementAndGet();
        assertTrue(JSON.readTree(exchange.getRequestBody()).path("stream").asBoolean());
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (var output = exchange.getResponseBody()) {
          write(output, Map.of("choices", List.of(Map.of("index", 0, "delta", Map.of("content", "第一段😀")))));
          try { assertTrue(gate.await(10, TimeUnit.SECONDS)); }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
          if (index != 1) return; // Cancelled or truncated streams have no successful terminal marker.
          write(output, Map.of("choices", List.of(Map.of("index", 0, "delta", Map.of("content", "第二段"), "finish_reason", "stop"))));
          output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)); output.flush();
        } catch (IOException ignored) { /* The cancelled client closes the body. */ }
        finally { exchange.close(); }
      });
      server.start();
    }
    private static void write(OutputStream output, Object chunk) throws IOException {
      output.write(("data: " + JSON.writeValueAsString(chunk) + "\n\n").getBytes(StandardCharsets.UTF_8)); output.flush();
    }
    public void close() { release.countDown(); server.stop(0); }
  }
}
