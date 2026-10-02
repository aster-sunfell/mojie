package com.mojie.skill.service.port;

import com.mojie.skill.model.SkillModels.*;
import java.util.*;

public interface SkillStorage {
  Published publish(String uploadId, String versionId, Map<String, byte[]> input);

  byte[] read(Version version, FileEntry file);

  byte[] export(Version version, List<FileEntry> files);

  Map<String, byte[]> unpack(byte[] bytes);

  byte[] artifact(Artifact file);

  void saveArtifact(String id, byte[] bytes);

  void discardVersion(String id);

  void deleteArtifact(String id);

  void cleanup(Set<String> versions, Set<String> uploads, Set<String> artifacts);

  void verify(Version version, List<FileEntry> files);
}
