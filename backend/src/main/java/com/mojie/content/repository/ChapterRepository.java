package com.mojie.content.repository;

import com.mojie.content.model.*;
import java.util.*;

public interface ChapterRepository {
  List<ChapterSummary> findPage(String projectId, Integer afterOrder, String afterId, int size);

  Chapter findVisible(String chapterId);

  CreatedChapter findById(String chapterId);

  String insert(String projectId, int chapterOrder, String title);

  String insertWithContent(String projectId, int chapterOrder, String title, String content);

  int rename(String chapterId, String title);

  ChapterProject findVisibleProject(String chapterId);

  int softDelete(String chapterId);

  ChapterProject findVisibleProjectForDelete(String chapterId);

  ChapterIdentity findRevisionChapter(String chapterId);

  LockedChapter lockVisible(String chapterId);

  int updateContentIfVersionMatches(String chapterId, int expectedVersion, String content);
}
