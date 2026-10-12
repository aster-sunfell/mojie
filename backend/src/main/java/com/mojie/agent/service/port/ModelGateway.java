package com.mojie.agent.service.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One provider attempt. Implementations must not execute tools or retry. */
public interface ModelGateway {
    Response call(Request request) throws ModelException;

    /** Emits public text during one attempt and returns the complete protocol response. */
    default Response call(Request request, java.util.function.Consumer<TextDelta> onDelta)
            throws ModelException {
        // Keep non-streaming test/custom gateways compatible without inventing incremental text.
        return call(request);
    }

    record TextDelta(String text) {}

    record Request(String systemPrompt, List<Message> messages, List<ToolSpec> tools,
                   String modelId, int maxOutputTokens, long timeoutMillis) {
        public Request { messages = List.copyOf(messages); tools = List.copyOf(tools); }
    }

    /** Keep block order. Opaque blocks carry provider protocol data such as thinking signatures. */
    sealed interface ContentPart permits TextPart, ToolCall, ToolReply, OpaquePart {}
    record TextPart(String text) implements ContentPart {}
    record ToolCall(String id, String name, JsonNode arguments) implements ContentPart {}
    record ToolReply(String callId, String executionId, Map<String, Object> result) implements ContentPart {}
    record OpaquePart(JsonNode providerBlock) implements ContentPart {}

    record Message(String role, List<ContentPart> content) {
        public Message { content = List.copyOf(content); }
        public static Message user(String text) { return new Message("user", List.of(new TextPart(text))); }
        public static Message assistant(List<ContentPart> parts) { return new Message("assistant", parts); }
        public static Message tools(List<ToolReply> replies) { return new Message("user", List.copyOf(replies)); }
        public List<ToolReply> replies() { return content.stream().filter(ToolReply.class::isInstance).map(ToolReply.class::cast).toList(); }
    }

    record ToolSpec(String name, String description, Map<String, Object> inputSchema) {}
    enum FinishReason { FINAL, TOOL_CALLS, TRUNCATED, REFUSED, OTHER }
    record Response(List<ContentPart> content, FinishReason finishReason,
                    String providerRequestId, String rawFinishReason, Map<String, Long> usage) {
        public Response { if (content != null) content = List.copyOf(content); }
        public Response(String text, List<ToolCall> calls, FinishReason finishReason,
                        String providerRequestId, String rawFinishReason, Map<String, Long> usage) {
            this(parts(text, calls), finishReason, providerRequestId, rawFinishReason, usage);
        }
        private static List<ContentPart> parts(String text, List<ToolCall> calls) {
            if (calls == null) return null;
            List<ContentPart> parts = new ArrayList<>();
            if (text != null && !text.isEmpty()) parts.add(new TextPart(text));
            parts.addAll(calls);
            return parts;
        }
        public String text() {
            if (content == null) return null;
            StringBuilder text = new StringBuilder();
            for (ContentPart part : content) if (part instanceof TextPart value) text.append(value.text());
            return text.toString();
        }
        public List<ToolCall> calls() {
            if (content == null) return null;
            return content.stream().filter(ToolCall.class::isInstance).map(ToolCall.class::cast).toList();
        }
    }
    final class ModelException extends Exception {
        private final String code;
        public ModelException(String code, String message) { super(message); this.code = Objects.requireNonNull(code); }
        public String code() { return code; }
    }
}
