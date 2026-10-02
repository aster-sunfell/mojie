package com.mojie.content.repository;

import com.mojie.content.model.*;
import java.util.*;

public interface SettingRepository {
  List<Setting> findPage(String projectId, String type, String keyword, String afterId, int size);

  Setting findById(String settingId);

  String insert(String projectId, String type, String title, String content);

  int update(String settingId, String type, String title, String content);

  SettingProject findOwnedProject(String owner, String settingId);

  int softDelete(String settingId);
}
