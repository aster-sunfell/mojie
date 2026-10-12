package com.mojie.agent.service.tool;

import static com.mojie.common.model.JsonSupport.tree;
import static org.junit.jupiter.api.Assertions.*;

import com.mojie.support.TestDatabase;
import com.mojie.support.TestServices;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class ProjectToolsIT {
  @Test
  void projectReadsRespectVisibilityCancellationAndDatabaseDeadline() throws Exception {
    try (TestDatabase database = new TestDatabase()) {
      var source = database.source();
      Flyway.configure().dataSource(source).load().migrate();
      var content = TestServices.content(source);
      String project = (String) content.projects().createProject("空作品").get("id");
      String other = (String) content.projects().createProject("另一本作品").get("id");
      var reader = TestServices.toolContent(source);
      var read = ContentTools.all(reader, "test-tool-key-123456".getBytes()).stream()
          .filter(t -> t.spec().name().equals("read_project")).findFirst().orElseThrow();
      assertEquals(new ToolContentReader.ProjectMetadata(project, "空作品"), reader.project(project));
      assertEquals(new ToolContentReader.ProjectMetadata(other, "另一本作品"), reader.project(other));
      assertNull(reader.project(UUID.randomUUID().toString()));
      var context = context(project);
      assertEquals(Map.of("projectId", project, "title", "空作品"),
          read.execute(tree("{}"), context).data());
      content.projects().deleteProject(project);
      assertNull(reader.project(project));
      var missing = read.execute(tree("{}"), context(project));
      assertEquals("RESOURCE_NOT_FOUND", missing.error().code());
      assertNull(missing.data());

      context = context(other);
      try (var blocker = source.getConnection(); var lock = blocker.createStatement()) {
        blocker.setAutoCommit(false);
        lock.execute("LOCK TABLE tb_project IN ACCESS EXCLUSIVE MODE");
        assertThrows(ToolContentReader.SearchTimeoutException.class,
            () -> reader.project(other, 100, null));
        context.executionClock(Clock.systemUTC(), Instant.now().plusMillis(100), () -> false);
        var result = read.execute(tree("{}"), context);
        assertEquals("READ_TIMEOUT", result.error().code());
        assertFalse(result.error().retryableByModel());
        assertNull(context.readProject());
        blocker.rollback();
      }
      context.executionClock(Clock.systemUTC(), Instant.now().plusSeconds(5), () -> false);
      assertTrue(read.execute(tree("{}"), context).success());
      var cancelled = context(other);
      cancelled.executionClock(Clock.systemUTC(), Instant.now().plusSeconds(5), () -> true);
      assertThrows(java.util.concurrent.CancellationException.class,
          () -> reader.project(other, 5_000, cancelled));
      assertNull(cancelled.readProject());
    }
  }

  private static ToolContext context(String project) {
    return new ToolContext(UUID.randomUUID().toString(), project, null, 0, null);
  }
}
