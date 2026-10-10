package com.mojie.config;

import static org.junit.jupiter.api.Assertions.*;

import com.mojie.agent.model.ModelMetadata;
import com.mojie.agent.repository.jdbc.JdbcModelMetadataRepository;
import com.mojie.support.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class SingleAuthorMigrationIT {
  @Test
  void migratingHistoricalOwnersPreservesResourcesAndMakesBothVisible() throws Exception {
    try (var database = new TestDatabase()) {
      DataSource source = database.source();
      Flyway.configure().dataSource(source).target("5").load().migrate();
      String first = historicalProject(source, "first-author"),
          second = historicalProject(source, "second-author");
      historicalRun(source, first, "first-author", UUID.randomUUID());
      historicalRun(source, second, "second-author", UUID.randomUUID());
      Flyway.configure().dataSource(source).load().migrate();
      var content = TestServices.content(source);
      assertEquals(
          Set.of(first, second),
          new HashSet<>(
              content.projects().projects(20).stream().map(p -> p.get("id").toString()).toList()));
      assertEquals(1, TestServices.query(source).list(first, null, null, 20).size());
      assertEquals(1, TestServices.query(source).list(second, null, null, 20).size());
      try (var c = source.getConnection();
          var s = c.createStatement()) {
        try (var rs =
            s.executeQuery(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema ="
                    + " current_schema() AND column_name = 'owner_id'")) {
          rs.next();
          assertEquals(0, rs.getInt(1));
        }
        try (var rs =
            s.executeQuery(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid ="
                    + " 'tb_agent_run'::regclass AND conname = 'tb_agent_run_idempotency_key'")) {
          rs.next();
          assertEquals("UNIQUE (client_request_id)", rs.getString(1));
        }
      }
    }
  }

  @Test
  void duplicateHistoricalKeysStopMigrationAndLeaveDataIntact() throws Exception {
    try (var database = new TestDatabase()) {
      DataSource source = database.source();
      Flyway.configure().dataSource(source).target("5").load().migrate();
      UUID key = UUID.randomUUID();
      historicalRun(source, historicalProject(source, "a"), "a", key);
      historicalRun(source, historicalProject(source, "b"), "b", key);
      Exception failure =
          assertThrows(
              Exception.class, () -> Flyway.configure().dataSource(source).load().migrate());
      assertTrue(failure.getMessage().contains(key.toString()));
      try (var c = source.getConnection();
          var s = c.createStatement();
          var rs = s.executeQuery("SELECT count(DISTINCT owner_id) FROM tb_agent_run")) {
        rs.next();
        assertEquals(2, rs.getInt(1));
      }
    }
  }

  @Test
  void metadataPersistsAcrossRepositoryInstancesAndKeepsProviderOriginAndModelSeparate()
      throws Exception {
    try (var database = new TestDatabase()) {
      DataSource source = database.source();
      Flyway.configure().dataSource(source).load().migrate();
      var metadata =
          new ModelMetadata(
              164000, "catalog:https://models.dev/api.json", Instant.parse("2026-10-10T00:00:00Z"));
      new JdbcModelMetadataRepository(source)
          .save("siliconflow-cn", "https://api.siliconflow.cn", "model", metadata);
      var repository = new JdbcModelMetadataRepository(source);
      assertEquals(
          metadata, repository.find("siliconflow-cn", "https://api.siliconflow.cn", "model"));
      assertNull(repository.find("siliconflow", "https://api.siliconflow.com", "model"));
      assertNull(repository.find("siliconflow-cn", "https://proxy.example", "model"));
      assertNull(repository.find("siliconflow-cn", "https://api.siliconflow.cn", "Pro/model"));
      var updated =
          new ModelMetadata(
              200000,
              "provider:https://api.siliconflow.cn/v1/models",
              metadata.fetchedAt().plusSeconds(1));
      repository.save("siliconflow-cn", "https://api.siliconflow.cn", "model", updated);
      assertEquals(
          updated, repository.find("siliconflow-cn", "https://api.siliconflow.cn", "model"));
    }
  }

  private String historicalProject(DataSource source, String owner) throws SQLException {
    try (var c = source.getConnection();
        var s =
            c.prepareStatement(
                "INSERT INTO tb_project(owner_id, name) VALUES (?, ?) RETURNING id")) {
      s.setString(1, owner);
      s.setString(2, owner);
      try (var rs = s.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private void historicalRun(DataSource source, String project, String owner, UUID key)
      throws SQLException {
    try (var c = source.getConnection();
        var s = c.createStatement()) {
      UUID chapter = UUID.randomUUID();
      s.execute(
          "INSERT INTO tb_chapter(id, project_id, chapter_order, title) VALUES ('"
              + chapter
              + "','"
              + project
              + "',1,'historical')");
      s.execute(
          "INSERT INTO tb_chapter_revision(chapter_id, version, content, source) VALUES ('"
              + chapter
              + "',1,'','CREATE')");
      String hash = "a".repeat(64);
      s.execute(
          "INSERT INTO tb_agent_prompt_snapshot(hash,kind,content) VALUES ('"
              + hash
              + "','SYSTEM_PROMPT','system') ON CONFLICT DO NOTHING");
      try (var insert =
          c.prepareStatement(
              """
INSERT INTO tb_agent_run(owner_id, project_id, target_chapter_id, instruction,
    client_request_id, request_digest, base_version, base_content, status, deadline_at,
    model_id, prompt_hash, tool_schema_hash, config_snapshot)
VALUES (?, ?::uuid, ?::uuid, 'historical', ?, ?, 1, '', 'QUEUED', now(), 'model', ?, ?, '{}')
""")) {
        insert.setString(1, owner);
        insert.setString(2, project);
        insert.setObject(3, chapter);
        insert.setObject(4, key);
        insert.setString(5, hash);
        insert.setString(6, hash);
        insert.setString(7, hash);
        insert.executeUpdate();
      }
    }
  }
}
