package com.mojie.config;

import static com.mojie.common.model.JsonSupport.*;

import com.mojie.agent.model.ModelApiConfig;
import com.mojie.agent.service.ModelMetadataResolver;
import com.mojie.agent.repository.jdbc.*;
import com.mojie.agent.service.port.ModelGateway;
import com.mojie.common.pagination.PageCursor;
import com.mojie.common.transaction.TransactionRunner;
import com.mojie.conversation.repository.jdbc.JdbcConversationRepository;
import com.mojie.conversation.service.*;
import com.mojie.infrastructure.skill.*;
import com.mojie.skill.repository.jdbc.JdbcSkillRepository;
import com.mojie.skill.service.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;

@Configuration
@org.springframework.scheduling.annotation.EnableScheduling
public class ConversationConfiguration {
  @Bean
  SkillService skillService(DataSource db, TransactionRunner tx, PageCursor cursor) {
    return new SkillService(
        new JdbcSkillRepository(db),
        new FileSkillStorage(
            Path.of(System.getenv().getOrDefault("MOJIE_DATA_DIR", ".mojie-data"))),
        new HttpSkillExecutor(System.getenv("MOJIE_SKILL_EXECUTOR_URL")),
        tx,
        cursor);
  }

  public static final class SkillStartup {
    private final SkillService skills;

    public SkillStartup(SkillService s) {
      skills = s;
    }

    public void start() {
      skills.recover();
      skills.maintain();
    }
  }

  @Bean(initMethod = "start")
  SkillStartup skillStartup(SkillService skills, StartupLifecycle startup) {
    return new SkillStartup(skills);
  }

  public static final class FileMaintenance {
    private final SkillService skills;

    public FileMaintenance(SkillService s) {
      skills = s;
    }

    @org.springframework.scheduling.annotation.Scheduled(
        initialDelay = 3600000,
        fixedDelay = 3600000)
    public void clean() {
      skills.maintain();
    }
  }

  @Bean
  FileMaintenance fileMaintenance(SkillService s) {
    return new FileMaintenance(s);
  }

  @Bean
  ConversationService conversationService(
      DataSource db,
      TransactionRunner tx,
      PageCursor cursor,
      ContentConfiguration.Services content,
      AgentConfiguration.Services agent,
      SkillService skills,
      ModelApiConfig modelConfig,
      ModelGateway model,
      UUID processId,
      ModelMetadataResolver metadata) {
    return createConversationService(
        db, tx, cursor, content, agent, skills, modelConfig, model,
        processId, () -> metadata.requireMetadata().snapshot());
  }

  ConversationService createConversationService(
      DataSource db,
      TransactionRunner tx,
      PageCursor cursor,
      ContentConfiguration.Services content,
      AgentConfiguration.Services agent,
      SkillService skills,
      ModelApiConfig modelConfig,
      ModelGateway model,
      UUID processId,
      Supplier<Map<String, Object>> metadata) {
    var repository = new JdbcConversationRepository(db);
    var event = new JdbcRunEventRepository(db);
    var tools = new SkillTools(skills, repository, tx, event);
    agent
        .runs()
        .conversationRuntime(new ContextBuilder(repository, tools, model, agent.trace(), tx));
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("maxOutputTokens", 16000);
    config.put("apiPath", modelConfig.apiPath());
    config.put("maxModelCalls", 16);
    config.put("maxToolCalls", 32);
    config.put("maxToolTextCodePoints", 120000);
    config.put("runTimeoutSeconds", 300);
    config.put("queueTimeoutSeconds", 60);
    config.put("baseUrl", modelConfig.baseUrl());
    var contentTools =
        com.mojie.agent.service.tool.ContentTools.all(
            agent.toolContent(),
            System.getenv()
                .getOrDefault("MOJIE_CURSOR_KEY", "mojie-local-dev-cursor-key-change-me")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var schemas = new ArrayList<ModelGateway.ToolSpec>();
    contentTools.forEach(t -> schemas.add(t.spec()));
    tools.tools().forEach(t -> schemas.add(t.spec()));
    return new ConversationService(
        repository,
        new JdbcRunRepository(db),
        new JdbcRunTraceRepository(db),
        event,
        content.access(),
        content.chapters(),
        skills,
        tx,
        cursor,
        agent.runs(),
        modelConfig.modelId(),
        () -> {
          Map<String, Object> snapshot = new LinkedHashMap<>(config);
          snapshot.putAll(metadata.get());
          return Map.copyOf(snapshot);
        },
        encode(schemas),
        processId,
        modelConfig.configured());
  }
}
