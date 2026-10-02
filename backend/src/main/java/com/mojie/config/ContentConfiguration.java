package com.mojie.config;

import com.mojie.common.pagination.PageCursor;
import com.mojie.common.transaction.TransactionRunner;
import com.mojie.content.repository.jdbc.*;
import com.mojie.content.service.*;
import com.mojie.proposal.repository.jdbc.*;
import com.mojie.proposal.service.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;

@Configuration
public class ContentConfiguration {
  public record Services(
      ProjectService projects,
      ChapterService chapters,
      SettingService settings,
      ProjectAccessService access,
      ChapterSaveCoordinator saves,
      ProposalService proposals,
      ProposalReviewService reviews) {}

  public static Services create(
      DataSource source, TransactionRunner transactions, PageCursor cursors) {
    var projects = new JdbcProjectRepository(source);
    var chapters = new JdbcChapterRepository(source);
    var revisions = new JdbcRevisionRepository(source);
    var settings = new JdbcSettingRepository(source);
    var proposals = new JdbcProposalRepository(source);
    var reviewRevisions = new JdbcProposalRevisionRepository(source);
    var access = new ProjectAccessService(projects);
    var chapterService =
        new ChapterService(projects, chapters, revisions, access, transactions, cursors);
    var reviewService = new ProposalReviewService(proposals, reviewRevisions);
    return new Services(
        new ProjectService(projects, access, transactions, cursors),
        chapterService,
        new SettingService(projects, settings, access, transactions, cursors),
        access,
        new ChapterSaveCoordinator(chapterService, reviewService, access, transactions),
        new ProposalService(proposals, chapterService, access, transactions),
        reviewService);
  }

  @Bean
  Services contentServices(DataSource source, TransactionRunner transactions, PageCursor cursors) {
    return create(source, transactions, cursors);
  }

  @Bean
  ProjectService projectService(Services services) {
    return services.projects();
  }

  @Bean
  ChapterService chapterService(Services services) {
    return services.chapters();
  }

  @Bean
  SettingService settingService(Services services) {
    return services.settings();
  }

  @Bean
  ChapterSaveCoordinator chapterSaveCoordinator(Services services) {
    return services.saves();
  }

  @Bean
  ProposalService proposalService(Services services) {
    return services.proposals();
  }
}
