package com.mojie.agent.service.port;

import com.mojie.agent.model.RunData;
import com.mojie.agent.service.loop.*;
import com.mojie.agent.service.tool.*;
import java.util.List;

public interface ConversationRuntime {
  record Plan(LoopInput input, LoopLimits limits, List<AgentTool> tools) {}

  Plan prepare(RunData run, ToolContext context, List<AgentTool> baseTools);
}
