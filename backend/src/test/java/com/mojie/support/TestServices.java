package com.mojie.support;

import com.mojie.agent.repository.jdbc.*;
import com.mojie.agent.service.*;
import com.mojie.agent.service.tool.ToolContentService;
import com.mojie.common.pagination.PageCursor;
import com.mojie.common.transaction.TransactionRunner;
import com.mojie.config.ContentConfiguration;
import com.mojie.content.repository.jdbc.JdbcContentReadRepository;
import com.mojie.content.service.ContentReadService;
import com.mojie.infrastructure.transaction.SpringTransactionRunner;
import com.mojie.proposal.repository.jdbc.JdbcProposalRepository;
import com.mojie.proposal.service.ProposalService;
import java.time.Clock;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

public final class TestServices {
  private TestServices() {}

  public static TransactionRunner transactions(DataSource source) {
    return new SpringTransactionRunner(new JdbcTransactionManager(source));
  }

  public static ContentConfiguration.Services content(DataSource source) {
    return ContentConfiguration.create(source, transactions(source), PageCursor.configured());
  }

  public static RunAdmissionService admission(DataSource source, Clock clock, UUID processId) {
    var content = content(source);
    return new RunAdmissionService(
        new JdbcRunRepository(source),
        new JdbcRunTraceRepository(source),
        new JdbcRunEventRepository(source),
        content.chapters(),
        content.access(),
        transactions(source),
        clock,
        processId);
  }

  public static RunCompletionService completion(DataSource source, Clock clock) {
    return new RunCompletionService(
        new JdbcRunRepository(source),
        new JdbcRunTraceRepository(source),
        new JdbcRunEventRepository(source),
        new JdbcProposalRepository(source),
        transactions(source),
        clock);
  }

  public static RunLifecycleService lifecycle(DataSource source, RunCompletionService completion) {
    return new RunLifecycleService(
        new JdbcRunRepository(source),
        new JdbcRunEventRepository(source),
        completion,
        transactions(source));
  }

  public static RunTraceService trace(DataSource source) {
    return new RunTraceService(
        new JdbcRunRepository(source),
        new JdbcRunTraceRepository(source),
        new JdbcRunEventRepository(source),
        transactions(source));
  }

  public static ToolContentService toolContent(DataSource source) {
    return new ToolContentService(
        new ContentReadService(new JdbcContentReadRepository(source), transactions(source)),
        new JdbcChapterViewRepository(source),
        transactions(source));
  }

  public static RunQueryService query(DataSource source) {
    return new RunQueryService(
        new JdbcRunReadRepository(source), transactions(source), PageCursor.configured(), true);
  }

  public static ProposalService proposals(DataSource source) {
    return content(source).proposals();
  }

  public static RunEventQueryService events(DataSource source) {
    return new RunEventQueryService(query(source), new JdbcRunEventRepository(source));
  }
}
