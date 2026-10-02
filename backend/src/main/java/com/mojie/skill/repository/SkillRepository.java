package com.mojie.skill.repository;

import com.mojie.skill.model.SkillModels.*;
import java.util.List;

public interface SkillRepository {
  List<Version> allReadyVersions();

  List<com.mojie.skill.model.RecoveryModels.FailedUpload> unfinishedUploads();

  List<com.mojie.skill.model.RecoveryModels.ExecutionHandle> unfinishedExecutions();

  void unavailable(String version);

  boolean active(String run);

  void failRunExecutions(String run, String code);

  boolean hasVersion(String id);

  void lockOwner(String owner);

  Skill skill(String owner, String id, boolean lock);

  List<Skill> page(String owner, String afterTime, String afterId, int size);

  List<Skill> enabled(String owner);

  Version version(String skill, String version);

  List<Version> versions(String skill, String afterTime, String afterId, int size);

  List<FileEntry> files(String version);

  Upload upload(String owner, String id);

  void reserve(String owner, String upload, String skill, long bytes, String version);

  List<com.mojie.skill.model.RecoveryModels.Retained> retainedVersions();

  List<com.mojie.skill.model.RecoveryModels.Retained> retainedUploads();

  List<com.mojie.skill.model.RecoveryModels.Retained> retainedArtifacts();

  List<Artifact> expiringArtifacts();

  boolean claimExpiration(String id);

  void finishExpiration(String owner, String id);

  void validating(String upload);

  Skill publish(String owner, String skill, String upload, Published pack, String compatibility);

  void fail(String owner, String upload, String code, String detail);

  int patch(
      String id, long expected, boolean enabled, String version, boolean deleted, String name);

  List<RunSkill> runSkills(String run);

  void bind(String run, String skill, String version, String source);

  void loaded(String run, String skill, Long message, String guidance);

  void delivered(String run, List<Long> inputs);

  void startExecution(
      String id,
      String run,
      String tool,
      String version,
      String script,
      String args,
      String inputs,
      String environment);

  void finishExecution(
      String id, String status, Integer exit, String stdout, String stderr, String code);

  void executionEnvironment(String id, String environment);

  Execution execution(String id);

  void publishArtifact(
      String owner,
      String project,
      String run,
      String execution,
      String id,
      String name,
      String hash,
      long bytes,
      String mime);

  Artifact artifact(String owner, String id);

  List<Artifact> artifacts(String run);
}
