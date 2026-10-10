package com.mojie.skill.repository.jdbc;

import static com.mojie.common.model.JsonSupport.*;

import com.mojie.common.error.BusinessException;
import com.mojie.common.repository.jdbc.JdbcAccess;
import com.mojie.skill.model.SkillModels.*;
import com.mojie.skill.repository.SkillRepository;
import java.util.*;
import javax.sql.DataSource;

public final class JdbcSkillRepository implements SkillRepository {
  private final JdbcAccess db;

  public JdbcSkillRepository(DataSource source) {
    db = new JdbcAccess(source);
  }

  public List<Version> allReadyVersions() {
    return db.records(Version.class, "SELECT * FROM tb_skill_version WHERE status='READY'");
  }

  public List<com.mojie.skill.model.RecoveryModels.FailedUpload> unfinishedUploads() {
    return db.records(
        com.mojie.skill.model.RecoveryModels.FailedUpload.class,
        "SELECT id FROM tb_skill_upload WHERE status IN ('UPLOADING','VALIDATING')");
  }

  public List<com.mojie.skill.model.RecoveryModels.ExecutionHandle> unfinishedExecutions() {
    return db.records(
        com.mojie.skill.model.RecoveryModels.ExecutionHandle.class,
        "SELECT id FROM tb_skill_execution WHERE status='STARTED'");
  }

  public void unavailable(String version) {
    db.update("UPDATE tb_skill_version SET status='UNAVAILABLE' WHERE id=?::uuid", version);
    db.update(
        "UPDATE tb_skill SET enabled=false,version=version+1 WHERE current_version_id=?::uuid",
        version);
  }

  public Skill skill(String id, boolean lock) {
    return db.firstRecord(
        Skill.class,
        "SELECT * FROM tb_skill WHERE id=?::uuid AND deleted_at IS NULL"
            + (lock ? " FOR UPDATE" : ""),
        id);
  }

  public List<Skill> page(String time, String id, int size) {
    return db.records(
        Skill.class,
        "SELECT * FROM tb_skill WHERE deleted_at IS NULL AND (?::timestamptz IS NULL"
            + " OR (created_at,id)<(?::timestamptz,?::uuid)) ORDER BY created_at DESC,id DESC LIMIT"
            + " ?",
        time,
        time,
        id,
        size);
  }

  public List<Skill> enabled() {
    return db.records(
        Skill.class,
        "SELECT * FROM tb_skill WHERE enabled AND deleted_at IS NULL ORDER BY"
            + " name,id");
  }

  public Version version(String skill, String version) {
    return db.firstRecord(
        Version.class,
        "SELECT * FROM tb_skill_version WHERE skill_id=?::uuid AND id=?::uuid",
        skill,
        version);
  }

  public List<Version> versions(String skill, String time, String id, int size) {
    return db.records(
        Version.class,
        "SELECT * FROM tb_skill_version WHERE skill_id=?::uuid AND (?::timestamptz IS NULL OR"
            + " (created_at,id)<(?::timestamptz,?::uuid)) ORDER BY created_at DESC,id DESC LIMIT ?",
        skill,
        time,
        time,
        id,
        size);
  }

  public List<FileEntry> files(String version) {
    return db.records(
        FileEntry.class,
        "SELECT * FROM tb_skill_file WHERE version_id=?::uuid ORDER BY relative_path",
        version);
  }

  public Upload upload(String id) {
    return db.firstRecord(
        Upload.class, "SELECT * FROM tb_skill_upload WHERE id=?::uuid", id);
  }

  public boolean active(String run) {
    return db.first(
            "SELECT id FROM tb_agent_run WHERE id=?::uuid AND status='RUNNING' FOR UPDATE", run)
        != null;
  }

  public void failRunExecutions(String run, String code) {
    db.update(
        "UPDATE tb_skill_execution SET status='FAILED',error_code=?,finished_at=now() WHERE"
            + " run_id=?::uuid AND status='STARTED'",
        code,
        run);
  }

  public boolean hasVersion(String id) {
    return db.first("SELECT id FROM tb_skill_version WHERE id=?::uuid", id) != null;
  }

  public void lockQuota() {
    db.update("INSERT INTO tb_skill_quota(singleton) VALUES(true) ON CONFLICT DO NOTHING");
    db.first("SELECT * FROM tb_skill_quota WHERE singleton FOR UPDATE");
  }

  public void reserve(String upload, String skill, long bytes, String version) {
    lockQuota();
    if (db.update(
            "UPDATE tb_skill_quota SET reserved_bytes=reserved_bytes+? WHERE singleton AND"
                + " used_bytes+reserved_bytes+?<=1073741824",
            bytes,
            bytes)
        != 1) throw new BusinessException("SKILL_QUOTA_EXCEEDED", "Storage quota exceeded");
    db.update(
        "INSERT INTO tb_skill_upload(id,skill_id,status,reserved_bytes,version_id)"
            + " VALUES(?::uuid,?::uuid,'UPLOADING',?,?::uuid)",
        upload,
        skill,
        bytes,
        version);
  }

  public List<com.mojie.skill.model.RecoveryModels.Retained> retainedVersions() {
    return db.records(
        com.mojie.skill.model.RecoveryModels.Retained.class,
        "SELECT id FROM tb_skill_version UNION SELECT version_id AS id FROM tb_skill_upload WHERE"
            + " status IN ('UPLOADING','VALIDATING') AND version_id IS NOT NULL");
  }

  public List<com.mojie.skill.model.RecoveryModels.Retained> retainedUploads() {
    return db.records(
        com.mojie.skill.model.RecoveryModels.Retained.class,
        "SELECT id FROM tb_skill_upload WHERE status IN ('UPLOADING','VALIDATING')");
  }

  public List<com.mojie.skill.model.RecoveryModels.Retained> retainedArtifacts() {
    return db.records(
        com.mojie.skill.model.RecoveryModels.Retained.class,
        "SELECT id FROM tb_artifact WHERE status<>'EXPIRED'");
  }

  public List<Artifact> expiringArtifacts() {
    return db.records(
        Artifact.class,
        "SELECT * FROM tb_artifact WHERE (status='READY' AND expires_at<now()) OR"
            + " status='DELETING'");
  }

  public boolean claimExpiration(String id) {
    if (db.first("SELECT id FROM tb_artifact WHERE id=?::uuid FOR UPDATE", id) == null)
      return false;
    return db.update(
            "UPDATE tb_artifact a SET status='DELETING' WHERE id=?::uuid AND status IN"
                + " ('READY','DELETING') AND expires_at<now() AND NOT EXISTS(SELECT 1 FROM"
                + " tb_skill_execution e CROSS JOIN LATERAL jsonb_array_elements(e.inputs) i WHERE"
                + " i->'source'->>'artifactId'=a.id::text)",
            id)
        == 1;
  }

  public void finishExpiration(String id) {
    lockQuota();
    var a =
        db.first(
            "UPDATE tb_artifact SET status='EXPIRED' WHERE id=?::uuid AND status='DELETING'"
                + " RETURNING size_bytes",
            id);
    if (a != null)
      db.update(
          "UPDATE tb_skill_quota SET used_bytes=used_bytes-? WHERE singleton",
          a.get("sizeBytes"));
  }

  public void validating(String upload) {
    db.update(
        "UPDATE tb_skill_upload SET status='VALIDATING' WHERE id=?::uuid AND status='UPLOADING'",
        upload);
  }

  public Skill publish(
      String skill, String upload, Published pack, String compatibility) {
    lockQuota();
    if (skill == null) {
      if (db.first(
              "SELECT id FROM tb_skill WHERE name=? AND deleted_at IS NULL",
              pack.metadata().get("name"))
          != null)
        throw new BusinessException(
            "SKILL_VERSION_CONFLICT",
            "Skill name already exists; rename the package or upload a version of the existing"
                + " Skill");
      skill = UUID.randomUUID().toString();
      db.update(
          "INSERT INTO tb_skill(id,name) VALUES(?::uuid,?)",
          skill,
          pack.metadata().get("name"));
    } else if (skill(skill, true) == null)
      throw new BusinessException("RESOURCE_NOT_FOUND", "Skill unavailable");
    db.update(
        "INSERT INTO"
            + " tb_skill_version(id,skill_id,package_hash,storage_key,status,metadata,size_bytes,file_count,compatibility)"
            + " VALUES(?::uuid,?::uuid,?,?,'READY',?::jsonb,?,?,?::jsonb)",
        pack.versionId(),
        skill,
        pack.hash(),
        "skills/packages/" + pack.versionId(),
        encode(pack.metadata()),
        pack.bytes(),
        pack.files().size(),
        compatibility);
    for (var file : pack.files())
      db.update(
          "INSERT INTO tb_skill_file VALUES(?::uuid,?::uuid,?,?,?,?)",
          UUID.randomUUID().toString(),
          pack.versionId(),
          file.path(),
          file.hash(),
          file.bytes().length,
          file.mime());
    db.update(
        "UPDATE tb_skill SET current_version_id=?::uuid,name=?,version=version+1 WHERE id=?::uuid",
        pack.versionId(),
        pack.metadata().get("name"),
        skill);
    db.update(
        "UPDATE tb_skill_quota SET used_bytes=used_bytes+?,reserved_bytes=reserved_bytes-(SELECT"
            + " reserved_bytes FROM tb_skill_upload WHERE id=?::uuid) WHERE singleton",
        pack.bytes(),
        upload);
    db.update(
        "UPDATE tb_skill_upload SET"
            + " status='READY',skill_id=?::uuid,version_id=?::uuid,reserved_bytes=0 WHERE"
            + " id=?::uuid",
        skill,
        pack.versionId(),
        upload);
    return skill(skill, false);
  }

  public void fail(String upload, String code, String detail) {
    lockQuota();
    db.update(
        "UPDATE tb_skill_quota SET reserved_bytes=reserved_bytes-COALESCE((SELECT reserved_bytes"
            + " FROM tb_skill_upload WHERE id=?::uuid AND status<>'READY'),0) WHERE singleton",
        upload);
    db.update(
        "UPDATE tb_skill_upload SET status='FAILED',reserved_bytes=0,error_code=?,error_detail=?"
            + " WHERE id=?::uuid AND status<>'READY'",
        code,
        detail,
        upload);
  }

  public int patch(
      String id, long version, boolean enabled, String current, boolean deleted, String name) {
    return db.update(
        "UPDATE tb_skill SET enabled=?,current_version_id=?::uuid,deleted_at=CASE WHEN ? THEN now()"
            + " ELSE NULL END,name=?,version=version+1 WHERE id=?::uuid AND version=?",
        enabled,
        current,
        deleted,
        name,
        id,
        version);
  }

  public List<RunSkill> runSkills(String run) {
    return db.records(
        RunSkill.class,
        "SELECT"
            + " rs.skill_id,rs.version_id,rs.source,rs.loaded,rs.provided_to_model,rs.loaded_message_seq,s.name,v.metadata,v.compatibility"
            + " FROM tb_run_skill rs JOIN tb_skill s ON s.id=rs.skill_id JOIN tb_skill_version v ON"
            + " v.id=rs.version_id WHERE rs.run_id=?::uuid ORDER BY s.name",
        run);
  }

  public void bind(String run, String skill, String version, String source) {
    db.update(
        "INSERT INTO tb_run_skill(run_id,skill_id,version_id,source)"
            + " VALUES(?::uuid,?::uuid,?::uuid,?)",
        run,
        skill,
        version,
        source);
  }

  public void loaded(String run, String skill, Long message, String guidance) {
    db.update(
        "UPDATE tb_run_skill SET loaded=true,loaded_message_seq=?,guidance=?::jsonb,guidance_hash=?"
            + " WHERE run_id=?::uuid AND skill_id=?::uuid",
        message,
        guidance,
        hash(guidance),
        run,
        skill);
  }

  public void delivered(String run, List<Long> inputs) {
    db.update(
        "UPDATE tb_run_skill SET provided_to_model=true WHERE run_id=?::uuid AND loaded AND"
            + " (loaded_message_seq IS NULL OR loaded_message_seq=ANY(?))",
        run,
        inputs.toArray(Long[]::new));
  }

  public void startExecution(
      String id,
      String run,
      String tool,
      String version,
      String script,
      String args,
      String inputs,
      String environment) {
    requireActive(run);
    var artifactIds = new TreeSet<String>();
    for (var input : tree(inputs))
      if (input.path("source").path("type").asText().equals("ARTIFACT"))
        artifactIds.add(input.path("source").path("artifactId").asText());
    for (String artifact : artifactIds)
      if (db.first(
              "SELECT a.id FROM tb_artifact a JOIN tb_agent_run origin ON origin.id=a.run_id"
                  + " JOIN tb_agent_run current_run ON current_run.id=?::uuid"
                  + " WHERE a.id=?::uuid AND a.project_id=current_run.project_id"
                  + " AND origin.conversation_id=current_run.conversation_id AND a.status='READY'"
                  + " AND a.expires_at>now() FOR UPDATE OF a",
              run,
              artifact)
          == null)
        throw new BusinessException("ARTIFACT_EXPIRED", "Input artifact is no longer available");
    db.update(
        "INSERT INTO"
            + " tb_skill_execution(id,run_id,tool_execution_id,version_id,script,arguments,inputs,environment,status)"
            + " VALUES(?::uuid,?::uuid,?::uuid,?::uuid,?,?::jsonb,?::jsonb,?::jsonb,'STARTED')",
        id,
        run,
        tool,
        version,
        script,
        args,
        inputs,
        environment);
  }

  public void finishExecution(
      String id, String status, Integer exit, String stdout, String stderr, String code) {
    db.update(
        "UPDATE tb_skill_execution SET"
            + " status=?,exit_code=?,stdout=?,stderr=?,error_code=?,finished_at=now() WHERE"
            + " id=?::uuid AND status='STARTED'",
        status,
        exit,
        stdout,
        stderr,
        code,
        id);
  }

  public void executionEnvironment(String id, String environment) {
    db.update(
        "UPDATE tb_skill_execution SET environment=?::jsonb WHERE id=?::uuid AND status='STARTED'",
        environment,
        id);
  }

  public Execution execution(String id) {
    return db.firstRecord(Execution.class, "SELECT * FROM tb_skill_execution WHERE id=?::uuid", id);
  }

  private void requireActive(String run) {
    if (db.first(
            "SELECT id FROM tb_agent_run WHERE id=?::uuid AND status='RUNNING' AND NOT"
                + " cancel_requested FOR UPDATE",
            run)
        == null) throw new java.util.concurrent.CancellationException();
  }

  public void publishArtifact(
      String project,
      String run,
      String execution,
      String id,
      String name,
      String hash,
      long bytes,
      String mime) {
    requireActive(run);
    lockQuota();
    if (db.update(
            "UPDATE tb_skill_quota SET used_bytes=used_bytes+? WHERE singleton AND"
                + " used_bytes+reserved_bytes+?<=1073741824",
            bytes,
            bytes)
        != 1) throw new BusinessException("SKILL_QUOTA_EXCEEDED", "Storage quota exceeded");
    db.update(
        "INSERT INTO"
            + " tb_artifact(id,project_id,run_id,execution_id,name,storage_key,hash,size_bytes,mime)"
            + " VALUES(?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?,?)",
        id,
        project,
        run,
        execution,
        name,
        "artifacts/" + id + "/file",
        hash,
        bytes,
        mime);
  }

  public Artifact artifact(String id) {
    return db.firstRecord(
        Artifact.class, "SELECT * FROM tb_artifact WHERE id=?::uuid", id);
  }

  public List<Artifact> artifacts(String run) {
    return db.records(
        Artifact.class,
        "SELECT * FROM tb_artifact WHERE run_id=?::uuid ORDER BY created_at,id",
        run);
  }
}
