package com.mojie.skill.service.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.mojie.agent.service.tool.ToolContext;
import java.util.*;

public interface SkillExecutor {
  Map<String, Object> capabilities();

  default void stop(String id) {}

  JsonNode execute(
      String id,
      Map<String, byte[]> files,
      String script,
      List<String> arguments,
      Map<String, byte[]> inputs,
      ToolContext cancellation);
}
