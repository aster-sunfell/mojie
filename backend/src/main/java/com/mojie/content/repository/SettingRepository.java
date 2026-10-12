package com.mojie.content.repository;

import com.mojie.content.model.*;
import java.util.*;

public interface SettingRepository {
  List<Setting> findPage(String projectId, String type, String keyword, String afterId, int size);

  Setting findById(String settingId);

  String insert(String projectId, String type, String title, String content);

  Setting lock(String settingId);

  int update(String settingId, String type, String title, String content, int expectedVersion);

  int insertRevision(
      String settingId,
      int version,
      String type,
      String title,
      String content,
      String source,
      String proposalId);

  List<SettingRevision> revisions(String settingId);

  SettingProject findVisibleProject(String settingId);

  int softDelete(String settingId);
}
