package com.mojie.skill.repository;

import com.mojie.skill.model.SkillModels.*;
import java.util.List;

public interface SkillRepository {
  List<Version> allReadyVersions();

  List<com.mojie.skill.model.RecoveryModels.FailedUpload> unfinishedUploads();

  List<com.mojie.skill.model.RecoveryModels.ExecutionHandle> unfinishedExecutions();

  void unavailable(String version);

  boolean hasVersion(String id);

  void lockQuota();

  Skill skill(String id, boolean lock);

  List<Skill> page(String afterTime, String afterId, int size);

  List<Skill> enabled();

  Version version(String skill, String version);

  List<Version> versions(String skill, String afterTime, String afterId, int size);

  List<FileEntry> files(String version);

  Upload upload(String id);

  void reserve(String upload, String skill, long bytes, String version);

  List<com.mojie.skill.model.RecoveryModels.Retained> retainedVersions();

  List<com.mojie.skill.model.RecoveryModels.Retained> retainedUploads();

  List<com.mojie.skill.model.RecoveryModels.Retained> retainedArtifacts();

  List<Artifact> expiringArtifacts();

  boolean claimExpiration(String id);

  void finishExpiration(String id);

  void validating(String upload);

  Skill publish(String skill, String upload, Published pack, String compatibility);

  void fail(String upload, String code, String detail);

  int patch(
      String id, long expected, boolean enabled, String version, boolean deleted, String name);

  List<RunSkill> runSkills(String run);

  void bind(String run, String skill, String version, String source);

  void finishExecution(
      String id, String status, Integer exit, String stdout, String stderr, String code);

  Execution execution(String id);

  Artifact artifact(String id);

  List<Artifact> artifacts(String run);
}
