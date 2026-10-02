package com.mojie.infrastructure.model.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.Timeout;
import com.anthropic.errors.*;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.agent.service.port.ModelGateway;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.*;

/** Anthropic Messages adapter: one SDK request, zero SDK retries, no tool execution. */
public final class AnthropicModelGateway implements ModelGateway, AutoCloseable {
    private final AnthropicClient client;
    private final JsonMapper json = JsonMapper.builder().build();
    private final boolean ownsClient;

    public static AnthropicModelGateway fromEnvironment() {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("ANTHROPIC_API_KEY is missing");
        return new AnthropicModelGateway(createClient(apiKey, System.getenv("MOJIE_ANTHROPIC_BASE_URL")), true);
    }

    public static AnthropicModelGateway configured(String apiKey, String baseUrl) {
        return new AnthropicModelGateway(createClient(apiKey, baseUrl), true);
    }

    static AnthropicClient createClient(String apiKey, String baseUrl) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Timeout.builder().connect(Duration.ofSeconds(10)).request(Duration.ofSeconds(60)).build())
                .maxRetries(0);
        if (baseUrl != null) builder.baseUrl(baseUrl);
        return builder.build();
    }

    /** Injection constructor for deterministic adapter tests. */
    public AnthropicModelGateway(AnthropicClient client) { this(client, false); }
    private AnthropicModelGateway(AnthropicClient client, boolean ownsClient) {
        this.client = Objects.requireNonNull(client); this.ownsClient = ownsClient;
    }

    @Override public Response call(Request request) throws ModelException {
        if (request.timeoutMillis() <= 0) throw new ModelException("RUN_TIMEOUT", "No request time remains");
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(request.modelId()).maxTokens(request.maxOutputTokens())
                .system(request.systemPrompt()).messages(toMessages(request.messages()));
        for (ToolSpec spec : request.tools()) params.addTool(toTool(spec));
        try {
            com.anthropic.models.messages.Message reply = client.messages().create(params.build(),
                    RequestOptions.builder().timeout(Duration.ofMillis(request.timeoutMillis())).build());
            return fromSdk(reply);
        } catch (UnauthorizedException | PermissionDeniedException e) {
            throw new ModelException("MODEL_AUTH_ERROR", "Model authentication failed");
        } catch (RateLimitException e) {
            throw new ModelException("MODEL_RATE_LIMITED", "Model rate limit reached");
        } catch (AnthropicIoException e) {
            throw new ModelException(hasTimeoutCause(e) ? "MODEL_TIMEOUT" : "MODEL_NETWORK_ERROR", "Model connection failed");
        } catch (BadRequestException e) {
            throw new ModelException("MODEL_ERROR", "Model request was rejected");
        } catch (AnthropicServiceException e) {
            if (e.statusCode() == 402)
                throw new ModelException("MODEL_PAYMENT_REQUIRED", "模型账户余额不足或需要开通付费权限，请检查模型服务账户。");
            throw new ModelException("MODEL_ERROR", "Model provider failed with HTTP " + e.statusCode());
        } catch (AnthropicException e) {
            throw new ModelException("MODEL_ERROR", "Model SDK failed");
        }
    }

    private List<MessageParam> toMessages(List<ModelGateway.Message> messages) throws ModelException {
        List<MessageParam> converted = new ArrayList<>();
        for (ModelGateway.Message message : messages) {
            MessageParam.Role role = switch (message.role()) {
                case "user" -> MessageParam.Role.USER;
                case "assistant" -> MessageParam.Role.ASSISTANT;
                default -> throw new ModelException("INVALID_MODEL_RESPONSE", "Unsupported message role");
            };
            List<ContentBlockParam> blocks = new ArrayList<>();
            for (ContentPart part : message.content()) blocks.add(toBlock(part));
            converted.add(MessageParam.builder().role(role).contentOfBlockParams(blocks).build());
        }
        return converted;
    }

    private ContentBlockParam toBlock(ContentPart part) throws ModelException {
        if (part instanceof TextPart text) return ContentBlockParam.ofText(text.text());
        if (part instanceof ToolCall call) {
            if (call.arguments() == null || !call.arguments().isObject())
                throw new ModelException("INVALID_MODEL_RESPONSE", "Provider tool arguments are not an object");
            ToolUseBlockParam.Input.Builder input = ToolUseBlockParam.Input.builder();
            call.arguments().fields().forEachRemaining(entry -> input.putAdditionalProperty(entry.getKey(), JsonValue.fromJsonNode(entry.getValue())));
            return ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                    .id(call.id()).name(call.name()).input(input.build()).build());
        }
        if (part instanceof ToolReply reply) {
            boolean error = Boolean.FALSE.equals(reply.result().get("success"));
            return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(reply.callId()).content(encode(reply.result())).isError(error).build());
        }
        if (part instanceof OpaquePart opaque) {
            JsonNode block = opaque.providerBlock();
            return switch (block.path("type").asText()) {
                case "thinking" -> ContentBlockParam.ofThinking(ThinkingBlockParam.builder()
                        .thinking(block.path("thinking").asText()).signature(block.path("signature").asText()).build());
                case "redacted_thinking" -> ContentBlockParam.ofRedactedThinking(
                        RedactedThinkingBlockParam.builder().data(block.path("data").asText()).build());
                default -> throw new ModelException("INVALID_MODEL_RESPONSE", "Unsupported provider content block");
            };
        }
        throw new ModelException("INVALID_MODEL_RESPONSE", "Unsupported message content block");
    }

    private static Tool toTool(ToolSpec spec) {
        Map<String, Object> schema = spec.inputSchema();
        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        Object rawProperties = schema.get("properties");
        if (rawProperties instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) properties.putAdditionalProperty(entry.getKey().toString(), JsonValue.from(entry.getValue()));
        }
        Tool.InputSchema.Builder input = Tool.InputSchema.builder()
                .type(JsonValue.from("object")).properties(properties.build())
                .putAdditionalProperty("additionalProperties", JsonValue.from(false));
        Object rawRequired = schema.get("required");
        if (rawRequired instanceof List<?> required) input.required(required.stream().map(Object::toString).toList());
        return Tool.builder().name(spec.name()).description(spec.description())
                .inputSchema(input.build()).strict(true).build();
    }

    private Response fromSdk(com.anthropic.models.messages.Message reply) throws ModelException {
        List<ContentPart> content = new ArrayList<>();
        for (ContentBlock block : reply.content()) {
            if (block.text().isPresent()) content.add(new TextPart(block.text().orElseThrow().text()));
            else if (block.toolUse().isPresent()) {
                ToolUseBlock call = block.toolUse().orElseThrow();
                content.add(new ToolCall(call.id(), call.name(), call._input().convert(JsonNode.class)));
            } else if (block.thinking().isPresent()) {
                ThinkingBlock thinking = block.thinking().orElseThrow();
                content.add(new OpaquePart(json.createObjectNode().put("type", "thinking")
                        .put("thinking", thinking.thinking()).put("signature", thinking.signature())));
            } else if (block.redactedThinking().isPresent()) {
                RedactedThinkingBlock redacted = block.redactedThinking().orElseThrow();
                content.add(new OpaquePart(json.createObjectNode().put("type", "redacted_thinking")
                        .put("data", redacted.data())));
            } else throw new ModelException("INVALID_MODEL_RESPONSE", "Unsupported provider response block");
        }
        String rawReason = reply.stopReason().map(StopReason::asString).orElse("");
        FinishReason reason = switch (rawReason) {
            case "end_turn", "stop_sequence" -> FinishReason.FINAL;
            case "tool_use" -> FinishReason.TOOL_CALLS;
            case "max_tokens", "model_context_window_exceeded" -> FinishReason.TRUNCATED;
            case "refusal" -> FinishReason.REFUSED;
            default -> FinishReason.OTHER;
        };
        Map<String, Long> usage = Map.of("inputTokens", reply.usage().inputTokens(),
                "outputTokens", reply.usage().outputTokens());
        return new Response(content, reason, reply.id(), rawReason, usage);
    }

    private String encode(Object value) throws ModelException {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new ModelException("INVALID_MODEL_RESPONSE", "Cannot serialize tool result"); }
    }
    private static boolean hasTimeoutCause(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof java.net.http.HttpTimeoutException) return true;
            if (cause instanceof InterruptedIOException && cause.getMessage() != null) {
                String message = cause.getMessage().toLowerCase(Locale.ROOT);
                if (message.contains("timeout") || message.contains("timed out")) return true;
            }
        }
        return false;
    }
    @Override public void close() { if (ownsClient) client.close(); }
}
