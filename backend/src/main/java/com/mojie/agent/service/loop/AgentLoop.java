package com.mojie.agent.service.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.agent.service.port.ModelGateway;
import com.mojie.agent.service.port.ModelGateway.*;
import com.mojie.agent.service.tool.AgentTool;
import com.mojie.agent.service.tool.AgentTool.ToolResult;
import com.mojie.agent.service.tool.ToolContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Explicit, framework-independent loop. The runner owns terminal database transactions. */
public final class AgentLoop {
  private final ModelGateway model;
  private final Map<String, AgentTool> tools;
  private final LoopTrace trace;
  private final LoopLimits limits;
  private final Clock clock;
  private final BooleanSupplier cancelled;
  private final ObjectMapper json = JsonMapper.builder().build();

  public AgentLoop(
      ModelGateway model,
      Collection<AgentTool> tools,
      LoopTrace trace,
      LoopLimits limits,
      Clock clock,
      BooleanSupplier cancelled) {
    this.model = Objects.requireNonNull(model);
    this.trace = Objects.requireNonNull(trace);
    this.limits = Objects.requireNonNull(limits);
    this.clock = Objects.requireNonNull(clock);
    this.cancelled = Objects.requireNonNull(cancelled);
    Map<String, AgentTool> byName = new TreeMap<>();
    for (AgentTool tool : tools)
      if (byName.putIfAbsent(tool.spec().name(), tool) != null)
        throw new IllegalArgumentException("Duplicate tool: " + tool.spec().name());
    this.tools = Map.copyOf(byName);
  }

  public LoopOutcome run(LoopInput input) {
    ToolContext context = input.context();
    context.executionClock(clock, input.deadline(), cancelled);
    String runId = context.runId();
    List<Message> messages = new ArrayList<>();
    if (input.initialMessages() == null) messages.add(Message.user(input.initialUserMessage()));
    else messages.addAll(input.initialMessages());
    List<Long> messageSeqs = new ArrayList<>(List.of(1L));
    List<String> historyIds = input.historyMessageIds();
    boolean compactedDuringLoop = false;
    int modelCalls = input.initialModelCalls(), toolCalls = 0, toolText = 0;
    try {
      while (true) {
        LoopOutcome stop = stop(input);
        if (stop != null) return stop;
        if (modelCalls >= limits.maxModelCalls())
          return limit("MODEL_CALLS", modelCalls, limits.maxModelCalls());
        List<ToolSpec> specs =
            tools.values().stream()
                .map(AgentTool::spec)
                .sorted(Comparator.comparing(ToolSpec::name))
                .toList();
        long remaining = Duration.between(clock.instant(), input.deadline()).toMillis();
        long timeout = Math.min(limits.maxRequestMillis(), remaining);
        Request request =
            new Request(
                input.systemPrompt(),
                messages,
                specs,
                input.modelId(),
                input.maxOutputTokens(),
                timeout);
        String serializedRequest = json.writeValueAsString(request);
        long inputBytes = serializedRequest.getBytes(StandardCharsets.UTF_8).length;
        if (!compactedDuringLoop
            && input.compactor() != null
            && (inputBytes > (limits.maxContextTokens() - request.maxOutputTokens() - 4096L) * 0.7
                || codePoints(serializedRequest)
                    > (limits.maxContextCodePoints() - (long) request.maxOutputTokens() * 4)
                        * 0.7)) {
          compactedDuringLoop = true;
          if (modelCalls + 2 > limits.maxModelCalls())
            return limit("MODEL_CALLS", modelCalls + 2, limits.maxModelCalls());
          LoopInput.Reduction reduction =
              input.compactor().compact(List.copyOf(messages), modelCalls);
          if (reduction != null) {
            messages = new ArrayList<>(reduction.messages());
            historyIds = reduction.historyIds();
            modelCalls = reduction.modelCalls();
            continue;
          }
        }
        long contextCodePoints =
            codePoints(serializedRequest) + (long) request.maxOutputTokens() * 4;
        if (contextCodePoints > limits.maxContextCodePoints())
          return input.initialMessages() == null
              ? limit("MODEL_CONTEXT_CHARS", contextCodePoints, limits.maxContextCodePoints())
              : new LoopOutcome.Failed(
                  "CONTEXT_TOO_LARGE", "Required context exceeds the application input limit");
        // Byte length bounds byte-level tokenizer input; reserve room for provider framing and
        // special tokens.
        long contextTokenBound =
            serializedRequest.getBytes(StandardCharsets.UTF_8).length
                + (long) request.maxOutputTokens()
                + 4_096;
        if (contextTokenBound > limits.maxContextTokens())
          return input.initialMessages() == null
              ? limit("MODEL_CONTEXT_TOKENS", contextTokenBound, limits.maxContextTokens())
              : new LoopOutcome.Failed(
                  "CONTEXT_TOO_LARGE", "Estimated input exceeds the provider context budget");

        int callIndex = ++modelCalls;
        trace.modelRequest(runId, callIndex, List.copyOf(messageSeqs), request, historyIds, "TASK");
        Response response;
        try {
          response = model.call(request);
        } catch (ModelGateway.ModelException e) {
          trace.modelFailed(runId, callIndex, e.code(), e.getMessage());
          return new LoopOutcome.Failed(e.code(), e.getMessage());
        } catch (java.util.concurrent.CancellationException e) {
          return new LoopOutcome.Cancelled();
        } catch (RuntimeException e) {
          trace.modelFailed(runId, callIndex, "MODEL_ERROR", e.getClass().getSimpleName());
          return new LoopOutcome.Failed("MODEL_ERROR", "Model request failed unexpectedly");
        }
        stop = stop(input);
        if (stop != null) return stop;
        String invalid = validate(response);
        if (invalid != null) {
          trace.modelFailed(runId, callIndex, "INVALID_MODEL_RESPONSE", invalid);
          return new LoopOutcome.Failed("INVALID_MODEL_RESPONSE", invalid);
        }
        if (response.finishReason() == FinishReason.TRUNCATED
            || response.finishReason() == FinishReason.REFUSED
            || response.finishReason() == FinishReason.OTHER) {
          String code =
              switch (response.finishReason()) {
                case TRUNCATED -> "LIMIT_EXCEEDED";
                case REFUSED -> "MODEL_REFUSED";
                case OTHER -> "INVALID_MODEL_RESPONSE";
                default -> throw new IllegalStateException("Unexpected finish reason");
              };
          trace.modelFailed(runId, callIndex, code, response.finishReason().name());
          return new LoopOutcome.Failed(code, response.finishReason().name());
        }
        context.markReadsProvided();
        long assistantSeq = trace.modelFinished(runId, callIndex, response);
        messages.add(Message.assistant(response.content()));
        messageSeqs.add(assistantSeq);
        List<ToolCall> batch = response.calls();
        if (batch.isEmpty()) {
          String analysis = response.text();
          if (analysis == null || analysis.isBlank())
            return new LoopOutcome.Failed("INVALID_MODEL_RESPONSE", "Empty final analysis");
          if (context.targetChapterId() != null && !context.targetRead())
            return new LoopOutcome.Failed(
                "INSUFFICIENT_CONTEXT", "Target chapter was not fully read");
          // A successful read is useful to this conclusion only if a later model request consumed
          // its reply.
          stop = stop(input);
          if (stop != null) return stop;
          return new LoopOutcome.Completed(
              analysis, context.candidate(), modelCalls, toolCalls, toolText);
        }
        if (modelCalls >= limits.maxModelCalls()) {
          abort(runId, callIndex, batch, "MODEL_CALLS");
          return limit("MODEL_CALLS", modelCalls, limits.maxModelCalls());
        }
        if (batch.size() > limits.maxToolCalls() - toolCalls) {
          abort(runId, callIndex, batch, "TOOL_CALLS");
          return limit("TOOL_CALLS", toolCalls + batch.size(), limits.maxToolCalls());
        }
        List<ToolReply> replies = new ArrayList<>();
        List<Long> replySeqs = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
          ToolCall call = batch.get(i);
          stop = stop(input);
          if (stop != null) {
            abort(
                runId,
                callIndex,
                batch.subList(i, batch.size()),
                stop instanceof LoopOutcome.Cancelled ? "CANCELLED" : "RUN_TIMEOUT");
            return stop;
          }
          toolCalls++;
          String executionId = trace.toolStarted(runId, callIndex, call);
          context.toolExecutionId(executionId);
          ToolResult result = execute(call, context);
          Map<String, Object> payload = wireResult(result, modelCalls, toolCalls);
          String wire = json.writeValueAsString(payload);
          // A single complete read that cannot fit is returned as an explicit tool error.
          if (result.success()
              && call.name().startsWith("read_")
              && codePoints(wire) > limits.maxToolTextCodePoints()) {
            result =
                ToolResult.error(
                    "RESULT_TOO_LARGE", "Complete result exceeds the tool text limit", false);
            payload = wireResult(result, modelCalls, toolCalls);
            wire = json.writeValueAsString(payload);
          }
          int resultSize = codePoints(wire);
          if (resultSize > limits.maxToolTextCodePoints() - toolText) {
            trace.toolFinished(runId, callIndex, call, executionId, result, wire, toolText, false);
            abort(runId, callIndex, batch.subList(i + 1, batch.size()), "TOOL_TEXT");
            return limit("TOOL_TEXT", toolText + resultSize, limits.maxToolTextCodePoints());
          }
          toolText += resultSize;
          long replySeq =
              trace.toolFinished(runId, callIndex, call, executionId, result, wire, toolText, true);
          acknowledgeRead(call, result, context);
          replies.add(new ToolReply(call.id(), executionId, payload));
          replySeqs.add(replySeq);
        }
        // All results were saved before any next model request can see them.
        messages.add(Message.tools(replies));
        messageSeqs.addAll(replySeqs);
      }
    } catch (java.util.concurrent.CancellationException e) {
      return new LoopOutcome.Cancelled();
    } catch (com.mojie.common.error.BusinessException e) {
      return new LoopOutcome.Failed(e.code(), e.getMessage());
    } catch (RuntimeException e) {
      return new LoopOutcome.Failed("PERSISTENCE_ERROR", "A run record could not be committed");
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return new LoopOutcome.Failed("PERSISTENCE_ERROR", "Cannot serialize tool result");
    }
  }

  private Map<String, Object> wireResult(ToolResult result, int modelCalls, int toolCalls) {
    Map<String, Object> payload = result.wire();
    Map<String, Object> budget = new LinkedHashMap<>();
    budget.put("modelCallsUsed", modelCalls);
    budget.put("modelCallsRemaining", limits.maxModelCalls() - modelCalls);
    budget.put("toolCallsUsed", toolCalls);
    budget.put("toolCallsRemaining", limits.maxToolCalls() - toolCalls);
    budget.put(
        "finalResponseRequired",
        modelCalls + 1 == limits.maxModelCalls() || toolCalls == limits.maxToolCalls());
    payload.put("budget", budget);
    return payload;
  }

  private ToolResult execute(ToolCall call, ToolContext context) {
    AgentTool tool = tools.get(call.name());
    if (tool == null) return ToolResult.error("UNKNOWN_TOOL", "Unknown tool", true);
    if (call.arguments() == null || !call.arguments().isObject())
      return ToolResult.error("VALIDATION_ERROR", "Arguments must be a JSON object", true);
    try {
      return Objects.requireNonNull(tool.execute(call.arguments(), context));
    } catch (IllegalArgumentException e) {
      String code =
          "INVALID_SEARCH_CURSOR".equals(e.getMessage())
              ? "INVALID_SEARCH_CURSOR"
              : "VALIDATION_ERROR";
      return ToolResult.error(code, e.getMessage(), true);
    }
  }

  private static void acknowledgeRead(ToolCall call, ToolResult result, ToolContext context) {
    if (!result.success() || !(result.data() instanceof Map<?, ?> data)) return;
    if ("read_settings".equals(call.name()) && data.get("settings") instanceof List<?> settings) {
      for (Object value : settings)
        if (value instanceof Map<?, ?> setting
            && setting.get("settingId") instanceof String id
            && setting.get("title") instanceof String title
            && setting.get("content") instanceof String content)
          context
              .readSettings()
              .put(
                  id,
                  new ToolContext.SettingInput(
                      title,
                      content,
                      com.mojie.common.model.JsonSupport.hash(title + "\n" + content)));
    }
    if (!"read_chapter".equals(call.name())) return;
    Object rawId = data.get("chapterId");
    if (!(rawId instanceof String id)) return;
    if (Objects.equals(context.targetChapterId(), id)) {
      context.markTargetRead();
      return;
    }
    if (data.get("title") instanceof String title
        && data.get("chapterOrder") instanceof Number order
        && data.get("version") instanceof Number version
        && data.get("content") instanceof String content) {
      context
          .readChapters()
          .putIfAbsent(
              id,
              new ToolContext.Chapter(id, title, order.intValue(), version.longValue(), content));
    }
  }

  private String validate(Response response) {
    if (response == null || response.finishReason() == null || response.content() == null)
      return "Missing response fields";
    for (ContentPart part : response.content()) {
      if (part instanceof ToolReply) return "Unexpected tool result in assistant response";
      if (part instanceof TextPart text && text.text() == null) return "Null text block";
      if (part instanceof OpaquePart opaque && opaque.providerBlock() == null)
        return "Null provider block";
    }
    boolean hasCalls = !response.calls().isEmpty();
    if (hasCalls != (response.finishReason() == FinishReason.TOOL_CALLS)
        && response.finishReason() != FinishReason.TRUNCATED
        && response.finishReason() != FinishReason.REFUSED
        && response.finishReason() != FinishReason.OTHER)
      return "Finish reason disagrees with tool calls";
    Set<String> ids = new HashSet<>();
    for (ToolCall call : response.calls()) {
      if (call == null
          || call.id() == null
          || call.id().isBlank()
          || call.name() == null
          || call.name().isBlank()) return "Missing tool call ID or name";
      if (!ids.add(call.id())) return "Duplicate tool call ID";
    }
    return null;
  }

  private LoopOutcome stop(LoopInput input) {
    if (cancelled.getAsBoolean()) return new LoopOutcome.Cancelled();
    if (!clock.instant().isBefore(input.deadline()))
      return new LoopOutcome.Failed("RUN_TIMEOUT", "Run deadline reached");
    return null;
  }

  private void abort(String runId, int callIndex, List<ToolCall> calls, String reason) {
    for (ToolCall call : calls) trace.toolAborted(runId, callIndex, call, reason);
  }

  private LoopOutcome.Failed limit(String kind, long used, long max) {
    return new LoopOutcome.Failed("LIMIT_EXCEEDED", kind + ": " + used + "/" + max);
  }

  private static int codePoints(String value) {
    return value.codePointCount(0, value.length());
  }
}
