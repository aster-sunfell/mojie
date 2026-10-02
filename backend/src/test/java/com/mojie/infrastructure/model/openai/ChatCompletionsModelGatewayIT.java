package com.mojie.infrastructure.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.agent.service.port.ModelGateway;
import com.mojie.agent.service.port.ModelGateway.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "MOJIE_TEST_LOCAL_HTTP", matches = "1")
class ChatCompletionsModelGatewayIT {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test void toolCallAndResultRoundTrip() throws Exception {
        AtomicReference<JsonNode> sent = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(200, """
                {"id":"chatcmpl-test","choices":[{"index":0,
                  "message":{"role":"assistant","content":"检查章节", "reasoning_content":"先阅读原文", "tool_calls":[
                    {"id":"call-2","type":"function","function":{"name":"read_chapter",
                      "arguments":"{\\\"chapterId\\\":\\\"chapter-2\\\"}"}}]},"finish_reason":"tool_calls"}],
                  "usage":{"prompt_tokens":12,"completion_tokens":34}}
                """, calls, sent);
        try (ChatCompletionsModelGateway gateway = new ChatCompletionsModelGateway("test-key", endpoint(server))) {
            ToolSpec spec = new ToolSpec("read_chapter", "Read chapter", Map.of("type", "object",
                    "properties", Map.of("chapterId", Map.of("type", "string")),
                    "required", List.of("chapterId"), "additionalProperties", false));
            ToolCall previous = new ToolCall("call-1", "read_chapter", JSON.readTree("{\"chapterId\":\"chapter-1\"}"));
            Response reply = gateway.call(new Request("system", List.of(
                    ModelGateway.Message.user("check"),
                    ModelGateway.Message.assistant(List.of(new TextPart("reading"), previous)),
                    ModelGateway.Message.tools(List.of(new ToolReply("call-1", "local-1",
                            Map.of("success", true, "data", Map.of("content", "chapter text")))))),
                    List.of(spec), "model", 100, 5_000));
            assertEquals(1, calls.get());
            assertEquals(FinishReason.TOOL_CALLS, reply.finishReason());
            assertEquals("检查章节", reply.text());
            assertEquals("call-2", reply.calls().getFirst().id());
            assertEquals("chapter-2", reply.calls().getFirst().arguments().path("chapterId").asText());
            assertEquals(12L, reply.usage().get("inputTokens"));
            assertEquals("chatcmpl-test", reply.providerRequestId());
            JsonNode body = sent.get();
            assertEquals("system", body.path("messages").get(0).path("role").asText());
            assertEquals("tool", body.path("messages").get(3).path("role").asText());
            assertEquals("call-1", body.path("messages").get(3).path("tool_call_id").asText());
            assertEquals("call-1", body.path("messages").get(2).path("tool_calls").get(0).path("id").asText());
            assertFalse(body.path("tools").get(0).path("function").path("strict").asBoolean(true));
            assertEquals(100, body.path("max_tokens").asInt());
            assertFalse(body.path("stream").asBoolean(true));
            gateway.call(new Request("system", List.of(ModelGateway.Message.user("check"),
                    ModelGateway.Message.assistant(reply.content()), ModelGateway.Message.tools(List.of(
                    new ToolReply("call-2", "local-2", Map.of("success", true))))), List.of(spec), "model", 100, 5_000));
            assertEquals(2, calls.get());
            assertEquals("先阅读原文", sent.get().path("messages").get(2).path("reasoning_content").asText());
            assertEquals("call-2", sent.get().path("messages").get(3).path("tool_call_id").asText());
        } finally { server.stop(0); }
    }

    @Test void errorsUseOneAttemptAndNeverExposeProviderBody() throws Exception {
        for (int status : List.of(401, 402, 403, 429, 404, 408, 504, 500)) {
            AtomicInteger calls = new AtomicInteger();
            HttpServer server = server(status, "{\"error\":\"secret detail\"}", calls, new AtomicReference<>());
            try (ChatCompletionsModelGateway gateway = new ChatCompletionsModelGateway("test-key", endpoint(server))) {
                ModelException failure = assertThrows(ModelException.class, () -> gateway.call(simpleRequest()));
                assertEquals(status == 401 || status == 403 ? "MODEL_AUTH_ERROR" : status == 402 ? "MODEL_PAYMENT_REQUIRED" : status == 429 ? "MODEL_RATE_LIMITED"
                                : status == 408 || status == 504 ? "MODEL_TIMEOUT" : "MODEL_ERROR",
                        failure.code());
                assertFalse(failure.getMessage().contains("secret detail"));
                assertEquals(1, calls.get());
            } finally { server.stop(0); }
        }
    }

    @Test void malformedToolArgumentsRemainPairedForModelCorrection() throws Exception {
        for (String raw : List.of("not-json", "[]", "{} {}")) {
            AtomicReference<JsonNode> sent = new AtomicReference<>();
            String response = completion("tool_calls", "\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\","
                    + "\"function\":{\"name\":\"read_chapter\",\"arguments\":" + JSON.writeValueAsString(raw) + "}}]");
            HttpServer server = server(200, response, new AtomicInteger(), sent);
            try (ChatCompletionsModelGateway gateway = new ChatCompletionsModelGateway("test-key", endpoint(server))) {
                Response first = gateway.call(simpleRequest());
                assertEquals(FinishReason.TOOL_CALLS, first.finishReason());
                assertEquals(raw, first.calls().getFirst().arguments().asText());
                gateway.call(new Request("system", List.of(ModelGateway.Message.user("check"),
                        ModelGateway.Message.assistant(first.content()), ModelGateway.Message.tools(List.of(
                                new ToolReply("call-1", "execution-1", Map.of("success", false,
                                        "error", Map.of("code", "VALIDATION_ERROR")))))),
                        List.of(), "model", 100, 5_000));
                assertEquals(raw, sent.get().path("messages").get(2).path("tool_calls").get(0)
                        .path("function").path("arguments").asText());
                assertEquals("call-1", sent.get().path("messages").get(3).path("tool_call_id").asText());
            } finally { server.stop(0); }
        }
    }

    @Test void malformedResponsesAreProtocolErrors() throws Exception {
        for (String response : List.of("", "null", "[]", "not-json", "{}", "{} {}",
                "{\"choices\":[{\"message\":{\"role\":\"user\"}}]}",
                completion("stop", "\"content\":[]"),
                completion("tool_calls", "\"tool_calls\":[{\"id\":7,\"type\":\"function\","
                        + "\"function\":{\"name\":\"read_chapter\",\"arguments\":\"{}\"}}]"),
                completion("tool_calls", "\"tool_calls\":[{\"id\":\"c\",\"type\":\"function\","
                        + "\"function\":{\"name\":7,\"arguments\":\"{}\"}}]"))) {
            HttpServer server = server(200, response, new AtomicInteger(), new AtomicReference<>());
            try (var gateway = new ChatCompletionsModelGateway("test-key", endpoint(server))) {
                assertEquals("INVALID_MODEL_RESPONSE", assertThrows(ModelException.class,
                        () -> gateway.call(simpleRequest())).code(), response);
            } finally { server.stop(0); }
        }
    }

    @Test void finishReasonsAndRefusalArePreserved() throws Exception {
        Map<String, FinishReason> reasons = Map.of("stop", FinishReason.FINAL, "eos", FinishReason.FINAL,
                "tool_calls", FinishReason.TOOL_CALLS, "length", FinishReason.TRUNCATED,
                "content_filter", FinishReason.REFUSED, "unknown", FinishReason.OTHER);
        for (var entry : reasons.entrySet()) {
            HttpServer server = server(200, completion(entry.getKey(), "\"content\":\"完成\""),
                    new AtomicInteger(), new AtomicReference<>());
            try (var gateway = new ChatCompletionsModelGateway("test-key", endpoint(server))) {
                Response response = gateway.call(simpleRequest());
                assertEquals(entry.getValue(), response.finishReason());
                assertEquals(entry.getKey(), response.rawFinishReason());
                assertEquals("完成", response.text());
            } finally { server.stop(0); }
        }
        HttpServer server = server(200, completion("stop", "\"refusal\":\"Cannot comply\",\"content\":null"),
                new AtomicInteger(), new AtomicReference<>());
        try (var gateway = new ChatCompletionsModelGateway("test-key", endpoint(server))) {
            assertEquals(FinishReason.REFUSED, gateway.call(simpleRequest()).finishReason());
        } finally { server.stop(0); }
    }

    @Test void stopWithToolCallsIsNotRewrittenAsPermissionToExecute() throws Exception {
        HttpServer server = server(200, completion("stop", "\"tool_calls\":[{\"id\":\"c\",\"type\":\"function\","
                + "\"function\":{\"name\":\"read_chapter\",\"arguments\":\"{}\"}}]"),
                new AtomicInteger(), new AtomicReference<>());
        try (var gateway = new ChatCompletionsModelGateway("test-key", endpoint(server))) {
            Response response = gateway.call(simpleRequest());
            assertEquals(FinishReason.FINAL, response.finishReason());
            assertEquals(1, response.calls().size()); // AgentLoop rejects the inconsistent batch.
        } finally { server.stop(0); }
    }

    @Test void timeoutNetworkFailureAndExpiredBudgetAreDistinct() throws Exception {
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch release = new CountDownLatch(1);
        slow.createContext("/v1/chat/completions", exchange -> {
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        slow.start();
        try (var gateway = new ChatCompletionsModelGateway("test-key", endpoint(slow))) {
            assertEquals("MODEL_TIMEOUT", assertThrows(ModelException.class,
                    () -> gateway.call(simpleRequest(100))).code());
            assertEquals("RUN_TIMEOUT", assertThrows(ModelException.class,
                    () -> gateway.call(simpleRequest(0))).code());
        } finally { release.countDown(); slow.stop(0); }
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        try (var gateway = new ChatCompletionsModelGateway("test-key", "http://127.0.0.1:" + port)) {
            assertEquals("MODEL_NETWORK_ERROR", assertThrows(ModelException.class,
                    () -> gateway.call(simpleRequest())).code());
        }
    }

    @Test void interruptionCancelsAnInFlightRequest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try (var gateway = new ChatCompletionsModelGateway("test-key", endpoint(server))) {
            Thread worker = Thread.ofPlatform().start(() -> {
                try { gateway.call(simpleRequest()); }
                catch (Throwable e) { failure.set(e); }
            });
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                worker.interrupt();
                worker.join(2_000);
                assertFalse(worker.isAlive());
                assertInstanceOf(CancellationException.class, failure.get());
            } finally { worker.interrupt(); release.countDown(); worker.join(2_000); }
        } finally { release.countDown(); server.stop(0); }
    }

    private static String completion(String reason, String fields) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\"," + fields
                + "},\"finish_reason\":\"" + reason + "\"}]}";
    }

    private static Request simpleRequest() {
        return simpleRequest(5_000);
    }

    private static Request simpleRequest(long timeout) {
        return new Request("system", List.of(ModelGateway.Message.user("check")), List.of(), "model", 100, timeout);
    }

    private static HttpServer server(int status, String response, AtomicInteger calls,
                                     AtomicReference<JsonNode> body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            if (!"POST".equals(exchange.getRequestMethod())
                    || !"Bearer test-key".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                    || !"application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }
            try { body.set(JSON.readTree(exchange.getRequestBody().readAllBytes())); }
            catch (Exception e) { throw new IllegalStateException(e); }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        return server;
    }

    private static String endpoint(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort(); }
}
