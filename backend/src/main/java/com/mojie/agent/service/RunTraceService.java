package com.mojie.agent.service;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.agent.model.RunRecord;
import com.mojie.agent.repository.*;
import com.mojie.agent.service.loop.*;
import com.mojie.agent.service.port.ModelGateway.*;
import com.mojie.agent.service.tool.AgentTool;
import com.mojie.agent.service.tool.AgentTool.Evidence;
import com.mojie.common.transaction.TransactionRunner;
import java.util.*;

public final class RunTraceService implements LoopTrace {
  private final RunRepository runs;
  private final RunTraceRepository trace;
  private final RunEventRepository events;
  private final TransactionRunner transactions;
  private final JsonMapper json = JsonMapper.builder().build();

  public RunTraceService(
      RunRepository runs,
      RunTraceRepository trace,
      RunEventRepository events,
      TransactionRunner transactions) {
    this.runs = runs;
    this.trace = trace;
    this.events = events;
    this.transactions = transactions;
  }

  @Override
  public void modelStarted(String runId, int callIndex, List<Long> inputs) {
    transactions.required(
        () -> {
          requireActive(runId);
          if (inputs.isEmpty() || new HashSet<>(inputs).size() != inputs.size())
            throw new IllegalStateException("Invalid model input message sequence");
          if (trace.countInputMessages(runId, inputs) != inputs.size())
            throw new IllegalStateException("Model input message is not committed");
          Integer reserved = trace.reserveModelCall(runId, LoopLimits.defaults().maxModelCalls());
          if (reserved == null || reserved != callIndex)
            throw new IllegalStateException("Model call budget changed");
          requireOne(trace.insertModelCall(runId, callIndex, inputs));
          append(runId, "model.started", Map.of("callIndex", callIndex));
          return null;
        });
  }

  @Override
  public void modelRequest(
      String runId,
      int index,
      List<Long> inputs,
      Request request,
      List<String> history,
      String phase) {
    transactions.required(
        () -> {
          modelStarted(runId, index, inputs);
          trace.snapshot(runId, index, encode(request), history, inputs, phase);
          return null;
        });
  }

  @Override
  public long modelFinished(String runId, int callIndex, Response response) {
    return transactions.required(
        () -> {
          requireActive(runId);
          UUID id = callId(runId, callIndex);
          requireOne(
              trace.completeModelCall(
                  id,
                  response.finishReason().name(),
                  response.rawFinishReason(),
                  response.providerRequestId(),
                  response.usage() == null ? null : encode(response.usage())));
          long seq = trace.nextMessageSequence(runId);
          requireOne(
              trace.insertMessage(
                  runId, seq, "ASSISTANT", encode(content(response.content())), id, null, null));
          append(
              runId,
              "model.finished",
              Map.of(
                  "callIndex",
                  callIndex,
                  "status",
                  "SUCCEEDED",
                  "finishReason",
                  response.finishReason().name()));
          return seq;
        });
  }

  @Override
  public long modelFailed(String runId, int callIndex, String code, String detail) {
    return transactions.required(
        () -> {
          requireActive(runId);
          requireOne(trace.failModelCall(callId(runId, callIndex), code, detail));
          return append(
              runId,
              "model.finished",
              Map.of("callIndex", callIndex, "status", "FAILED", "errorCode", code));
        });
  }

  @Override
  public String toolStarted(String runId, int callIndex, ToolCall call) {
    return transactions.required(
        () -> {
          requireActive(runId);
          UUID callId = callId(runId, callIndex);
          if (trace.reserveToolCall(runId, LoopLimits.defaults().maxToolCalls()) == null)
            throw new IllegalStateException("Tool call budget changed");
          UUID executionId = UUID.randomUUID();
          requireOne(
              trace.insertStep(
                  executionId,
                  runId,
                  callId,
                  call.id(),
                  call.name(),
                  call.arguments() == null ? null : encode(call.arguments())));
          append(
              runId,
              "tool.started",
              Map.of(
                  "callIndex",
                  callIndex,
                  "toolExecutionId",
                  executionId.toString(),
                  "toolName",
                  call.name()));
          return executionId.toString();
        });
  }

  @Override
  public long toolFinished(
      String runId,
      int callIndex,
      ToolCall call,
      String executionId,
      AgentTool.ToolResult result,
      String wireJson,
      int cumulativeCodePoints,
      boolean returnedToModel) {
    return transactions.required(
        () -> {
          requireActive(runId);
          UUID callId = callId(runId, callIndex);
          requireOne(
              trace.completeStep(
                  runId,
                  executionId,
                  callId,
                  call.id(),
                  result.success() ? "SUCCEEDED" : "FAILED",
                  wireJson,
                  result.error() == null ? null : result.error().code()));
          for (Evidence evidence : result.evidence()) {
            boolean chapter = "CHAPTER".equals(evidence.resourceType());
            boolean setting = "SETTING".equals(evidence.resourceType());
            if (!chapter && !setting)
              throw new IllegalArgumentException("Unknown evidence resource type");
            requireOne(
                trace.insertEvidence(
                    runId,
                    executionId,
                    evidence.kind(),
                    chapter ? evidence.resourceId() : null,
                    chapter ? Integer.parseInt(evidence.version()) : null,
                    setting ? evidence.resourceId() : null,
                    evidence.line(),
                    evidence.codePointOffset(),
                    setting ? evidence.version() : null));
          }
          long seq = -1;
          if (returnedToModel) {
            requireOne(
                trace.updateToolText(
                    runId,
                    cumulativeCodePoints - wireJson.codePointCount(0, wireJson.length()),
                    cumulativeCodePoints));
            seq = trace.nextMessageSequence(runId);
            requireOne(
                trace.insertMessage(
                    runId, seq, "TOOL", wireJson, callId, call.id(), UUID.fromString(executionId)));
          }
          Map<String, Object> payload = new LinkedHashMap<>();
          payload.put("toolExecutionId", executionId);
          payload.put("toolName", call.name());
          payload.put("status", result.success() ? "SUCCEEDED" : "FAILED");
          payload.put("returnedToModel", returnedToModel);
          if (result.error() != null) payload.put("errorCode", result.error().code());
          append(runId, "tool.finished", payload);
          return seq;
        });
  }

  @Override
  public void toolAborted(String runId, int callIndex, ToolCall call, String reason) {
    transactions.required(
        () -> {
          Objects.requireNonNull(runs.lockRun(null, runId), "Run unavailable");
          requireOne(
              trace.insertAbortedStep(
                  runId,
                  callId(runId, callIndex),
                  call.id(),
                  call.name(),
                  call.arguments() == null ? null : encode(call.arguments()),
                  reason));
          return null;
        });
  }

  private void requireActive(String runId) {
    RunRecord state = Objects.requireNonNull(runs.lockRun(null, runId), "Run unavailable");
    if (!"RUNNING".equals(state.status()) || state.cancelRequested())
      throw new IllegalStateException("Run is no longer active");
  }

  private UUID callId(String runId, int index) {
    return Objects.requireNonNull(trace.modelCallId(runId, index), "Model call unavailable");
  }

  private long append(String runId, String type, Object payload) {
    long seq = events.nextSequence(runId);
    requireOne(events.insert(runId, seq, type, encode(payload)));
    return seq;
  }

  private static List<Map<String, Object>> content(List<ContentPart> parts) {
    List<Map<String, Object>> serialized = new ArrayList<>();
    for (ContentPart part : parts) {
      if (part instanceof TextPart text)
        serialized.add(Map.of("type", "text", "text", text.text()));
      else if (part instanceof ToolCall call)
        serialized.add(
            Map.of(
                "type",
                "tool_call",
                "id",
                call.id(),
                "name",
                call.name(),
                "arguments",
                call.arguments() == null ? Map.of() : call.arguments()));
      else if (part instanceof OpaquePart opaque)
        serialized.add(Map.of("type", "opaque", "block", opaque.providerBlock()));
      else throw new IllegalArgumentException("Unexpected response part");
    }
    return serialized;
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot serialize run record", e);
    }
  }

  private static void requireOne(int count) {
    if (count != 1) throw new IllegalStateException("Run record changed concurrently");
  }
}
