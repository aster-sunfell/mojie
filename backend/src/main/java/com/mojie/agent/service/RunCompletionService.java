package com.mojie.agent.service;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.agent.model.*;
import com.mojie.agent.repository.*;
import com.mojie.agent.service.loop.LoopOutcome;
import com.mojie.agent.service.tool.ToolContext.Candidate;
import com.mojie.common.transaction.TransactionRunner;
import com.mojie.proposal.repository.ProposalRepository;
import java.time.Clock;
import java.util.*;

public final class RunCompletionService {
  private final RunRepository runs;
  private final RunTraceRepository trace;
  private final RunEventRepository events;
  private final ProposalRepository proposals;
  private final TransactionRunner transactions;
  private final Clock clock;
  private final JsonMapper json = JsonMapper.builder().build();

  public RunCompletionService(
      RunRepository runs,
      RunTraceRepository trace,
      RunEventRepository events,
      ProposalRepository proposals,
      TransactionRunner transactions,
      Clock clock) {
    this.runs = runs;
    this.trace = trace;
    this.events = events;
    this.proposals = proposals;
    this.transactions = transactions;
    this.clock = clock;
  }

  public TerminalResult finish(String projectId, String runId, LoopOutcome outcome) {
    return transactions.requiresNew(
        () -> {
          if (!runs.lockProject(projectId)) throw new IllegalStateException("Project unavailable");
          RunRecord row = Objects.requireNonNull(runs.lockRun(projectId, runId), "Run unavailable");
          if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(row.status()))
            return runs.existingTerminal(runId, row.status());
          String status, code = null, detail = null, analysis = null;
          Candidate candidate = null;
          if (row.cancelRequested() || outcome instanceof LoopOutcome.Cancelled)
            status = "CANCELLED";
          else if (!clock.instant().isBefore(row.deadline())) {
            status = "FAILED";
            code = "RUN_TIMEOUT";
            detail = "Run deadline reached";
          } else if (outcome instanceof LoopOutcome.Failed failure) {
            status = "FAILED";
            code = failure.code();
            detail = failure.detail();
          } else if (outcome instanceof LoopOutcome.Completed completed) {
            if (!"RUNNING".equals(row.status()))
              throw new IllegalStateException("Only a running task can succeed");
            validateSuccess(runId, row, completed);
            status = "SUCCEEDED";
            analysis = completed.analysis();
            candidate = completed.candidate();
          } else throw new IllegalArgumentException("Unknown loop outcome");
          requireOne(runs.updateTerminal(runId, status, analysis, code, detail));
          String proposalId = null;
          if (candidate != null) {
            proposalId = UUID.randomUUID().toString();
            if (candidate.settingEdit() != null) {
              var edit = candidate.settingEdit();
              requireOne(
                  proposals.insertSetting(
                      proposalId,
                      runId,
                      projectId,
                      edit.settingId(),
                      candidate.baseVersion(),
                      edit.baseType(),
                      edit.baseTitle(),
                      candidate.baseContent(),
                      edit.proposedType(),
                      edit.proposedTitle(),
                      candidate.proposedContent(),
                      candidate.reason()));
            } else
              requireOne(
                  proposals.insert(
                      proposalId,
                      runId,
                      projectId,
                      candidate.chapterId() == null ? row.targetChapterId() : candidate.chapterId(),
                      candidate.chapterId() == null ? row.baseVersion() : candidate.baseVersion(),
                      candidate.chapterId() == null ? row.baseContent() : candidate.baseContent(),
                      candidate.proposedContent(),
                      candidate.reason()));
          }
          if (!"SUCCEEDED".equals(status)) {
            trace.abortOpenCalls(runId, status);
            trace.abortOpenSteps(runId, status);
          }
          String type =
              switch (status) {
                case "SUCCEEDED" -> "run.succeeded";
                case "CANCELLED" -> "run.cancelled";
                case "FAILED" -> "run.failed";
                default -> throw new IllegalArgumentException("Invalid terminal status");
              };
          Map<String, Object> payload = new LinkedHashMap<>();
          payload.put("status", status);
          if (code != null) payload.put("errorCode", code);
          if (proposalId != null) payload.put("proposalId", proposalId);
          long seq = events.nextSequence(runId);
          requireOne(events.insert(runId, seq, type, encode(payload)));
          runs.releaseSlot(projectId, runId);
          return new TerminalResult(status, proposalId, code);
        });
  }

  private void validateSuccess(String runId, RunRecord row, LoopOutcome.Completed completed) {
    if (completed.analysis() == null || completed.analysis().isBlank())
      throw new IllegalStateException("Empty analysis");
    if (row.modelCalls() != completed.modelCalls()
        || row.toolCalls() != completed.toolCalls()
        || row.toolText() != completed.toolTextCodePoints())
      throw new IllegalStateException("Budget counters differ from trace");
    if (!trace.hasFinalResponse(runId, completed.modelCalls()))
      throw new IllegalStateException("Final model response is not committed");
    if (row.targetChapterId() != null
        && !trace.hasTargetInput(
            runId, completed.modelCalls(), row.targetChapterId(), row.baseVersion()))
      throw new IllegalStateException("Target chapter was not sent to final model call");
    if (completed.candidate() != null
        && completed.candidate().chapterId() != null
        && !trace.hasTargetInput(
            runId,
            completed.modelCalls(),
            completed.candidate().chapterId(),
            completed.candidate().baseVersion()))
      throw new IllegalStateException("Candidate chapter was not sent to final model call");
    if (completed.candidate() != null && !trace.hasCandidateStep(runId))
      throw new IllegalStateException("Candidate has no successful tool step");
    if (completed.candidate() != null
        && completed.candidate().settingEdit() != null
        && !trace.hasSettingInput(
            runId,
            completed.modelCalls(),
            completed.candidate().settingEdit().settingId(),
            completed.candidate().baseVersion()))
      throw new IllegalStateException("Candidate setting was not sent to final model call");
    if (trace.openCalls(runId) != 0) throw new IllegalStateException("Model call remains open");
    if (trace.openSteps(runId) != 0) throw new IllegalStateException("Tool step remains open");
  }

  private static void requireOne(int count) {
    if (count != 1) throw new IllegalStateException("Run record changed concurrently");
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot serialize event", e);
    }
  }
}
