package com.mojie.skill.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

public final class SkillModels {
  private SkillModels() {}

  public record Skill(
      String id,
      String ownerId,
      String name,
      boolean enabled,
      String currentVersionId,
      String deletedAt,
      long version,
      String createdAt) {}

  public record Version(
      String id,
      String skillId,
      String packageHash,
      String storageKey,
      String status,
      JsonNode metadata,
      long sizeBytes,
      int fileCount,
      JsonNode compatibility,
      String createdAt) {}

  public record FileEntry(
      String id, String versionId, String relativePath, String hash, long sizeBytes, String mime) {}

  public record Upload(
      String id,
      String ownerId,
      String skillId,
      String versionId,
      String status,
      long reservedBytes,
      String errorCode,
      String errorDetail,
      String createdAt) {}

  public record PackageFile(String path, byte[] bytes, String hash, String mime) {}

  public record Published(
      String versionId,
      String hash,
      long bytes,
      Map<String, Object> metadata,
      List<PackageFile> files) {}

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public record RunSkill(
      String skillId,
      String versionId,
      String source,
      boolean loaded,
      boolean providedToModel,
      Long loadedMessageSeq,
      String name,
      JsonNode metadata,
      JsonNode compatibility) {}

  public record Artifact(
      String id,
      String ownerId,
      String projectId,
      String runId,
      String executionId,
      String name,
      String storageKey,
      String hash,
      long sizeBytes,
      String mime,
      String expiresAt,
      String status,
      String createdAt) {}

  public record Execution(
      String id,
      String runId,
      String toolExecutionId,
      String versionId,
      String script,
      JsonNode arguments,
      JsonNode inputs,
      JsonNode environment,
      String status,
      Integer exitCode,
      String stdout,
      String stderr,
      String errorCode,
      String startedAt,
      String finishedAt) {}
}
