package com.mojie.agent.service.loop;

import com.mojie.agent.service.tool.ToolContext;
import java.time.Instant;

public record LoopInput(ToolContext context, String instruction, String systemPrompt, String modelId,
                        int maxOutputTokens, Instant deadline) {
    public String initialUserMessage() {
        return initialUserMessage(context.targetChapterId(), context.baseVersion(), instruction);
    }
    public static String initialUserMessage(String targetChapterId, long baseVersion, String instruction) {
        return "目标章节 ID: " + targetChapterId + "\n目标章节基准版本: " + baseVersion
                + "\n请使用 read_chapter 读取完整目标章后再形成最终分析。\n检查要求:\n" + instruction;
    }
}
