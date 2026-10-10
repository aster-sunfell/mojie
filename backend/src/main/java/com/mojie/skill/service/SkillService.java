package com.mojie.skill.service;

import static com.mojie.common.model.JsonSupport.*;

import com.mojie.common.error.*;
import com.mojie.common.pagination.*;
import com.mojie.common.transaction.TransactionRunner;
import com.mojie.skill.model.SkillModels.*;
import com.mojie.skill.repository.SkillRepository;
import com.mojie.skill.service.port.*;
import java.time.Instant;
import java.util.*;

public final class SkillService {
  private final SkillRepository repo;
  private final SkillStorage storage;
  private final SkillExecutor executor;
  private final TransactionRunner tx;
  private final PageCursor cursors;

  public SkillService(
      SkillRepository r, SkillStorage s, SkillExecutor e, TransactionRunner t, PageCursor c) {
    repo = r;
    storage = s;
    executor = e;
    tx = t;
    cursors = c;
  }

  public void recover() {
    for (var upload : repo.unfinishedUploads())
      tx.required(
          () -> {
            repo.fail(
                upload.id(),
                "SERVICE_RESTARTED",
                "Upload interrupted by service restart");
            return null;
          });
    for (var execution : repo.unfinishedExecutions()) {
      executor.stop(execution.id());
      tx.required(
          () -> {
            repo.finishExecution(
                execution.id(), "FAILED", null, "", "Service restarted", "SERVICE_RESTARTED");
            return null;
          });
    }
    for (var version : repo.allReadyVersions())
      try {
        storage.verify(version, repo.files(version.id()));
      } catch (BusinessException unavailable) {
        tx.required(
            () -> {
              repo.unavailable(version.id());
              return null;
            });
      }
  }

  public void maintain() {
    for (var artifact : repo.expiringArtifacts())
      if (tx.required(() -> repo.claimExpiration(artifact.id()))) {
        storage.deleteArtifact(artifact.id());
        tx.required(
            () -> {
              repo.finishExpiration(artifact.id());
              return null;
            });
      }
    storage.cleanup(
        repo.retainedVersions().stream()
            .map(com.mojie.skill.model.RecoveryModels.Retained::id)
            .collect(java.util.stream.Collectors.toSet()),
        repo.retainedUploads().stream()
            .map(com.mojie.skill.model.RecoveryModels.Retained::id)
            .collect(java.util.stream.Collectors.toSet()),
        repo.retainedArtifacts().stream()
            .map(com.mojie.skill.model.RecoveryModels.Retained::id)
            .collect(java.util.stream.Collectors.toSet()));
  }

  public SkillRepository repository() {
    return repo;
  }

  public SkillStorage storage() {
    return storage;
  }

  public SkillExecutor executor() {
    return executor;
  }

  public List<RunSkill> runSkills(String run) {
    return repo.runSkills(run);
  }

  public List<Artifact> runArtifacts(String run) {
    return repo.artifacts(run);
  }

  public Map<String, Object> capabilities() {
    return Map.of(
        "maxRawUploadBytes",
        20 * 1024 * 1024,
        "maxUnpackedBytes",
        100 * 1024 * 1024,
        "maxFileBytes",
        20 * 1024 * 1024,
        "maxFiles",
        500,
        "maxDepth",
        16,
        "maxEnabledSkills",
        50,
        "quotaBytes",
        1073741824L,
        "maxSkillBytes",
        65536,
        "executor",
        executor.capabilities());
  }

  public Upload importPackage(String skill, Map<String, byte[]> files) {
    if (skill != null) requireSkill(skill);
    String upload = UUID.randomUUID().toString(), version = UUID.randomUUID().toString();
    tx.required(
        () -> {
          repo.reserve(upload, skill, 100L * 1024 * 1024, version);
          return null;
        });
    try {
      tx.required(
          () -> {
            repo.validating(upload);
            return null;
          });
      Published pack = storage.publish(upload, version, files);
      tx.required(
          () -> {
            repo.publish(skill, upload, pack, encode(compatibility(pack)));
            return null;
          });
    } catch (RuntimeException e) {
      Upload state = repo.upload(upload);
      if (state != null && !state.status().equals("READY")) {
        if (!repo.hasVersion(version)) storage.discardVersion(version);
        tx.required(
            () -> {
              repo.fail(
                  upload,
                  e instanceof BusinessException b ? b.code() : "SKILL_INVALID_PACKAGE",
                  e instanceof BusinessException ? e.getMessage() : "Package publication failed");
              return null;
            });
      }
    }
    return upload(upload);
  }

  public Upload importZip(String skill, byte[] bytes) {
    return importPackage(skill, storage.unpack(bytes));
  }

  private Map<String, Object> compatibility(Published p) {
    List<String> scripts =
        p.files().stream()
            .filter(f -> !f.mime().equals("inode/directory"))
            .map(PackageFile::path)
            .filter(x -> x.startsWith("scripts/"))
            .toList();
    boolean unsupported =
        scripts.stream()
            .anyMatch(x -> !x.endsWith(".py") && !x.endsWith(".js") && !x.endsWith(".mjs"));
    var environment = executor.capabilities();
    return Map.of(
        "readable",
        true,
        "scripts",
        scripts,
        "partialExecution",
        unsupported || (!scripts.isEmpty() && !Boolean.TRUE.equals(environment.get("configured"))),
        "environment",
        environment);
  }

  public Upload upload(String id) {
    Upload u = repo.upload(id);
    if (u == null) throw missing();
    return u;
  }

  public Skill requireSkill(String id) {
    if (id == null) throw new IllegalArgumentException("skill ID required");
    UUID.fromString(id);
    Skill s = repo.skill(id, false);
    if (s == null) throw missing();
    return s;
  }

  public Version requireVersion(String id, String version) {
    if (version == null) throw new IllegalArgumentException("version ID required");
    UUID.fromString(version);
    requireSkill(id);
    Version v = repo.version(id, version);
    if (v == null) throw missing();
    if (!v.status().equals("READY"))
      throw new BusinessException("SKILL_PACKAGE_UNAVAILABLE", "Skill package unavailable");
    return v;
  }

  public Map<String, Object> detail(String id) {
    Skill s = requireSkill(id);
    Version v = s.currentVersionId() == null ? null : repo.version(id, s.currentVersionId());
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("skill", s);
    m.put("currentVersion", v);
    if (v != null) m.put("files", repo.files(v.id()));
    return m;
  }

  public Map<String, Object> versionDetail(String id, String version) {
    Version v = requireVersion(id, version);
    return Map.of("version", v, "files", repo.files(version));
  }

  public List<Skill> enabled() {
    return repo.enabled();
  }

  public Map<String, Object> list(String cursor, int limit) {
    int size = ListPaging.limit(limit);
    var pos = ListPaging.position(cursors, "skills", cursor);
    List<Skill> rows =
        repo.page(pos == null ? null : pos.time(), pos == null ? null : pos.id(), size + 1);
    return pageSkills(rows, size, "skills");
  }

  private Map<String, Object> pageSkills(List<Skill> rows, int size, String scope) {
    boolean more = rows.size() > size;
    List<Skill> items = rows.subList(0, Math.min(size, rows.size()));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", items.stream().map(s -> detail(s.id())).toList());
    result.put("hasMore", more);
    result.put(
        "nextCursor",
        more
            ? ListPaging.encode(cursors, scope, items.getLast().createdAt(), items.getLast().id())
            : null);
    return result;
  }

  public Map<String, Object> versions(String id, String cursor, int limit) {
    requireSkill(id);
    int size = ListPaging.limit(limit);
    String scope = "skill-versions:" + id;
    var pos = ListPaging.position(cursors, scope, cursor);
    List<Version> rows =
        repo.versions(id, pos == null ? null : pos.time(), pos == null ? null : pos.id(), size + 1);
    boolean more = rows.size() > size;
    var items = rows.subList(0, Math.min(size, rows.size()));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", items);
    result.put("hasMore", more);
    result.put(
        "nextCursor",
        more
            ? ListPaging.encode(cursors, scope, items.getLast().createdAt(), items.getLast().id())
            : null);
    return result;
  }

  public Skill patch(
      String id, long expected, Boolean enabled, String version, boolean deleted) {
    return tx.required(
        () -> {
          repo.lockQuota();
          Skill s = repo.skill(id, true);
          if (s == null) throw missing();
          boolean active = enabled == null ? s.enabled() : enabled;
          if (deleted) active = false;
          String v = version == null ? s.currentVersionId() : version;
          Version pack = repo.version(id, v);
          if (pack == null) throw missing();
          if (active) {
            requireVersion(id, v);
            storage.verify(pack, repo.files(v));
          }
          if (active && !s.enabled() && repo.enabled().size() >= 50)
            throw new BusinessException(
                "SKILL_UPLOAD_LIMIT_EXCEEDED", "At most 50 Skills may be enabled");
          String name = pack.metadata().path("name").asText();
          if (active
              && repo.enabled().stream()
                  .anyMatch(other -> !other.id().equals(id) && other.name().equals(name)))
            throw new BusinessException(
                "SKILL_VERSION_CONFLICT",
                "Enabled Skill name already exists; update the existing Skill or rename the"
                    + " package");
          if (repo.patch(id, expected, active, v, deleted, name) != 1)
            throw new BusinessException("VERSION_CONFLICT", "Skill version changed");
          return deleted ? s : repo.skill(id, false);
        });
  }

  public FileEntry file(String skill, String version, String fileId) {
    requireVersion(skill, version);
    return repo.files(version).stream()
        .filter(f -> f.id().equals(fileId))
        .findFirst()
        .orElseThrow(SkillService::missing);
  }

  public byte[] read(String skill, String version, String fileId) {
    return storage.read(requireVersion(skill, version), file(skill, version, fileId));
  }

  public byte[] export(String skill, String version) {
    Version v = requireVersion(skill, version);
    return storage.export(v, repo.files(version));
  }

  public Upload edit(String skill, String version, Map<String, String> edits) {
    Version v = requireVersion(skill, version);
    Map<String, byte[]> files = new LinkedHashMap<>();
    for (var f : repo.files(version)) files.put(f.relativePath(), storage.read(v, f));
    for (var e : edits.entrySet()) {
      if (!files.containsKey(e.getKey()))
        throw new BusinessException("VALIDATION_ERROR", "Only existing text files can be edited");
      if (e.getValue() == null) throw new IllegalArgumentException("Text content required");
      var entry =
          repo.files(version).stream()
              .filter(f -> f.relativePath().equals(e.getKey()))
              .findFirst()
              .orElseThrow();
      if (!entry.mime().startsWith("text/") && !entry.mime().equals("application/json"))
        throw new IllegalArgumentException("Only text files can be edited");
      byte[] data = e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8);
      files.put(e.getKey(), data);
    }
    return importPackage(skill, files);
  }

  public Artifact artifact(String id) {
    Artifact a = repo.artifact(id);
    if (a == null) throw missing();
    return a;
  }

  public byte[] downloadArtifact(String id) {
    Artifact a = artifact(id);
    if (!a.status().equals("READY") || Instant.parse(a.expiresAt()).isBefore(Instant.now()))
      throw new BusinessException("ARTIFACT_EXPIRED", "Artifact has expired");
    return storage.artifact(a);
  }

  private static BusinessException missing() {
    return new BusinessException("RESOURCE_NOT_FOUND", "Skill or file unavailable");
  }
}
