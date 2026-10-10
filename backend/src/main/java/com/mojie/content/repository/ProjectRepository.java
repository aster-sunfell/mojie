package com.mojie.content.repository;

import com.mojie.content.model.*;
import java.util.*;

public interface ProjectRepository {
  List<ProjectSummary> findPage(String keyword, String beforeTime, String beforeId, int size);

  Project findVisible(String projectId);

  Project findById(String projectId);

  String insert(String title);

  int rename(String projectId, String title);

  int softDelete(String projectId);

  ProjectIdentity findVisibleId(String projectId);

  ChapterOrder allocateChapterOrder(String projectId);

  ProjectLock lockVisible(String projectId);

  boolean hasProjectProposals(String projectId);

  boolean hasChapterProposals(String chapterId);
}
