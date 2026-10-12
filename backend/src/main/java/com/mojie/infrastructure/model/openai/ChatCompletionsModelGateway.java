package com.mojie.infrastructure.model.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.agent.service.port.ModelGateway;
import com.mojie.agent.service.port.ModelGateway.*;
import com.mojie.infrastructure.model.*;
import java.io.InputStream;
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
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.concurrent.CancellationException;

/** Streaming and non-streaming Chat Completions requests, with no retries or tool execution. */
public final class ChatCompletionsModelGateway implements ModelGateway, AutoCloseable {
    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final boolean ownsClient;
    private final StreamTimeouts streamTimeouts;

    public ChatCompletionsModelGateway(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, StreamTimeouts.defaults());
    }

    public ChatCompletionsModelGateway(String apiKey, String baseUrl, StreamTimeouts timeouts) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), apiKey, baseUrl, true, timeouts);
    }

    public ChatCompletionsModelGateway(HttpClient client, String apiKey, String baseUrl) {
        this(client, apiKey, baseUrl, false, StreamTimeouts.defaults());
    }

    private ChatCompletionsModelGateway(HttpClient client, String apiKey, String baseUrl, boolean ownsClient,
                                       StreamTimeouts timeouts) {
        this.client = Objects.requireNonNull(client);
        this.ownsClient = ownsClient;
        this.apiKey = Objects.requireNonNull(apiKey);
        this.streamTimeouts = Objects.requireNonNull(timeouts);
        this.endpoint = URI.create(Objects.requireNonNull(baseUrl) + "/v1/chat/completions");
    }

    @Override public Response call(Request request, Consumer<TextDelta> onDelta) throws ModelException {
        if (request.timeoutMillis() <= 0) throw new ModelException("RUN_TIMEOUT", "No request time remains");
        Objects.requireNonNull(onDelta);
        long started = System.nanoTime();
        Map<String, Object> body = toBody(request);
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        HttpRequest http = HttpRequest.newBuilder(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofMillis(Math.min(request.timeoutMillis(), streamTimeouts.firstEvent().toMillis())))
                .POST(HttpRequest.BodyPublishers.ofString(encode(body))).build();
        try {
            HttpResponse<InputStream> reply = client.send(http, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = reply.body();
                 var watchdog = new StreamWatchdog(input, started, request.timeoutMillis(), streamTimeouts);
                 var text = new ModelTextBuffer(onDelta)) {
                if (reply.statusCode() != 200) throw statusError(reply.statusCode());
                if (!reply.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT)
                        .startsWith("text/event-stream")) throw invalid("Model response is not an event stream");
                StreamCompletion completion = new StreamCompletion(text::accept);
                try {
                    SseFrames.read(input, watchdog, completion::accept);
                } catch (IOException e) {
                    watchdog.ensureActive();
                    throw e;
                }
                watchdog.ensureActive();
                return completion.response();
            }
        } catch (HttpTimeoutException e) {
            throw new ModelException("MODEL_TIMEOUT", "Model first event timed out");
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Model stream cancelled");
            throw new ModelException("MODEL_NETWORK_ERROR", "Model stream connection failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Model request interrupted");
        }
    }

    private final class StreamCompletion {
        private final Consumer<TextDelta> onDelta;
        private final StringBuilder text = new StringBuilder(), reasoning = new StringBuilder();
        private final TreeMap<Integer, CallParts> calls = new TreeMap<>();
        private String id, reason;
        private JsonNode usage;
        private boolean hasReasoning, refused;
        private long receivedChars;

        StreamCompletion(Consumer<TextDelta> onDelta) { this.onDelta = onDelta; }

        boolean accept(String data) throws ModelException {
            if ("[DONE]".equals(data)) {
                if (reason == null) throw invalid("Model stream has no finish reason");
                return false;
            }
            JsonNode chunk;
            try { chunk = json.readTree(data); }
            catch (JsonProcessingException e) { throw invalid("Model stream frame is not JSON"); }
            if (chunk == null || !chunk.isObject()) throw invalid("Invalid model stream frame");
            if (chunk.has("error")) throw new ModelException("MODEL_ERROR", "Model provider reported a stream error");
            if (chunk.path("id").isTextual()) {
                String incoming = chunk.path("id").asText();
                if (id != null && !id.equals(incoming)) throw invalid("Model stream response ID changed");
                id = incoming;
            }
            if (chunk.path("usage").isObject()) usage = chunk.get("usage");
            JsonNode choices = chunk.path("choices");
            if (!choices.isArray() || choices.size() > 1) throw invalid("Expected one streamed model choice");
            if (choices.isEmpty()) return true; // Optional final usage-only frame.
            JsonNode choice = choices.get(0);
            if (choice.hasNonNull("index") && choice.path("index").asInt(-1) != 0)
                throw invalid("Unexpected model choice index");
            JsonNode delta = choice.path("delta");
            if (!delta.isObject()) throw invalid("Missing model stream delta");
            if (delta.hasNonNull("role") && !"assistant".equals(delta.path("role").asText()))
                throw invalid("Unexpected streamed message role");
            if (reason != null && !delta.isEmpty()) throw invalid("Model content arrived after finish");
            String added = append(text, delta.path("content"));
            if (!added.isEmpty()) onDelta.accept(new TextDelta(added));
            if (delta.path("reasoning_content").isTextual()) hasReasoning = true;
            append(reasoning, delta.path("reasoning_content"));
            if (delta.path("refusal").isTextual() && !delta.path("refusal").asText().isBlank()) refused = true;
            JsonNode rawCalls = delta.path("tool_calls");
            if (!rawCalls.isMissingNode() && !rawCalls.isNull()) {
                if (!rawCalls.isArray()) throw invalid("Invalid streamed tool calls");
                for (JsonNode raw : rawCalls) {
                    if (!raw.path("index").isIntegralNumber() || !raw.path("index").canConvertToInt())
                        throw invalid("Missing tool call index");
                    int index = raw.path("index").asInt();
                    if (index < 0 || index >= 128) throw invalid("Invalid tool call index");
                    if (raw.hasNonNull("type") && !"function".equals(raw.path("type").asText()))
                        throw invalid("Unsupported streamed tool call type");
                    CallParts call = calls.computeIfAbsent(index, ignored -> new CallParts());
                    append(call.id, raw.path("id"));
                    append(call.name, raw.path("function").path("name"));
                    append(call.arguments, raw.path("function").path("arguments"));
                }
            }
            if (choice.hasNonNull("finish_reason")) {
                if (!choice.path("finish_reason").isTextual() || reason != null)
                    throw invalid("Invalid stream finish reason");
                reason = choice.path("finish_reason").asText();
            }
            return true;
        }

        private String append(StringBuilder target, JsonNode value) throws ModelException {
            if (value.isMissingNode() || value.isNull()) return "";
            if (!value.isTextual()) throw invalid("Invalid streamed content field");
            String added = value.asText();
            receivedChars += added.length();
            if (receivedChars > 1_048_576) throw invalid("Model stream output is too large");
            target.append(added);
            return added;
        }

        Response response() throws ModelException {
            var message = json.createObjectNode().put("role", "assistant").put("content", text.toString());
            if (hasReasoning) message.put("reasoning_content", reasoning.toString());
            if (refused) message.put("refusal", "refused");
            if (!calls.isEmpty()) {
                var tools = message.putArray("tool_calls");
                for (CallParts call : calls.values()) tools.addObject()
                    .put("id", call.id.toString()).put("type", "function")
                    .set("function", json.createObjectNode().put("name", call.name.toString())
                        .put("arguments", call.arguments.toString()));
            }
            var root = json.createObjectNode();
            if (id != null) root.put("id", id);
            if (usage != null) root.set("usage", usage);
            root.putArray("choices").addObject().put("finish_reason", reason).set("message", message);
            return parse(root.toString());
        }
    }

    private static final class CallParts {
        final StringBuilder id = new StringBuilder(), name = new StringBuilder(), arguments = new StringBuilder();
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
        else body.put("tool_choice", "none");
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
        if (reason == FinishReason.FINAL && rawText.isTextual()
                && rawText.asText().stripLeading().startsWith("<｜DSML｜function_calls>"))
            throw invalid("Provider returned native tool-call markup as final text instead of structured tool calls");
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
