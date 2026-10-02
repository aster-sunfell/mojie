package com.mojie.content.repository;

import com.mojie.content.model.*;
import java.util.*;

public interface ProjectRepository {
  List<ProjectSummary> findPage(
      String owner, String keyword, String beforeTime, String beforeId, int size);

  Project findOwned(String owner, String projectId);

  Project findById(String projectId);

  String insert(String owner, String title);

  int rename(String projectId, String title);

  int softDelete(String projectId);

  ProjectIdentity findOwnedId(String owner, String projectId);

  ChapterOrder allocateChapterOrder(String projectId);

  ProjectLock lockOwned(String owner, String projectId);

  boolean hasProjectProposals(String projectId);

  boolean hasChapterProposals(String chapterId);
}
