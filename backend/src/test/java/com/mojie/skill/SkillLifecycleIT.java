package com.mojie.skill;

import static org.junit.jupiter.api.Assertions.*;

import com.mojie.common.error.BusinessException;
import com.mojie.common.pagination.PageCursor;
import com.mojie.common.repository.jdbc.JdbcAccess;
import com.mojie.infrastructure.skill.*;
import com.mojie.skill.repository.jdbc.JdbcSkillRepository;
import com.mojie.skill.service.SkillService;
import com.mojie.support.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SkillLifecycleIT {
  @TempDir Path data;

  @Test
  void concurrentReservationsRespectQuotaAndReleaseExactlyOnce() throws Exception {
    try (TestDatabase database = new TestDatabase()) {
      var source = database.source();
      Flyway.configure().dataSource(source).load().migrate();
      var tx = TestServices.transactions(source);
      var repository = new JdbcSkillRepository(source);
      var db = new JdbcAccess(source);
      db.update(
          "UPDATE tb_skill_quota SET used_bytes=? WHERE singleton",
          1073741824L - 100 * 1024 * 1024L);
      var start = new CountDownLatch(1);
      List<String> accepted = new ArrayList<>();
      try (var pool = Executors.newFixedThreadPool(4)) {
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++)
          results.add(
              pool.submit(
                  () -> {
                    start.await();
                    String id = UUID.randomUUID().toString();
                    try {
                      tx.required(
                          () -> {
                            repository.reserve(
                                id,
                                null,
                                60 * 1024 * 1024L,
                                UUID.randomUUID().toString());
                            return null;
                          });
                      return id;
                    } catch (BusinessException error) {
                      assertEquals("SKILL_QUOTA_EXCEEDED", error.code());
                      return null;
                    }
                  }));
        start.countDown();
        for (var future : results) {
          String id = future.get(10, TimeUnit.SECONDS);
          if (id != null) accepted.add(id);
        }
      }
      assertEquals(1, accepted.size());
      assertEquals(
          60 * 1024 * 1024L,
          ((Number)
                  db.first("SELECT reserved_bytes AS n FROM tb_skill_quota WHERE singleton")
                      .get("n"))
              .longValue());
      for (int i = 0; i < 2; i++)
        tx.required(
            () -> {
              repository.fail(accepted.getFirst(), "TEST_FAILED", "failed upload");
              return null;
            });
      assertEquals(
          0L,
          ((Number)
                  db.first("SELECT reserved_bytes AS n FROM tb_skill_quota WHERE singleton")
                      .get("n"))
              .longValue());
      assertEquals(
          1L,
          ((Number) db.first("SELECT count(*) AS n FROM tb_skill_upload").get("n")).longValue());
    }
  }

  @Test
  void failedUploadsReleaseQuotaAndRecoveryDoesNotDeleteActiveStaging() throws Exception {
    try (TestDatabase database = new TestDatabase()) {
      var source = database.source();
      Flyway.configure().dataSource(source).load().migrate();
      var tx = TestServices.transactions(source);
      var repository = new JdbcSkillRepository(source);
      var skills =
          new SkillService(
              repository,
              new FileSkillStorage(data),
              new HttpSkillExecutor(null),
              tx,
              PageCursor.configured());
      var db = new JdbcAccess(source);
      byte[] body =
          "---\nname: lifecycle\ndescription: 状态与配额\n---\n指导\n".getBytes(StandardCharsets.UTF_8);
      var upload = skills.importPackage(null, Map.of("SKILL.md", body));
      assertEquals("READY", upload.status());
      var skill = skills.requireSkill(upload.skillId());
      skills.patch(skill.id(), skill.version(), true, null, false);
      var duplicate = skills.importPackage(null, Map.of("SKILL.md", body));
      assertEquals("FAILED", duplicate.status());
      assertEquals("SKILL_VERSION_CONFLICT", duplicate.errorCode());
      assertEquals(
          0L,
          ((Number)
                  db.first("SELECT reserved_bytes AS n FROM tb_skill_quota WHERE singleton")
                      .get("n"))
              .longValue());
      assertFalse(Files.exists(data.resolve("skills/packages/" + duplicate.versionId())));
      String active = UUID.randomUUID().toString(),
          version = UUID.randomUUID().toString(),
          orphan = UUID.randomUUID().toString();
      tx.required(
          () -> {
            repository.reserve(active, null, 100 * 1024 * 1024, version);
            return null;
          });
      Path activeStage = data.resolve("skills/staging/" + active),
          oldStage = data.resolve("skills/staging/" + orphan);
      Files.createDirectories(activeStage);
      Files.createDirectories(oldStage);
      var old = FileTime.fromMillis(System.currentTimeMillis() - 2 * 86400000L);
      Files.setLastModifiedTime(activeStage, old);
      Files.setLastModifiedTime(oldStage, old);
      skills.maintain();
      assertTrue(Files.exists(activeStage));
      assertFalse(Files.exists(oldStage));
      Files.delete(data.resolve("skills/packages/" + upload.versionId() + "/SKILL.md"));
      skills.recover();
      assertEquals("UNAVAILABLE", repository.version(skill.id(), upload.versionId()).status());
      assertFalse(skills.requireSkill(skill.id()).enabled());
      assertEquals("FAILED", skills.upload(active).status());
      assertEquals(
          0L,
          ((Number)
                  db.first("SELECT reserved_bytes AS n FROM tb_skill_quota WHERE singleton")
                      .get("n"))
              .longValue());
      assertNotNull(skills.detail(skill.id()));
      skills.maintain();
      assertFalse(Files.exists(activeStage));
      db.update("UPDATE tb_skill_quota SET used_bytes=1073741824 WHERE singleton");
      assertEquals(
          "SKILL_QUOTA_EXCEEDED",
          assertThrows(
                  BusinessException.class,
                  () -> skills.importPackage(null, Map.of("SKILL.md", body)))
              .code());
    }
  }
}
