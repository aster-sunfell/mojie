package com.mojie.agent.service;

import static com.mojie.common.model.QueryResult.fields;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.*;
import com.mojie.agent.model.RunData;
import com.mojie.agent.repository.RunReadRepository;
import com.mojie.common.error.BusinessException;
import com.mojie.common.pagination.*;
import com.mojie.common.transaction.TransactionRunner;
import java.time.OffsetDateTime;
import java.util.*;

public final class RunQueryService {
  private final RunReadRepository reads;
  private final TransactionRunner transactions;
  private final PageCursor cursors;
  private final boolean traceEnabled;
  private final JsonMapper json = JsonMapper.builder().build();

  public RunQueryService(
      RunReadRepository reads,
      TransactionRunner transactions,
      PageCursor cursors,
      boolean traceEnabled) {
    this.reads = reads;
    this.transactions = transactions;
    this.cursors = cursors;
    this.traceEnabled = traceEnabled;
  }

  public List<Map<String, Object>> list(
      String projectId, String chapterId, String status, int limit) {
    return page(projectId, chapterId, status, null, limit).items();
  }

  public Page<Map<String, Object>> page(
      String projectId, String chapterId, String status, String cursor, int limit) {
    if (limit < 1 || limit > 50) throw new IllegalArgumentException("Invalid limit");
    UUID.fromString(projectId);
    if (chapterId != null) UUID.fromString(chapterId);
    String scope = "runs:" + projectId + ":" + chapterId + ":" + status;
    String marker = cursors.decode(scope, cursor);
    String afterTime = null, afterId = null;
    if (marker != null) {
      String[] parts = marker.split("\\|", -1);
      if (parts.length != 2) throw new IllegalArgumentException("Invalid cursor");
      try {
        OffsetDateTime.parse(parts[0]);
        UUID.fromString(parts[1]);
      } catch (RuntimeException e) {
        throw new IllegalArgumentException("Invalid cursor", e);
      }
      afterTime = parts[0];
      afterId = parts[1];
    }
    List<Map<String, Object>> rows =
        fields(reads.page(projectId, chapterId, status, afterTime, afterId, limit + 1));
    if (rows.size() <= limit) return new Page<>(rows, null);
    List<Map<String, Object>> items = rows.subList(0, limit);
    Map<String, Object> last = items.getLast();
    return new Page<>(items, cursors.encode(scope, last.get("createdAt") + "|" + last.get("id")));
  }

  public Map<String, Object> detail(String runId) {
    return transactions.readSnapshot(
        () -> {
          Map<String, Object> run = scoped(runId);
          run.put("steps", fields(reads.listSteps(runId)));
          run.put("sources", fields(reads.listSources(runId)));
          return run;
        });
  }

  public Map<String, Object> trace(String runId) {
    if (!traceEnabled)
      throw new BusinessException("RESOURCE_NOT_FOUND", "Run or proposal unavailable");
    return transactions.readSnapshot(
        () -> {
          Map<String, Object> result = scoped(runId);
          Map<String, Object> selection = fields(reads.modelSelection(runId));
          result.put("modelId", selection.get("modelId"));
          JsonNode snapshot = (JsonNode) decode(selection.get("configSnapshot").toString());
          Map<String, Object> modelConfig = new LinkedHashMap<>();
          for (String name :
              List.of(
                  "apiPath",
                  "maxOutputTokens",
                  "contextWindowTokens",
                  "contextWindowSource",
                  "contextWindowFetchedAt")) {
            if (snapshot.has(name)) modelConfig.put(name, snapshot.get(name));
          }
          result.put("modelConfig", modelConfig);
          result.put("modelCalls", fields(reads.listModelCalls(runId)));
          List<Map<String, Object>> messages = fields(reads.listMessages(runId));
          for (Map<String, Object> message : messages) redactOpaqueBlocks(message.get("content"));
          result.put("messages", messages);
          result.put("steps", fields(reads.listStepsDetailed(runId)));
          result.put("sources", fields(reads.listSourcesDetailed(runId)));
          return result;
        });
  }

  public Map<String, Object> scoped(String runId) {
    Map<String, Object> value = fields(reads.scoped(runId));
    if (value == null)
      throw new BusinessException("RESOURCE_NOT_FOUND", "Run or proposal unavailable");
    return value;
  }

  public RunData load(String runId) {
    Map<String, Object> row =
        Objects.requireNonNull(fields(reads.inputs(runId)), "Run is not active");
    JsonNode config = (JsonNode) decode(row.get("configSnapshot").toString());
    int max = config.path("maxOutputTokens").asInt(16_000),
        window = config.path("contextWindowTokens").asInt(0);
    if (max <= 0 || window <= 0) throw new IllegalStateException("Invalid run model configuration");
    return new RunData(
        row.get("id").toString(),
        row.get("projectId").toString(),
        row.get("targetChapterId").toString(),
        ((Number) row.get("baseVersion")).intValue(),
        (String) row.get("baseContent"),
        (String) row.get("instruction"),
        (String) row.get("content"),
        (String) row.get("modelId"),
        max,
        window,
        OffsetDateTime.parse(row.get("deadlineAt").toString()).toInstant());
  }

  private Object decode(String value) {
    try {
      return json.readTree(value);
    } catch (Exception e) {
      throw new IllegalStateException("Stored JSON is invalid", e);
    }
  }

  private static void redactOpaqueBlocks(Object content) {
    if (!(content instanceof ArrayNode blocks)) return;
    for (JsonNode block : blocks) {
      if (!(block instanceof ObjectNode part) || !"opaque".equals(part.path("type").asText()))
        continue;
      String protocolType = part.path("block").path("type").asText("unknown");
      ObjectNode redacted = JsonNodeFactory.instance.objectNode();
      redacted.put("type", protocolType);
      redacted.put("redacted", true);
      part.set("block", redacted);
    }
  }
}
