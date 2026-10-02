package com.mojie.agent.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.mojie.agent.service.port.ModelGateway.ToolSpec;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public interface AgentTool {
    ToolSpec spec();
    ToolResult execute(JsonNode arguments, ToolContext context);

    record Evidence(String kind, String resourceType, String resourceId, String version,
                    Integer line, Integer codePointOffset) {}
    record ToolResult(boolean success, Object data, ToolError error, List<Evidence> evidence) {
        public ToolResult { evidence = List.copyOf(evidence); }
        public static ToolResult ok(Object data, List<Evidence> evidence) { return new ToolResult(true, data, null, evidence); }
        public static ToolResult error(String code, String message, boolean retryable) {
            return new ToolResult(false, null, new ToolError(code, message, retryable), List.of());
        }
        public Map<String, Object> wire() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("success", success);
            value.put("data", data == null ? Map.of() : data);
            value.put("error", error == null ? null : Map.of("code", error.code(), "message", error.message(),
                    "retryableByModel", error.retryableByModel()));
            return value;
        }
    }
    record ToolError(String code, String message, boolean retryableByModel) {}
}
