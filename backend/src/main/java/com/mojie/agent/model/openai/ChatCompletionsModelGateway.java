package com.mojie.agent.model.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.agent.model.ModelGateway;
import com.mojie.agent.model.ModelGateway.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** One non-streaming Chat Completions request, with no retries or tool execution. */
public final class ChatCompletionsModelGateway implements ModelGateway, AutoCloseable {
    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final boolean ownsClient;

    public ChatCompletionsModelGateway(String apiKey, String baseUrl) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), apiKey, baseUrl, true);
    }

    public ChatCompletionsModelGateway(HttpClient client, String apiKey, String baseUrl) {
        this(client, apiKey, baseUrl, false);
    }

    private ChatCompletionsModelGateway(HttpClient client, String apiKey, String baseUrl, boolean ownsClient) {
        this.client = Objects.requireNonNull(client);
        this.ownsClient = ownsClient;
        this.apiKey = Objects.requireNonNull(apiKey);
        this.endpoint = URI.create(Objects.requireNonNull(baseUrl) + "/v1/chat/completions");
    }

    @Override public Response call(Request request) throws ModelException {
        if (request.timeoutMillis() <= 0) throw new ModelException("RUN_TIMEOUT", "No request time remains");
        String body = encode(toBody(request));
        HttpRequest http = HttpRequest.newBuilder(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(request.timeoutMillis()))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            HttpResponse<String> reply = client.send(http, HttpResponse.BodyHandlers.ofString());
            if (reply.statusCode() != 200) throw statusError(reply.statusCode());
            return parse(reply.body());
        } catch (HttpTimeoutException e) {
            throw new ModelException("MODEL_TIMEOUT", "Model request timed out");
        } catch (IOException e) {
            throw new ModelException("MODEL_NETWORK_ERROR", "Model connection failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Model request interrupted");
        }
    }

    private Map<String, Object> toBody(Request request) throws ModelException {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", request.systemPrompt()));
        for (Message message : request.messages()) {
            if (message.role().equals("user")) {
                List<ToolReply> replies = message.replies();
                if (!replies.isEmpty()) {
                    if (replies.size() != message.content().size())
                        throw new ModelException("INVALID_MODEL_RESPONSE", "Mixed user content and tool results");
                    for (ToolReply reply : replies) messages.add(Map.of("role", "tool", "tool_call_id", reply.callId(),
                            "content", encode(reply.result())));
                } else {
                    messages.add(Map.of("role", "user", "content", textOnly(message.content())));
                }
            } else if (message.role().equals("assistant")) {
                Map<String, Object> assistant = new LinkedHashMap<>();
                assistant.put("role", "assistant");
                StringBuilder content = new StringBuilder();
                List<Map<String, Object>> calls = new ArrayList<>();
                for (ContentPart part : message.content()) {
                    if (part instanceof TextPart text) content.append(text.text());
                    else if (part instanceof ToolCall call) {
                        // A malformed argument string is still a declared tool call. Replay it verbatim
                        // with the structured validation error so the model can correct its own call.
                        String arguments = call.arguments() != null && call.arguments().isTextual()
                                ? call.arguments().asText() : encode(call.arguments());
                        calls.add(Map.of("id", call.id(), "type", "function", "function",
                                Map.of("name", call.name(), "arguments", arguments)));
                    } else if (part instanceof OpaquePart opaque
                            && "chat_reasoning".equals(opaque.providerBlock().path("type").asText())
                            && opaque.providerBlock().path("reasoning_content").isTextual()) {
                        assistant.put("reasoning_content", opaque.providerBlock().path("reasoning_content").asText());
                    } else throw new ModelException("INVALID_MODEL_RESPONSE", "Unsupported assistant content block");
                }
                assistant.put("content", content.isEmpty() ? null : content.toString());
                if (!calls.isEmpty()) assistant.put("tool_calls", calls);
                messages.add(assistant);
            } else throw new ModelException("INVALID_MODEL_RESPONSE", "Unsupported message role");
        }
        List<Map<String, Object>> tools = request.tools().stream().map(spec -> Map.<String, Object>of(
                "type", "function", "function", Map.of("name", spec.name(), "description", spec.description(),
                        "parameters", spec.inputSchema(), "strict", false))).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.modelId());
        body.put("messages", messages);
        body.put("max_tokens", request.maxOutputTokens());
        body.put("stream", false);
        if (!tools.isEmpty()) body.put("tools", tools);
        return body;
    }

    private String textOnly(List<ContentPart> parts) throws ModelException {
        StringBuilder text = new StringBuilder();
        for (ContentPart part : parts) {
            if (!(part instanceof TextPart value))
                throw new ModelException("INVALID_MODEL_RESPONSE", "Unsupported user content block");
            text.append(value.text());
        }
        return text.toString();
    }

    private Response parse(String body) throws ModelException {
        JsonNode root;
        try { root = json.readTree(body); }
        catch (JsonProcessingException e) { throw invalid("Model response is not JSON"); }
        if (root == null || !root.isObject()) throw invalid("Model response is not an object");
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() != 1) throw invalid("Expected one model choice");
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");
        if (!message.isObject() || !"assistant".equals(message.path("role").asText()))
            throw invalid("Missing assistant message");
        List<ContentPart> content = new ArrayList<>();
        JsonNode reasoning = message.path("reasoning_content");
        if (reasoning.isTextual()) content.add(new OpaquePart(json.createObjectNode()
                .put("type", "chat_reasoning").set("reasoning_content", reasoning)));
        else if (!reasoning.isMissingNode() && !reasoning.isNull()) throw invalid("Invalid reasoning content");
        JsonNode rawText = message.path("content");
        if (rawText.isTextual() && !rawText.asText().isEmpty()) content.add(new TextPart(rawText.asText()));
        else if (!rawText.isMissingNode() && !rawText.isNull() && !rawText.isTextual())
            throw invalid("Unsupported assistant text content");
        JsonNode rawCalls = message.path("tool_calls");
        if (!rawCalls.isMissingNode() && !rawCalls.isNull()) {
            if (!rawCalls.isArray()) throw invalid("Invalid tool calls");
            for (JsonNode rawCall : rawCalls) {
                if (!"function".equals(rawCall.path("type").asText())) throw invalid("Unsupported tool call type");
                JsonNode rawId = rawCall.path("id");
                JsonNode rawName = rawCall.path("function").path("name");
                JsonNode rawArguments = rawCall.path("function").path("arguments");
                if (!rawId.isTextual() || !rawName.isTextual() || !rawArguments.isTextual())
                    throw invalid("Invalid tool call fields");
                String id = rawId.asText();
                String name = rawName.asText();
                if (id.isBlank() || name.isBlank()) throw invalid("Invalid tool call");
                JsonNode arguments;
                try { arguments = json.readTree(rawArguments.asText()); }
                catch (JsonProcessingException e) { arguments = null; }
                if (arguments == null || !arguments.isObject())
                    arguments = json.getNodeFactory().textNode(rawArguments.asText());
                content.add(new ToolCall(id, name, arguments));
            }
        }
        String rawReason = choice.path("finish_reason").asText("");
        FinishReason reason = switch (rawReason) {
            case "tool_calls" -> FinishReason.TOOL_CALLS;
            case "stop", "eos" -> FinishReason.FINAL;
            case "length" -> FinishReason.TRUNCATED;
            case "content_filter" -> FinishReason.REFUSED;
            default -> FinishReason.OTHER;
        };
        if (message.path("refusal").isTextual() && !message.path("refusal").asText().isBlank())
            reason = FinishReason.REFUSED;
        Map<String, Long> usage = null;
        JsonNode rawUsage = root.path("usage");
        if (rawUsage.isObject()) {
            Map<String, Long> parsed = new LinkedHashMap<>();
            if (rawUsage.path("prompt_tokens").isIntegralNumber())
                parsed.put("inputTokens", rawUsage.path("prompt_tokens").longValue());
            if (rawUsage.path("completion_tokens").isIntegralNumber())
                parsed.put("outputTokens", rawUsage.path("completion_tokens").longValue());
            if (!parsed.isEmpty()) usage = parsed;
        }
        String requestId = root.path("id").asText(null);
        return new Response(content, reason, requestId, rawReason, usage);
    }

    private String encode(Object value) throws ModelException {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw invalid("Cannot serialize model request"); }
    }

    private static ModelException invalid(String detail) {
        return new ModelException("INVALID_MODEL_RESPONSE", detail);
    }

    @Override public void close() { if (ownsClient) client.close(); }

    private static ModelException statusError(int status) {
        if (status == 401 || status == 403) return new ModelException("MODEL_AUTH_ERROR", "Model authentication failed");
        if (status == 402) return new ModelException("MODEL_PAYMENT_REQUIRED", "模型账户余额不足或需要开通付费权限，请检查模型服务账户。");
        if (status == 429) return new ModelException("MODEL_RATE_LIMITED", "Model rate limit reached");
        if (status == 408 || status == 504) return new ModelException("MODEL_TIMEOUT", "Model request timed out");
        if (status == 404) return new ModelException("MODEL_ERROR", "Model endpoint or model ID was not found");
        return new ModelException("MODEL_ERROR", "Model provider failed with HTTP " + status);
    }
}
