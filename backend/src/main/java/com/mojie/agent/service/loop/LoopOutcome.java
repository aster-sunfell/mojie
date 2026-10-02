package com.mojie.agent.service.loop;

import com.mojie.agent.service.tool.ToolContext.Candidate;

public sealed interface LoopOutcome {
    record Completed(String analysis, Candidate candidate, int modelCalls, int toolCalls, int toolTextCodePoints) implements LoopOutcome {}
    record Failed(String code, String detail) implements LoopOutcome {}
    record Cancelled() implements LoopOutcome {}
}
