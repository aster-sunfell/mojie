package com.mojie.skill.api;

import com.mojie.agent.service.RunService;
import com.mojie.common.error.BusinessException;
import com.mojie.skill.model.SkillModels.*;
import com.mojie.skill.service.SkillService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public final class SkillController {
  private final SkillService service;
  private final RunService runs;

  public SkillController(SkillService s, RunService runs) {
    service = s;
    this.runs = runs;
  }

  @GetMapping("/skills/capabilities")
  public Object capabilities() {
    return service.capabilities();
  }

  @GetMapping("/skills")
  public Object list(
      @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
    return service.list(cursor, limit);
  }

  @GetMapping("/skills/{id}")
  public Object get(@PathVariable String id) {
    return service.detail(id);
  }

  public record Patch(Long expectedVersion, Boolean enabled, String currentVersionId) {}

  @PatchMapping("/skills/{id}")
  public Object patch(@PathVariable String id, @RequestBody Patch p) {
    if (p.expectedVersion() == null) throw new IllegalArgumentException("expectedVersion required");
    return service.patch(
        id, p.expectedVersion(), p.enabled(), p.currentVersionId(), false);
  }

  @DeleteMapping("/skills/{id}")
  public Object delete(@PathVariable String id, @RequestParam long expectedVersion) {
    return service.patch(id, expectedVersion, false, null, true);
  }

  @GetMapping("/skill-uploads/{id}")
  public Upload upload(@PathVariable String id) {
    return service.upload(id);
  }

  @PostMapping(value = "/skills/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<Upload> importFiles(
      @RequestParam("files") List<MultipartFile> files,
      @RequestParam(required = false) List<String> paths) {
    return ResponseEntity.accepted().body(importFiles(null, files, paths));
  }

  @PostMapping(value = "/skills/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<Upload> update(
      @PathVariable String id,
      @RequestParam("files") List<MultipartFile> files,
      @RequestParam(required = false) List<String> paths) {
    return ResponseEntity.accepted().body(importFiles(id, files, paths));
  }

  public record Edit(String baseVersionId, Map<String, String> files) {}

  @PostMapping(value = "/skills/{id}/versions", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Upload> edit(@PathVariable String id, @RequestBody Edit e) {
    if (e.files() == null || e.files().isEmpty())
      throw new IllegalArgumentException("files required");
    return ResponseEntity.accepted()
        .body(service.edit(id, e.baseVersionId(), e.files()));
  }

  private Upload importFiles(String skill, List<MultipartFile> files, List<String> paths) {
    if (files.isEmpty() || files.size() > 500 || paths != null && paths.size() != files.size())
      throw new IllegalArgumentException("Invalid file list");
    long total = 0;
    Map<String, byte[]> entries = new LinkedHashMap<>();
    for (int i = 0; i < files.size(); i++) {
      MultipartFile f = files.get(i);
      if ((total += f.getSize()) > 20 * 1024 * 1024L)
        throw new BusinessException("SKILL_UPLOAD_LIMIT_EXCEEDED", "Upload exceeds 20 MiB");
      byte[] bytes;
      try (InputStream in = f.getInputStream()) {
        bytes = in.readNBytes(20 * 1024 * 1024 + 1);
      } catch (IOException e) {
        throw new IllegalArgumentException("Upload could not be read");
      }
      if (bytes.length > 20 * 1024 * 1024)
        throw new BusinessException("SKILL_UPLOAD_LIMIT_EXCEEDED", "Upload exceeds 20 MiB");
      String name = f.getOriginalFilename();
      if (files.size() == 1 && name != null && name.toLowerCase(Locale.ROOT).endsWith(".zip"))
        return service.importZip(skill, bytes);
      String path =
          paths != null
              ? paths.get(i)
              : files.size() == 1 && name != null && name.toLowerCase(Locale.ROOT).endsWith(".md")
                  ? "SKILL.md"
                  : name;
      if (entries.putIfAbsent(path, bytes) != null)
        throw new BusinessException("SKILL_INVALID_PACKAGE", "Duplicate upload path");
    }
    return service.importPackage(skill, entries);
  }

  @GetMapping("/skills/{id}/versions")
  public Object versions(
      @PathVariable String id,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") int limit) {
    return service.versions(id, cursor, limit);
  }

  @GetMapping("/skills/{id}/versions/{version}")
  public Object version(@PathVariable String id, @PathVariable String version) {
    return service.versionDetail(id, version);
  }

  @GetMapping("/skills/{id}/versions/{version}/export")
  public ResponseEntity<byte[]> export(@PathVariable String id, @PathVariable String version) {
    return download(service.export(id, version), "skill-" + version + ".zip");
  }

  @GetMapping("/skills/{id}/versions/{version}/files/{file}")
  public ResponseEntity<byte[]> file(
      @PathVariable String id, @PathVariable String version, @PathVariable String file) {
    var f = service.file(id, version, file);
    return download(
        service.read(id, version, file),
        f.relativePath().substring(f.relativePath().lastIndexOf('/') + 1));
  }

  @GetMapping("/artifacts/{id}")
  public Object artifact(@PathVariable String id) {
    return service.artifact(id);
  }

  @GetMapping("/artifacts/{id}/download")
  public ResponseEntity<byte[]> artifactDownload(@PathVariable String id) {
    var a = service.artifact(id);
    return download(service.downloadArtifact(id), a.name());
  }

  @GetMapping("/agent/runs/{id}/resources")
  public Object resources(@PathVariable String id) {
    runs.detail(id);
    return Map.of("skills", service.runSkills(id), "artifacts", service.runArtifacts(id));
  }

  private static ResponseEntity<byte[]> download(byte[] bytes, String name) {
    return ResponseEntity.ok()
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Security-Policy", "sandbox; default-src 'none'")
        .header(
            "Content-Disposition",
            ContentDisposition.attachment()
                .filename(name, StandardCharsets.UTF_8)
                .build()
                .toString())
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(bytes);
  }
}
