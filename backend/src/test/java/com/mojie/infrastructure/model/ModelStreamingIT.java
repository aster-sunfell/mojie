package com.mojie.infrastructure.model;

import static org.junit.jupiter.api.Assertions.*;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.agent.service.port.ModelGateway;
import com.mojie.agent.service.port.ModelGateway.*;
import com.mojie.infrastructure.model.anthropic.AnthropicModelGateway;
import com.mojie.infrastructure.model.openai.ChatCompletionsModelGateway;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "MOJIE_TEST_LOCAL_HTTP", matches = "1")
@Timeout(20)
class ModelStreamingIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test void chatDeliversTextBeforeCompletionAndAssemblesInterleavedToolsAndUsage() throws Exception {
    CountDownLatch prefix = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicReference<JsonNode> sent = new AtomicReference<>();
    HttpServer server = server("/v1/chat/completions", sent, output -> {
      frame(output, chat(Map.of("role", "assistant", "reasoning_content", "private reasoning"), null));
      frame(output, chat(Map.of("content", "第一😀段"), null));
      await(release);
      frame(output, chat(Map.of("tool_calls", List.of(
          Map.of("index", 1, "id", "call-2", "type", "function", "function", Map.of("name", "read_settings", "arguments", "{")),
          Map.of("index", 0, "id", "call-1", "type", "function", "function", Map.of("name", "read_", "arguments", "{\"chapter")))), null));
      frame(output, chat(Map.of("content", "第二段", "tool_calls", List.of(
          Map.of("index", 0, "function", Map.of("name", "chapter", "arguments", "Id\":\"chapter\"}")),
          Map.of("index", 1, "function", Map.of("arguments", "\"settingIds\":[\"setting\"]}")))), null));
      frame(output, chat(Map.of(), "tool_calls"));
      frame(output, Map.of("id", "chat-1", "choices", List.of(), "usage", Map.of("prompt_tokens", 12, "completion_tokens", 34)));
      rawFrame(output, "[DONE]");
    });
    try (var gateway = new ChatCompletionsModelGateway("test-key", origin(server));
         var worker = Executors.newSingleThreadExecutor()) {
      List<String> visible = Collections.synchronizedList(new ArrayList<>());
      Future<Response> response = worker.submit(() -> gateway.call(request(5_000), delta -> {
        visible.add(delta.text()); prefix.countDown();
      }));
      try {
        if (!prefix.await(3, TimeUnit.SECONDS)) response.get(1, TimeUnit.SECONDS);
        assertEquals(0, prefix.getCount());
        assertFalse(response.isDone(), "Text must arrive while the upstream response remains open");
        assertEquals(List.of("第一😀段"), visible);
        release.countDown();
        Response finalResponse = response.get(3, TimeUnit.SECONDS);
        assertTrue(sent.get().path("stream").asBoolean());
        assertTrue(sent.get().path("stream_options").path("include_usage").asBoolean());
        assertEquals("第一😀段第二段", finalResponse.text());
        assertEquals("第一😀段第二段", String.join("", visible));
        assertEquals(FinishReason.TOOL_CALLS, finalResponse.finishReason());
        assertEquals("read_chapter", finalResponse.calls().get(0).name());
        assertEquals("chapter", finalResponse.calls().get(0).arguments().path("chapterId").asText());
        assertEquals("setting", finalResponse.calls().get(1).arguments().path("settingIds").get(0).asText());
        assertEquals(34L, finalResponse.usage().get("outputTokens"));
        assertEquals("private reasoning", ((OpaquePart) finalResponse.content().getFirst()).providerBlock().path("reasoning_content").asText());
      } finally { release.countDown(); }
    } finally { server.stop(0); }
  }

  @Test void anthropicStreamsOnlyPublicTextAndPreservesThinkingSignatureAndToolJson() throws Exception {
    CountDownLatch prefix = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicReference<JsonNode> sent = new AtomicReference<>();
    HttpServer server = server("/v1/messages", sent, output -> {
      frame(output, Map.of("type", "message_start", "message", Map.of("id", "msg-1", "type", "message", "role", "assistant",
          "model", "test", "content", List.of(), "usage", Map.of("input_tokens", 12, "output_tokens", 0))));
      blockStart(output, 0, Map.of("type", "thinking", "thinking", "", "signature", ""));
      blockDelta(output, 0, Map.of("type", "thinking_delta", "thinking", "private reasoning"));
      blockDelta(output, 0, Map.of("type", "signature_delta", "signature", "signed"));
      blockStop(output, 0);
      blockStart(output, 1, Map.of("type", "text", "text", ""));
      blockDelta(output, 1, Map.of("type", "text_delta", "text", "第一😀段"));
      await(release);
      blockDelta(output, 1, Map.of("type", "text_delta", "text", "第二段"));
      blockStop(output, 1);
      blockStart(output, 2, Map.of("type", "tool_use", "id", "call-1", "name", "read_chapter", "input", Map.of()));
      blockDelta(output, 2, Map.of("type", "input_json_delta", "partial_json", "{\"chapterId\":"));
      blockDelta(output, 2, Map.of("type", "input_json_delta", "partial_json", "\"chapter\"}"));
      blockStop(output, 2);
      frame(output, Map.of("type", "message_delta", "delta", Map.of("stop_reason", "tool_use"), "usage", Map.of("output_tokens", 34)));
      frame(output, Map.of("type", "message_stop"));
    });
    var client = AnthropicOkHttpClient.builder().apiKey("test-key").baseUrl(origin(server)).maxRetries(0).build();
    try (var gateway = new AnthropicModelGateway(client);
         var worker = Executors.newSingleThreadExecutor()) {
      List<String> visible = Collections.synchronizedList(new ArrayList<>());
      Future<Response> response = worker.submit(() -> gateway.call(request(5_000), delta -> {
        visible.add(delta.text()); prefix.countDown();
      }));
      try {
        assertTrue(prefix.await(3, TimeUnit.SECONDS));
        assertFalse(response.isDone());
        assertEquals(List.of("第一😀段"), visible);
        release.countDown();
        Response finalResponse = response.get(3, TimeUnit.SECONDS);
        assertTrue(sent.get().path("stream").asBoolean());
        assertEquals("第一😀段第二段", finalResponse.text());
        assertEquals(finalResponse.text(), String.join("", visible));
        assertEquals(FinishReason.TOOL_CALLS, finalResponse.finishReason());
        assertEquals("signed", ((OpaquePart) finalResponse.content().getFirst()).providerBlock().path("signature").asText());
        assertEquals("chapter", finalResponse.calls().getFirst().arguments().path("chapterId").asText());
        assertEquals(12L, finalResponse.usage().get("inputTokens"));
        assertEquals(34L, finalResponse.usage().get("outputTokens"));
      } finally { release.countDown(); }
    } finally { client.close(); server.stop(0); }
  }

  @Test void truncatedChatStreamsAreFailuresEvenAfterAVisiblePrefixOrFinishReason() throws Exception {
    for (boolean finish : List.of(false, true)) {
      HttpServer server = server("/v1/chat/completions", new AtomicReference<>(), output ->
          frame(output, chat(Map.of("content", "未完成"), finish ? "stop" : null)));
      try (var gateway = new ChatCompletionsModelGateway("test-key", origin(server))) {
        List<String> visible = new ArrayList<>();
        ModelException error = assertThrows(ModelException.class, () -> gateway.call(request(2_000), d -> visible.add(d.text())));
        assertEquals("INVALID_MODEL_RESPONSE", error.code());
        assertEquals(List.of("未完成"), visible);
      } finally { server.stop(0); }
    }
  }

  @Test void idleAndFirstEventTimeoutsCloseAnOpenResponseBody() throws Exception {
    for (boolean first : List.of(true, false)) {
      CountDownLatch release = new CountDownLatch(1);
      HttpServer server = server("/v1/chat/completions", new AtomicReference<>(), output -> {
        if (first) output.write("event: pending\n".getBytes(StandardCharsets.UTF_8));
        else frame(output, chat(Map.of("content", "开头"), null));
        output.flush();
        await(release);
      });
      StreamTimeouts timeouts = new StreamTimeouts(Duration.ofMillis(300), Duration.ofMillis(150));
      try (var gateway = new ChatCompletionsModelGateway("test-key", origin(server), timeouts)) {
        ModelException error = assertThrows(ModelException.class, () -> gateway.call(request(3_000), d -> {}));
        assertEquals("MODEL_TIMEOUT", error.code());
        assertTrue(error.getMessage().contains(first ? "first event" : "idle"));
      } finally { release.countDown(); server.stop(0); }
    }
  }

  @Test void cancellationClosesABlockedStreamForBothProviders() throws Exception {
    for (boolean anthropic : List.of(false, true)) {
      CountDownLatch prefix = new CountDownLatch(1), release = new CountDownLatch(1);
      HttpServer server = server(anthropic ? "/v1/messages" : "/v1/chat/completions", new AtomicReference<>(), output -> {
        if (anthropic) {
          frame(output, Map.of("type", "message_start", "message", Map.of("id", "msg-1", "type", "message", "role", "assistant",
              "model", "test", "content", List.of(), "usage", Map.of("input_tokens", 1, "output_tokens", 0))));
          blockStart(output, 0, Map.of("type", "text", "text", ""));
          blockDelta(output, 0, Map.of("type", "text_delta", "text", "开头"));
        } else frame(output, chat(Map.of("content", "开头"), null));
        await(release);
      });
      var client = AnthropicOkHttpClient.builder().apiKey("test-key").baseUrl(origin(server)).maxRetries(0).build();
      var chat = new ChatCompletionsModelGateway("test-key", origin(server));
      var messages = new AnthropicModelGateway(client);
      AtomicReference<Throwable> failure = new AtomicReference<>();
      ModelGateway gateway = anthropic ? messages : chat;
      Thread worker = Thread.ofPlatform().start(() -> {
        try { gateway.call(request(5_000), d -> prefix.countDown()); }
        catch (Throwable e) { failure.set(e); }
      });
      try {
        assertTrue(prefix.await(3, TimeUnit.SECONDS));
        worker.interrupt();
        worker.join(2_000);
        assertFalse(worker.isAlive(), "Cancellation must unblock the upstream read");
        assertInstanceOf(CancellationException.class, failure.get());
      } finally {
        release.countDown(); worker.interrupt(); worker.join(2_000);
        chat.close(); client.close(); server.stop(0);
      }
    }
  }

  @Test void absoluteDeadlineStillAppliesToAStreamWithRegularHeartbeats() throws Exception {
    HttpServer server = server("/v1/chat/completions", new AtomicReference<>(), output -> {
      for (int i = 0; i < 20; i++) {
        output.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8)); output.flush();
        try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
      }
    });
    try (var gateway = new ChatCompletionsModelGateway("test-key", origin(server))) {
      assertEquals("MODEL_TIMEOUT", assertThrows(ModelException.class,
          () -> gateway.call(request(200), d -> {})).code());
    } finally { server.stop(0); }
  }

  private static Request request(long timeout) {
    return new Request("system", List.of(Message.user("讨论构思")), List.of(), "test", 100, timeout);
  }
  private static Map<String, Object> chat(Map<String, Object> delta, String reason) {
    Map<String, Object> choice = new LinkedHashMap<>();
    choice.put("index", 0); choice.put("delta", delta); choice.put("finish_reason", reason);
    return Map.of("id", "chat-1", "choices", List.of(choice));
  }
  private static void blockStart(OutputStream output, int index, Object content) throws IOException {
    frame(output, Map.of("type", "content_block_start", "index", index, "content_block", content));
  }
  private static void blockDelta(OutputStream output, int index, Object delta) throws IOException {
    frame(output, Map.of("type", "content_block_delta", "index", index, "delta", delta));
  }
  private static void blockStop(OutputStream output, int index) throws IOException {
    frame(output, Map.of("type", "content_block_stop", "index", index));
  }
  private static void frame(OutputStream output, Object data) throws IOException {
    var node = JSON.valueToTree(data);
    if (node.path("type").isTextual()) output.write(("event: " + node.path("type").asText() + "\r\n").getBytes(StandardCharsets.UTF_8));
    rawFrame(output, JSON.writeValueAsString(data));
  }
  private static void rawFrame(OutputStream output, String data) throws IOException {
    byte[] bytes = ("data: " + data + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
    // Force HTTP chunk boundaries through UTF-8 code points and through JSON tokens.
    for (int i = 0; i < bytes.length; i += 3) output.write(bytes, i, Math.min(3, bytes.length - i));
    output.flush();
  }
  private static void await(CountDownLatch release) throws IOException {
    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture timed out"); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
  }
  @FunctionalInterface private interface Writer { void write(OutputStream output) throws IOException; }
  private static HttpServer server(String path, AtomicReference<JsonNode> sent, Writer writer) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(path, exchange -> {
      sent.set(JSON.readTree(exchange.getRequestBody().readAllBytes()));
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, 0);
      try (var output = exchange.getResponseBody()) { writer.write(output); }
      catch (IOException ignored) { /* Cancellation and timeout intentionally close this socket. */ }
      finally { exchange.close(); }
    });
    server.start();
    return server;
  }
  private static String origin(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort(); }
}
