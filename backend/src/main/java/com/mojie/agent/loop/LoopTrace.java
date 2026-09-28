package com.mojie.agent.loop;

import com.mojie.agent.model.ModelGateway;
import com.mojie.agent.tool.AgentTool;
import java.util.List;

/** Each method is a durable, atomic write. An exception stops the loop. */
public interface LoopTrace {
    void modelStarted(String runId, int callIndex, List<Long> inputMessageSeqs);
    long modelFinished(String runId, int callIndex, ModelGateway.Response response);
    long modelFailed(String runId, int callIndex, String code, String detail);
    String toolStarted(String runId, int callIndex, ModelGateway.ToolCall call);
    long toolFinished(String runId, int callIndex, ModelGateway.ToolCall call, String executionId,
                      AgentTool.ToolResult result, String json, int cumulativeCodePoints, boolean returnedToModel);
    void toolAborted(String runId, int callIndex, ModelGateway.ToolCall call, String reason);
}
