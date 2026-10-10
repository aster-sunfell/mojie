package com.mojie.agent.service.loop;

import com.mojie.agent.service.tool.ToolContext;
import java.time.Instant;

public record LoopInput(
    ToolContext context,
    String instruction,
    String systemPrompt,
    String modelId,
    int maxOutputTokens,
    Instant deadline,
    java.util.List<com.mojie.agent.service.port.ModelGateway.Message> initialMessages,
    java.util.List<String> historyMessageIds,
    int initialModelCalls,
    Compactor compactor) {
  public record Reduction(
      java.util.List<com.mojie.agent.service.port.ModelGateway.Message> messages,
      java.util.List<String> historyIds,
      int modelCalls) {}

  public interface Compactor {
    Reduction compact(
        java.util.List<com.mojie.agent.service.port.ModelGateway.Message> messages, int modelCalls);
  }

  public LoopInput(
      ToolContext context,
      String instruction,
      String systemPrompt,
      String modelId,
      int maxOutputTokens,
      Instant deadline,
      java.util.List<com.mojie.agent.service.port.ModelGateway.Message> messages,
      java.util.List<String> ids,
      int calls) {
    this(
        context,
        instruction,
        systemPrompt,
        modelId,
        maxOutputTokens,
        deadline,
        messages,
        ids,
        calls,
        null);
  }

  public LoopInput(
      ToolContext context,
      String instruction,
      String systemPrompt,
      String modelId,
      int maxOutputTokens,
      Instant deadline) {
    this(
        context,
        instruction,
        systemPrompt,
        modelId,
        maxOutputTokens,
        deadline,
        null,
        java.util.List.of(),
        0,
        null);
  }

  public String initialUserMessage() {
    return context.targetChapterId() == null
        ? instruction
        : initialUserMessage(context.targetChapterId(), context.baseVersion(), instruction);
  }

  public static String initialUserMessage(
      String targetChapterId, long baseVersion, String instruction) {
    return "目标章节 ID: "
        + targetChapterId
        + "\n目标章节基准版本: "
        + baseVersion
        + "\n请使用 read_chapter 读取完整目标章后再形成最终分析。\n检查要求:\n"
        + instruction;
  }
}
