package com.mojie.support;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Converts deterministic complete fixtures to the corresponding provider stream protocol. */
public final class StreamingResponses {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private StreamingResponses() {}

  public static String chat(String response) throws Exception {
    ObjectNode root = (ObjectNode) JSON.readTree(response);
    var choices = root.withArray("choices");
    for (var raw : choices) {
      ObjectNode choice = (ObjectNode) raw;
      choice.put("index", 0);
      ObjectNode message = (ObjectNode) choice.remove("message");
      choice.set("delta", message);
      var calls = message.path("tool_calls");
      for (int i = 0; i < calls.size(); i++) ((ObjectNode) calls.get(i)).put("index", i);
    }
    return frame(root.toString()) + frame("[DONE]");
  }

  public static String anthropic(String response) throws Exception {
    ObjectNode message = (ObjectNode) JSON.readTree(response);
    var content = message.remove("content");
    String stop = message.path("stop_reason").asText();
    var usage = message.get("usage").deepCopy();
    message.putArray("content");
    message.putNull("stop_reason").putNull("stop_sequence");
    ((ObjectNode) message.get("usage")).put("output_tokens", 0);
    StringBuilder stream = new StringBuilder(frame(JSON.createObjectNode()
        .put("type", "message_start").set("message", message).toString()));
    for (int i = 0; i < content.size(); i++) {
      ObjectNode block = (ObjectNode) content.get(i).deepCopy();
      String kind = block.path("type").asText();
      ObjectNode blockDelta = null;
      if (kind.equals("text")) {
        blockDelta = JSON.createObjectNode().put("type", "text_delta").put("text", block.path("text").asText());
        block.put("text", "");
      } else if (kind.equals("tool_use")) {
        blockDelta = JSON.createObjectNode().put("type", "input_json_delta").put("partial_json", block.path("input").toString());
        block.putObject("input");
      }
      stream.append(frame(JSON.createObjectNode().put("type", "content_block_start")
          .put("index", i).set("content_block", block).toString()));
      if (blockDelta != null) stream.append(frame(JSON.createObjectNode().put("type", "content_block_delta")
          .put("index", i).set("delta", blockDelta).toString()));
      stream.append(frame(JSON.createObjectNode().put("type", "content_block_stop").put("index", i).toString()));
    }
    var delta = JSON.createObjectNode().put("type", "message_delta");
    delta.set("delta", JSON.createObjectNode().put("stop_reason", stop).putNull("stop_sequence"));
    delta.set("usage", usage);
    return stream.append(frame(delta.toString())).append(frame("{\"type\":\"message_stop\"}")).toString();
  }

  private static String frame(String data) {
    String event = "";
    if (!"[DONE]".equals(data)) {
      try {
        var type = JSON.readTree(data).path("type");
        if (type.isTextual()) event = "event: " + type.asText() + "\n";
      } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
    return event + "data: " + data + "\n\n";
  }
}
