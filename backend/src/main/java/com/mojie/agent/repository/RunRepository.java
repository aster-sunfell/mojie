package com.mojie.agent.repository;

import com.mojie.agent.model.*;
import com.mojie.agent.model.RunAdmission.*;
import java.util.*;

public interface RunRepository {
  Existing findExisting(String owner, String key);

  boolean lockProject(String projectId);

  String activeSlot(String projectId);

  int occupySlot(String projectId, String runId);

  int releaseSlot(String projectId, String runId);

  RunRecord lockRun(String projectId, String runId);

  int insertSnapshot(String hash, String kind, String content);

  String insertRun(
      Request request,
      String instruction,
      String digest,
      String baseContent,
      String promptHash,
      String schemaHash,
      String modelId,
      String configJson,
      java.time.Instant queuedAt,
      UUID processId);

  int claimQueued(String projectId, String runId, UUID processId);

  int cancelQueued(String runId);

  int requestCancel(String runId);

  List<ActiveRun> overdue();

  List<ActiveRun> previousProcess(UUID processId);

  int updateTerminal(String runId, String status, String analysis, String code, String detail);

  TerminalResult existingTerminal(String runId, String status);
}
