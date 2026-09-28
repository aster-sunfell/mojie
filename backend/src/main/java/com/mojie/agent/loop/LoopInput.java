package com.mojie.agent.loop;

import com.mojie.agent.tool.ToolContext;
import java.time.Instant;

public record LoopInput(ToolContext context, String instruction, String systemPrompt, String modelId,
                        int maxOutputTokens, Instant deadline) {}
