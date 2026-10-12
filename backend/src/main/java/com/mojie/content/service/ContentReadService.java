package com.mojie.content.service;

import com.mojie.common.cancellation.QueryCancellation;
import com.mojie.common.transaction.TransactionRunner;
import com.mojie.content.model.ChapterVersionRow;
import com.mojie.content.model.ReadContent.*;
import com.mojie.content.repository.ContentReadRepository;
import java.util.*;

public final class ContentReadService {
  private final ContentReadRepository content;
  private final TransactionRunner transactions;

  public ContentReadService(ContentReadRepository content, TransactionRunner transactions) {
    this.content = content;
    this.transactions = transactions;
  }

  public ProjectMetadata project(String project, long timeout, QueryCancellation cancellation) {
    return transactions.requiresNew(() -> content.project(project, timeout, cancellation));
  }

  public List<ChapterMetadata> chapters(
      String project, long timeout, QueryCancellation cancellation) {
    return transactions.requiresNew(() -> content.chapters(project, timeout, cancellation));
  }

  public ChapterText revision(String project, String id, long version, long timeout) {
    return transactions.requiresNew(() -> content.revision(project, id, version, timeout));
  }

  public List<ChapterText> revisions(
      String project, List<ChapterVersion> chapters, long timeout, QueryCancellation cancellation) {
    return transactions.requiresNew(
        () -> content.revisions(project, chapters, timeout, cancellation));
  }

  public List<SettingText> settings(String project, long timeout, QueryCancellation cancellation) {
    return transactions.requiresNew(() -> content.settings(project, timeout, cancellation));
  }

  public List<ChapterVersionRow> versions(
      String project, long timeout, QueryCancellation cancellation) {
    return transactions.readSnapshot(() -> content.versions(project, timeout, cancellation));
  }
}
