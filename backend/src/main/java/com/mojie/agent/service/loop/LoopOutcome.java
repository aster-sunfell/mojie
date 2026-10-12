package com.mojie.agent.service.loop;

import com.mojie.agent.service.tool.ToolContext.Candidate;

public sealed interface LoopOutcome {
  record Completed(
      String analysis,
      int modelCalls,
      int toolCalls,
      int toolTextCodePoints,
      java.util.List<Candidate> candidates)
      implements LoopOutcome {
    public Completed {
      candidates = java.util.List.copyOf(candidates);
    }

    public Candidate candidate() {
      return candidates.isEmpty() ? null : candidates.getFirst();
    }

    public Completed(
        String analysis,
        Candidate candidate,
        int modelCalls,
        int toolCalls,
        int toolTextCodePoints) {
      this(
          analysis,
          modelCalls,
          toolCalls,
          toolTextCodePoints,
          candidate == null ? java.util.List.of() : java.util.List.of(candidate));
    }
  }

  record Failed(String code, String detail) implements LoopOutcome {}

  record Cancelled() implements LoopOutcome {}
}
