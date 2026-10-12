package com.mojie.content.repository;

import com.mojie.common.cancellation.QueryCancellation;
import com.mojie.content.model.ChapterVersionRow;
import com.mojie.content.model.ReadContent.*;
import java.util.List;

public interface ContentReadRepository {
  ProjectMetadata project(String projectId, long timeoutMillis, QueryCancellation context);

  List<ChapterMetadata> chapters(String projectId, long timeoutMillis, QueryCancellation context);

  ChapterText revision(String projectId, String chapterId, long version, long timeoutMillis);

  List<ChapterText> revisions(
      String projectId,
      List<ChapterVersion> chapters,
      long timeoutMillis,
      QueryCancellation context);

  List<ChapterVersionRow> versions(String projectId, long timeoutMillis, QueryCancellation context);

  List<SettingText> settings(String projectId, long timeoutMillis, QueryCancellation context);
}
