package com.mojie.agent.service;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.agent.model.RunAdmission.*;
import com.mojie.agent.repository.*;
import com.mojie.agent.service.loop.LoopInput;
import com.mojie.common.error.*;
import com.mojie.common.transaction.TransactionRunner;
import com.mojie.content.service.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;

public final class RunAdmissionService {
  private final RunRepository runs;
  private final RunTraceRepository trace;
  private final RunEventRepository events;
  private final ChapterService chapters;
  private final ProjectAccessService access;
  private final TransactionRunner transactions;
  private final Clock clock;
  private final UUID processId;
  private final JsonMapper json = JsonMapper.builder().build();

  public RunAdmissionService(
      RunRepository runs,
      RunTraceRepository trace,
      RunEventRepository events,
      ChapterService chapters,
      ProjectAccessService access,
      TransactionRunner transactions,
      Clock clock,
      UUID processId) {
    this.runs = runs;
    this.trace = trace;
    this.events = events;
    this.chapters = chapters;
    this.access = access;
    this.transactions = transactions;
    this.clock = clock;
    this.processId = processId;
  }

  public Optional<Receipt> findExisting(Request request) {
    Prepared prepared = prepare(request);
    return transactions.required(() -> existing(request, prepared.digest()));
  }

  private Optional<Receipt> existing(Request request, String digest) {
    Existing found = runs.findExisting(request.ownerId(), request.clientRequestId());
    if (found == null) return Optional.empty();
    if (!digest.equals(found.digest()))
      throw new BusinessException(
          "IDEMPOTENCY_CONFLICT", "Request key belongs to different content");
    return Optional.of(new Receipt(found.runId(), found.status(), false));
  }

  public Receipt create(Request request, Config config) {
    Prepared prepared = prepare(request);
    Objects.requireNonNull(config);
    if (config.systemPrompt() == null
        || config.systemPrompt().isBlank()
        || config.toolSchemaJson() == null
        || config.toolSchemaJson().isBlank()
        || config.modelId() == null
        || config.modelId().isBlank())
      throw new BusinessException("MODEL_ERROR", "Model configuration is incomplete");
    try {
      return transactions.requiresNew(
          () -> {
            Optional<Receipt> existing = existing(request, prepared.digest());
            if (existing.isPresent()) return existing.get();
            access.lock(request.ownerId(), request.projectId(), false);
            existing = existing(request, prepared.digest());
            if (existing.isPresent()) return existing.get();
            if (runs.activeSlot(request.projectId()) != null)
              throw new BusinessException("RESOURCE_BUSY", "Project already has an active run");
            Map<String, Object> chapter =
                chapters.lock(request.ownerId(), request.targetChapterId());
            if (!UUID.fromString(request.projectId()).toString().equals(chapter.get("projectId")))
              throw new BusinessException("RESOURCE_NOT_FOUND", "Chapter unavailable");
            if (((Number) chapter.get("version")).longValue() != request.expectedVersion())
              throw new BusinessException("VERSION_CONFLICT", "Chapter version changed");
            String promptHash = snapshot("SYSTEM_PROMPT", config.systemPrompt());
            String schemaHash = snapshot("TOOL_SCHEMA", config.toolSchemaJson());
            String runId =
                runs.insertRun(
                    request,
                    prepared.instruction(),
                    prepared.digest(),
                    (String) chapter.get("content"),
                    promptHash,
                    schemaHash,
                    config.modelId(),
                    encode(config.publicModelConfig()),
                    clock.instant(),
                    processId);
            trace.insertMessage(
                runId,
                1,
                "USER",
                encode(
                    List.of(
                        Map.of(
                            "type",
                            "text",
                            "text",
                            LoopInput.initialUserMessage(
                                request.targetChapterId(),
                                request.expectedVersion(),
                                prepared.instruction())))),
                null,
                null,
                null);
            long seq = events.nextSequence(runId);
            events.insert(runId, seq, "run.queued", "{\"status\":\"QUEUED\"}");
            if (runs.occupySlot(request.projectId(), runId) != 1)
              throw new BusinessException("RESOURCE_BUSY", "Project already has an active run");
            return new Receipt(runId, "QUEUED", true);
          });
    } catch (StorageException e) {
      if (!e.hasSqlState("23505")) throw e;
      // The failed write is rolled back before reading a concurrent winner in a fresh transaction.
      return transactions.requiresNew(
          () -> existing(request, prepared.digest()).orElseThrow(() -> e));
    }
  }

  private String snapshot(String kind, String content) {
    String hash = sha256(kind + "\n" + content);
    runs.insertSnapshot(hash, kind, content);
    return hash;
  }

  private record Prepared(String instruction, String digest) {}

  private static Prepared prepare(Request request) {
    Objects.requireNonNull(request);
    if (request.ownerId() == null || request.ownerId().isBlank()) throw validation("ownerId");
    try {
      UUID.fromString(request.projectId());
      UUID.fromString(request.targetChapterId());
      UUID.fromString(request.clientRequestId());
    } catch (RuntimeException e) {
      throw validation("UUID");
    }
    if (request.expectedVersion() == null
        || request.expectedVersion() <= 0
        || request.expectedVersion() > Integer.MAX_VALUE) throw validation("expectedVersion");
    if (request.instruction() == null) throw validation("instruction");
    String instruction = request.instruction().replace("\r\n", "\n").replace('\r', '\n');
    if (instruction.isBlank() || instruction.codePointCount(0, instruction.length()) > 2000)
      throw validation("instruction");
    return new Prepared(
        instruction,
        sha256(
            request.projectId()
                + "\n"
                + request.targetChapterId()
                + "\n"
                + request.expectedVersion()
                + "\n"
                + instruction));
  }

  private static BusinessException validation(String field) {
    return new BusinessException("VALIDATION_ERROR", "Invalid " + field);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot serialize run configuration", e);
    }
  }
}
