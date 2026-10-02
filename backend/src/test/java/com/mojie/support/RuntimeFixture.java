package com.mojie.support;

import com.mojie.agent.model.CancelResult;
import com.mojie.agent.model.ModelApiConfig;
import com.mojie.agent.model.RunAdmission.*;
import com.mojie.agent.service.port.ModelGateway;
import com.mojie.common.pagination.PageCursor;
import com.mojie.config.AgentConfiguration;
import java.time.Clock;
import java.util.UUID;
import javax.sql.DataSource;

public final class RuntimeFixture implements AutoCloseable {
  private final AgentConfiguration.Services services;
  private final UUID processId = UUID.randomUUID();

  public RuntimeFixture(
      DataSource source,
      ModelGateway model,
      Clock clock,
      String prompt,
      String modelId,
      int output,
      byte[] key) {
    this(source, model, clock, prompt, modelId, output, key, ModelApiConfig.MESSAGES);
  }

  public RuntimeFixture(
      DataSource source,
      ModelGateway model,
      Clock clock,
      String prompt,
      String modelId,
      int output,
      byte[] key,
      String path) {
    this(source, model, clock, prompt, modelId, output, key, path, 1_000_000);
  }

  public RuntimeFixture(
      DataSource source,
      ModelGateway model,
      Clock clock,
      String prompt,
      String modelId,
      int output,
      byte[] key,
      String path,
      int window) {
    services =
        AgentConfiguration.create(
            source,
            TestServices.content(source),
            TestServices.transactions(source),
            PageCursor.configured(),
            model,
            clock,
            processId,
            prompt,
            modelId,
            output,
            key,
            path,
            window,
            true,
            true);
  }

  public void start() {
    services.startup().start();
  }

  public Receipt submit(Request request) {
    return services.runs().submit(request);
  }

  public CancelResult cancel(String project, String run) {
    return services.runs().cancelProjectRun(project, run);
  }

  public int occupiedCapacity() {
    return services.executor().occupiedCapacity();
  }

  public int runningThreads() {
    return services.executor().runningThreads();
  }

  public UUID processInstanceId() {
    return processId;
  }

  public boolean healthy() {
    return services.executor().healthy();
  }

  public void close() {
    services.sweeper().close();
    services.executor().close();
  }
}
