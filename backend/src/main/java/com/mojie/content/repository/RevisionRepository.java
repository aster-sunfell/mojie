package com.mojie.content.repository;

import com.mojie.content.model.*;
import java.util.*;

public interface RevisionRepository {
  int insertCreate(String chapterId);

  List<RevisionSummary> findPage(String chapterId, Integer beforeVersion, int size);

  List<RevisionReview> reviewLinks(String chapterId, int version);

  ChapterRevision findVisible(String chapterId, int version);

  int insert(String chapterId, int version, String content, String source, String proposalId);
}
