package com.mojie.agent.model.anthropic;

import com.anthropic.client.AnthropicClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.agent.model.ModelGateway;
import com.mojie.agent.model.ModelGateway.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AnthropicModelGatewayIT {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test void completeBlocksAndToolSchemaRoundTrip() throws Exception {
        assumeTrue("1".equals(System.getenv("MOJIE_TEST_LOCAL_HTTP")));
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = server(200, """
                {"id":"msg_test","type":"message","role":"assistant","model":"claude-test",
                 "content":[{"type":"thinking","thinking":"hidden","signature":"signed"},
                            {"type":"text","text":"查看目标章"},
                            {"type":"tool_use","id":"tool-1","name":"read_chapter","input":{"chapterId":"chapter-1"}}],
                 "stop_reason":"tool_use","stop_sequence":null,
                 "usage":{"input_tokens":12,"output_tokens":34}}
                """, calls, body);
        AnthropicClient client = AnthropicModelGateway.createClient("test", endpoint(server));
        try (AnthropicModelGateway gateway = new AnthropicModelGateway(client)) {
            ToolSpec tool = new ToolSpec("read_chapter", "Read chapter", Map.of("type", "object",
                    "properties", Map.of("chapterId", Map.of("type", "string")),
                    "required", List.of("chapterId"), "additionalProperties", false));
            OpaquePart thinking = new OpaquePart(JSON.readTree("{\"type\":\"thinking\",\"thinking\":\"previous\",\"signature\":\"old-sig\"}"));
            ToolCall previous = new ToolCall("previous-tool", "read_chapter", JSON.readTree("{\"chapterId\":\"chapter-0\"}"));
            ModelGateway.Message assistant = ModelGateway.Message.assistant(List.of(thinking, previous));
            ModelGateway.Message reply = ModelGateway.Message.tools(List.of(new ToolReply("previous-tool", "local-1",
                    Map.of("success", false, "error", Map.of("code", "RESOURCE_NOT_FOUND", "message", "missing",
                            "retryableByModel", true), "data", Map.of()))));
            Response response = gateway.call(new Request("system", List.of(ModelGateway.Message.user("check"), assistant, reply),
                    List.of(tool), "claude-test", 100, 5_000));
            assertEquals(1, calls.get());
            assertEquals(FinishReason.TOOL_CALLS, response.finishReason());
            assertEquals("tool_use", response.rawFinishReason());
            assertEquals(3, response.content().size());
            assertInstanceOf(OpaquePart.class, response.content().get(0));
            assertEquals("查看目标章", response.text());
            assertEquals("tool-1", response.calls().getFirst().id());
            assertEquals("chapter-1", response.calls().getFirst().arguments().path("chapterId").asText());
            assertEquals(12L, response.usage().get("inputTokens"));
            JsonNode sent = JSON.readTree(body.get());
            assertTrue(sent.path("tools").get(0).path("strict").asBoolean());
            assertFalse(sent.path("tools").get(0).path("input_schema").path("additionalProperties").asBoolean(true));
            assertEquals("old-sig", sent.path("messages").get(1).path("content").get(0).path("signature").asText());
            assertTrue(sent.path("messages").get(2).path("content").get(0).path("is_error").asBoolean());
        } finally { client.close(); server.stop(0); }
    }

    @Test void rateLimitMakesExactlyOneHttpAttempt() throws Exception {
        assumeTrue("1".equals(System.getenv("MOJIE_TEST_LOCAL_HTTP")));
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(429,
                "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}",
                calls, new AtomicReference<>());
        AnthropicClient client = AnthropicModelGateway.createClient("test", endpoint(server));
        try (AnthropicModelGateway gateway = new AnthropicModelGateway(client)) {
            ModelGateway.ModelException failure = assertThrows(ModelGateway.ModelException.class,
                    () -> gateway.call(new Request("system", List.of(ModelGateway.Message.user("check")), List.of(),
                            "claude-test", 100, 5_000)));
            assertEquals("MODEL_RATE_LIMITED", failure.code());
            assertEquals(1, calls.get());
        } finally { client.close(); server.stop(0); }
    }

    private static HttpServer server(int status, String response, AtomicInteger calls, AtomicReference<String> body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            calls.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
