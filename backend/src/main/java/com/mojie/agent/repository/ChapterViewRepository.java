package com.mojie.agent.repository;

import com.mojie.agent.model.ChapterView;
import com.mojie.common.cancellation.QueryCancellation;
import com.mojie.content.model.ChapterVersionRow;
import java.util.*;

public interface ChapterViewRepository {
  String lockRun(String runId, String projectId, long timeoutMillis, QueryCancellation context);

  ChapterView find(String runId, QueryCancellation context);

  void insert(
      String runId,
      UUID viewId,
      List<ChapterVersionRow> chapters,
      Map<String, Long> versions,
      QueryCancellation context);
}
