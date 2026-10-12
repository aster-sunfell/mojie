package com.mojie.agent.repository;

import java.util.*;

public interface RunTraceRepository {
  default void snapshot(
      String run,
      int call,
      String request,
      List<String> historyIds,
      List<Long> inputs,
      String phase) {}

  long countInputMessages(String runId, List<Long> sequences);

  Integer reserveModelCall(String runId, int max);

  int insertModelCall(String runId, int callIndex, List<Long> inputs);

  UUID modelCallId(String runId, int callIndex);

  int completeModelCall(
      UUID id, String finishReason, String rawReason, String requestId, String usage);

  int failModelCall(UUID id, String code, String detail);

  int appendOutput(String runId, int callIndex, String text, long eventSeq);

  long nextMessageSequence(String runId);

  int insertMessage(
      String runId,
      long seq,
      String role,
      String body,
      UUID callId,
      String toolCallId,
      UUID executionId);

  Integer reserveToolCall(String runId, int max);

  int insertStep(
      UUID executionId,
      String runId,
      UUID callId,
      String toolCallId,
      String toolName,
      String arguments);

  int completeStep(
      String runId,
      String executionId,
      UUID callId,
      String toolCallId,
      String status,
      String result,
      String code);

  int updateToolText(String runId, int expected, int cumulative);

  int insertAbortedStep(
      String runId,
      UUID callId,
      String toolCallId,
      String toolName,
      String arguments,
      String reason);

  int insertEvidence(
      String runId,
      String executionId,
      String kind,
      String chapterId,
      Integer chapterVersion,
      String settingId,
      Integer line,
      Integer offset,
      String hash);

  int abortOpenCalls(String runId, String status);

  int abortOpenSteps(String runId, String status);

  boolean hasFinalResponse(String runId, int callIndex);

  boolean hasTargetInput(String runId, int callIndex, String chapterId, int version);

  boolean hasSettingInput(String runId, int callIndex, String settingId, int version);

  boolean hasCandidateStep(String runId);

  long openCalls(String runId);

  long openSteps(String runId);
}
