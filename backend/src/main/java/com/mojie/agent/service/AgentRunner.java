package com.mojie.agent.service;

import com.mojie.agent.model.RunData;
import com.mojie.agent.service.loop.*;
import com.mojie.agent.service.port.*;
import com.mojie.agent.service.tool.*;
import java.time.Clock;
import java.util.*;

public final class AgentRunner {
  private final RunLifecycleService lifecycle;
  private final RunCompletionService completion;
  private final RunQueryService reader;
  private final ModelGateway model;
  private final LoopTrace trace;
  private final List<AgentTool> tools;
  private final RunExecutor executor;
  private final Clock clock;
  private final UUID processId;

  public AgentRunner(
      RunLifecycleService lifecycle,
      RunCompletionService completion,
      RunQueryService reader,
      ModelGateway model,
      LoopTrace trace,
      List<AgentTool> tools,
      RunExecutor executor,
      Clock clock,
      UUID processId) {
    this.lifecycle = lifecycle;
    this.completion = completion;
    this.reader = reader;
    this.model = model;
    this.trace = trace;
    this.tools = List.copyOf(tools);
    this.executor = executor;
    this.clock = clock;
    this.processId = processId;
  }

  public void execute(String projectId, String runId, RunExecutor.ExecutionControl control) {
    try {
      if (!lifecycle.start(projectId, runId, processId)) {
        completion.finish(
            projectId, runId, new LoopOutcome.Failed("RUN_TIMEOUT", "Queued run was not claimed"));
        return;
      }
      RunData run = reader.load(runId);
      ToolContext context =
          new ToolContext(
              run.runId(),
              run.projectId(),
              run.targetChapterId(),
              run.baseVersion(),
              run.baseContent());
      control.onCancel(context::cancelActiveSearch);
      AgentLoop loop =
          new AgentLoop(
              model,
              tools,
              trace,
              LoopLimits.defaults().withContextWindowTokens(run.contextWindowTokens()),
              clock,
              control::cancelled);
      LoopOutcome outcome =
          loop.run(
              new LoopInput(
                  context,
                  run.instruction(),
                  run.systemPrompt(),
                  run.modelId(),
                  run.maxOutputTokens(),
                  run.deadline()));
      completion.finish(projectId, runId, outcome);
    } catch (RuntimeException failure) {
      try {
        completion.finish(
            projectId, runId, new LoopOutcome.Failed("PERSISTENCE_ERROR", "Run worker failed"));
      } catch (RuntimeException unavailable) {
        executor.deferTerminal(
            new RunExecutor.PendingTerminal(
                projectId, runId, "PERSISTENCE_ERROR", "Run worker failed"));
      }
    }
  }
}
