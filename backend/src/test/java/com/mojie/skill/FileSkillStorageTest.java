package com.mojie.skill;

import static com.mojie.common.model.JsonSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.mojie.common.error.BusinessException;
import com.mojie.infrastructure.skill.FileSkillStorage;
import com.mojie.skill.model.SkillModels.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class FileSkillStorageTest {
  @TempDir Path data;

  private byte[] body() {
    return "---\nname: 包测试\ndescription: 完整包验证\nmetadata:\n  unknown: 保留\n---\n正文\n"
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void binaryExportRoundTripAndCorruptionDetection() {
    var store = new FileSkillStorage(data);
    String id = UUID.randomUUID().toString();
    var published =
        store.publish(
            UUID.randomUUID().toString(),
            id,
            Map.of(
                "wrapped/", new byte[0],
                "wrapped/SKILL.md", body(),
                "wrapped/assets/b.bin", new byte[] {0, 1, (byte) 255},
                "wrapped/empty/", new byte[0]));
    Version v =
        new Version(
            id,
            "",
            published.hash(),
            "",
            "READY",
            tree(encode(published.metadata())),
            published.bytes(),
            published.files().size(),
            tree("{}"),
            "");
    List<FileEntry> files =
        published.files().stream()
            .map(
                f ->
                    new FileEntry(
                        UUID.randomUUID().toString(),
                        id,
                        f.path(),
                        f.hash(),
                        f.bytes().length,
                        f.mime()))
            .toList();
    var imported = store.unpack(store.export(v, files));
    assertArrayEquals(new byte[] {0, 1, (byte) 255}, imported.get("assets/b.bin"));
    assertTrue(imported.containsKey("empty/"));
    assertFalse(imported.containsKey(""));
    store.verify(v, files);
    assertEquals(
        "保留",
        published.metadata().get("metadata") instanceof Map<?, ?> m ? m.get("unknown") : null);
    try {
      Files.write(data.resolve("skills/packages/" + id + "/assets/b.bin"), new byte[] {3});
    } catch (Exception e) {
      fail(e);
    }
    assertEquals(
        "SKILL_PACKAGE_UNAVAILABLE",
        assertThrows(BusinessException.class, () -> store.verify(v, files)).code());
  }

  @Test
  void zipRejectsLinksDuplicateEntriesAndDecompressionOverflow() throws Exception {
    var store = new FileSkillStorage(data);
    var link = new ZipArchiveEntry("scripts/link.py");
    link.setUnixMode(0120777);
    assertEquals(
        "SKILL_INVALID_PACKAGE",
        assertThrows(
                BusinessException.class,
                () ->
                    store.unpack(
                        zip(
                            List.of(link),
                            List.of("/etc/passwd".getBytes(StandardCharsets.UTF_8)))))
            .code());
    assertEquals(
        "SKILL_INVALID_PACKAGE",
        assertThrows(
                BusinessException.class,
                () ->
                    store.unpack(
                        zip(
                            List.of(
                                new ZipArchiveEntry("assets/é"),
                                new ZipArchiveEntry("assets/e\u0301")),
                            List.of(new byte[] {1}, new byte[] {2}))))
            .code());
    assertEquals(
        "SKILL_UPLOAD_LIMIT_EXCEEDED",
        assertThrows(
                BusinessException.class,
                () ->
                    store.unpack(
                        zip(
                            List.of(new ZipArchiveEntry("assets/bomb.bin")),
                            List.of(new byte[FileSkillStorage.FILE_LIMIT + 1]))))
            .code());
  }

  @Test
  void rejectsFileDirectoryCollisionsBeforePublishing() {
    var store = new FileSkillStorage(data);
    for (var files :
        List.of(
            Map.of("SKILL.md", body(), "assets", new byte[] {1}, "assets/file.bin", new byte[] {2}),
            Map.of("SKILL.md", body(), "assets", new byte[] {1}, "assets/", new byte[0]))) {
      String version = UUID.randomUUID().toString();
      assertEquals(
          "SKILL_INVALID_PACKAGE",
          assertThrows(
                  BusinessException.class,
                  () -> store.publish(UUID.randomUUID().toString(), version, files))
              .code());
      assertFalse(Files.exists(data.resolve("skills/packages/" + version)));
    }
  }

  private byte[] zip(List<ZipArchiveEntry> entries, List<byte[]> contents) throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipArchiveOutputStream(bytes)) {
      for (int i = 0; i < entries.size(); i++) {
        zip.putArchiveEntry(entries.get(i));
        zip.write(contents.get(i));
        zip.closeArchiveEntry();
      }
    }
    return bytes.toByteArray();
  }

  @Test
  void rejectsUnsafeRootsEncodingAndDuplicatePaths() {
    var store = new FileSkillStorage(data);
    for (String path :
        List.of(
            "../SKILL.md",
            "/SKILL.md",
            "C:/SKILL.md",
            "a\\SKILL.md",
            "a/../SKILL.md",
            "a//SKILL.md"))
      assertThrows(
          BusinessException.class,
          () ->
              store.publish(
                  UUID.randomUUID().toString(),
                  UUID.randomUUID().toString(),
                  Map.of(path, body())));
    assertThrows(
        BusinessException.class,
        () ->
            store.publish(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                Map.of("SKILL.md", body(), "other/SKILL.md", body())));
    assertThrows(
        BusinessException.class,
        () ->
            store.publish(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                Map.of("SKILL.md", new byte[] {(byte) 0xff})));
    assertThrows(
        BusinessException.class,
        () ->
            store.publish(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                Map.of(
                    "SKILL.md",
                    body(),
                    "assets/é",
                    new byte[] {1},
                    "assets/e\u0301",
                    new byte[] {2})));
  }
}
