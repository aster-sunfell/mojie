package com.mojie.content.repository;

import com.mojie.content.model.*;
import java.util.*;

public interface ChapterRepository {
  List<ChapterSummary> findPage(String projectId, Integer afterOrder, String afterId, int size);

  Chapter findOwned(String owner, String chapterId);

  CreatedChapter findById(String chapterId);

  String insert(String projectId, int chapterOrder, String title);

  int rename(String chapterId, String title);

  ChapterProject findOwnedProject(String owner, String chapterId);

  int softDelete(String chapterId);

  ChapterProject findOwnedProjectForDelete(String owner, String chapterId);

  ChapterIdentity findRevisionChapter(String owner, String chapterId);

  LockedChapter lockOwned(String owner, String chapterId);

  int updateContentIfVersionMatches(String chapterId, int expectedVersion, String content);
}
