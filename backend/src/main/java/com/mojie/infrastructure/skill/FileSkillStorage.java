package com.mojie.infrastructure.skill;

import static com.mojie.common.model.JsonSupport.*;

import com.mojie.common.error.BusinessException;
import com.mojie.skill.model.SkillModels.*;
import com.mojie.skill.service.port.SkillStorage;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.*;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.zip.*;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class FileSkillStorage implements SkillStorage {
  public static final int RAW_LIMIT = 20 * 1024 * 1024,
      FILE_LIMIT = 20 * 1024 * 1024,
      TOTAL_LIMIT = 100 * 1024 * 1024;
  private final Path root;

  public FileSkillStorage(Path root) {
    this.root = root.toAbsolutePath().normalize();
    try {
      Files.createDirectories(this.root.resolve("skills/staging"));
      Files.createDirectories(this.root.resolve("skills/packages"));
      Files.createDirectories(this.root.resolve("artifacts"));
    } catch (IOException e) {
      throw new IllegalStateException("File storage unavailable", e);
    }
  }

  public static String safePath(String value) {
    if (value == null
        || value.isBlank()
        || value.indexOf('\0') >= 0
        || value.contains("\\")
        || value.startsWith("/")
        || value.matches("^[A-Za-z]:.*")) throw invalid("Invalid package path");
    String n = Normalizer.normalize(value, Normalizer.Form.NFC);
    String[] parts = n.split("/", -1);
    if (parts.length > 16) throw invalid("Package directory depth exceeds 16");
    for (String part : parts)
      if (part.isEmpty() || part.equals(".") || part.equals(".."))
        throw invalid("Invalid package path");
    return n;
  }

  public Map<String, byte[]> unpack(byte[] bytes) {
    if (bytes.length > RAW_LIMIT) throw limit();
    Path temp = null;
    try {
      temp = Files.createTempFile(root.resolve("skills/staging"), "zip-", ".zip");
      Files.write(temp, bytes);
      Map<String, byte[]> files = new LinkedHashMap<>();
      Set<String> seen = new HashSet<>();
      long total = 0;
      int count = 0;
      try (ZipFile zip = ZipFile.builder().setFile(temp.toFile()).get()) {
        var entries = zip.getEntries();
        while (entries.hasMoreElements()) {
          ZipArchiveEntry entry = entries.nextElement();
          String raw = entry.getName();
          String path =
              safePath(
                  entry.isDirectory() && raw.endsWith("/")
                      ? raw.substring(0, raw.length() - 1)
                      : raw);
          if (!seen.add(path)) throw invalid("Duplicate normalized path");
          if (++count > 500) throw limit();
          int mode = entry.getUnixMode() & 0170000;
          if (entry.isUnixSymlink()
              || mode != 0 && mode != 0100000 && mode != 0040000
              || entry.getGeneralPurposeBit().usesEncryption()
              || !zip.canReadEntryData(entry))
            throw invalid("Links, special or encrypted ZIP entries are unsupported");
          if (entry.isDirectory()) {
            files.put(path + "/", new byte[0]);
            continue;
          }
          if (entry.getSize() > FILE_LIMIT) throw limit();
          byte[] data;
          try (InputStream in = zip.getInputStream(entry)) {
            data = in.readNBytes(FILE_LIMIT + 1);
          }
          if (data.length > FILE_LIMIT || (total += data.length) > TOTAL_LIMIT) throw limit();
          files.put(path, data);
        }
      }
      return files;
    } catch (BusinessException e) {
      throw e;
    } catch (IOException e) {
      throw invalid("Invalid ZIP archive");
    } finally {
      if (temp != null)
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {
        }
    }
  }

  public Published publish(String upload, String version, Map<String, byte[]> input) {
    UUID.fromString(upload);
    UUID.fromString(version);
    if (input.isEmpty() || input.size() > 500) throw limit();
    Map<String, byte[]> files = new TreeMap<>();
    long total = 0;
    for (var entry : input.entrySet()) {
      String key = entry.getKey();
      boolean directory = key.endsWith("/");
      String path =
          safePath(directory ? key.substring(0, key.length() - 1) : key) + (directory ? "/" : "");
      if (files.putIfAbsent(path, entry.getValue()) != null)
        throw invalid("Duplicate normalized path");
      if (entry.getValue().length > FILE_LIMIT || (total += entry.getValue().length) > TOTAL_LIMIT)
        throw limit();
    }
    var roots =
        files.keySet().stream()
            .filter(p -> p.equals("SKILL.md") || p.endsWith("/SKILL.md"))
            .toList();
    if (roots.size() != 1)
      throw invalid("Exactly one SKILL.md is required; import multiple Skills separately");
    String skillRoot = roots.getFirst();
    String prefix = skillRoot.substring(0, skillRoot.length() - 8);
    if (!prefix.isEmpty() && prefix.substring(0, prefix.length() - 1).contains("/"))
      throw invalid("Only one wrapping directory is supported");
    Map<String, byte[]> normalized = new TreeMap<>();
    for (var e : files.entrySet()) {
      if (!e.getKey().startsWith(prefix)) throw invalid("All files must belong to the Skill root");
      if (e.getKey().equals(prefix)) continue;
      normalized.put(e.getKey().substring(prefix.length()), e.getValue());
    }
    for (String path : normalized.keySet()) {
      String withoutSlash = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
      if (path.endsWith("/") && normalized.containsKey(withoutSlash))
        throw invalid("A package path cannot be both a file and a directory");
      int slash = withoutSlash.indexOf('/');
      while (slash >= 0) {
        if (normalized.containsKey(withoutSlash.substring(0, slash)))
          throw invalid("A package file cannot contain other entries");
        slash = withoutSlash.indexOf('/', slash + 1);
      }
    }
    for (var entry : normalized.entrySet())
      if (mime(entry.getKey()).startsWith("text/")
          || mime(entry.getKey()).equals("application/json")) utf8(entry.getValue());
    byte[] skill = normalized.get("SKILL.md");
    if (skill.length > 65536) throw limit();
    String text = utf8(skill);
    Map<String, Object> metadata = parse(text);
    Path stage = root.resolve("skills/staging/" + upload),
        destination = root.resolve("skills/packages/" + version);
    List<PackageFile> manifest = new ArrayList<>();
    try {
      Files.createDirectory(stage);
      for (var e : normalized.entrySet()) {
        Path path = stage.resolve(e.getKey());
        if (e.getKey().endsWith("/")) {
          Files.createDirectories(path);
          manifest.add(
              new PackageFile(e.getKey(), new byte[0], hash(new byte[0]), "inode/directory"));
          continue;
        }
        Files.createDirectories(path.getParent());
        try (FileChannel ch =
            FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
          ByteBuffer b = ByteBuffer.wrap(e.getValue());
          while (b.hasRemaining()) ch.write(b);
          ch.force(true);
        }
        manifest.add(
            new PackageFile(e.getKey(), e.getValue(), hash(e.getValue()), mime(e.getKey())));
      }
      try (var paths = Files.walk(stage)) {
        for (Path p : paths.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList())
          sync(p);
      }
      Files.move(stage, destination, StandardCopyOption.ATOMIC_MOVE);
      sync(destination.getParent());
      sync(stage.getParent());
      String digest =
          hash(
              encode(
                  manifest.stream()
                      .map(
                          f -> Map.of("path", f.path(), "hash", f.hash(), "size", f.bytes().length))
                      .toList()));
      return new Published(version, digest, total, metadata, List.copyOf(manifest));
    } catch (IOException e) {
      throw new BusinessException("SKILL_PACKAGE_UNAVAILABLE", "Package could not be persisted");
    }
  }

  public static String utf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      throw invalid("Text is not valid UTF-8");
    }
  }

  private static Map<String, Object> parse(String text) {
    String normalized = text.replace("\r\n", "\n");
    if (!normalized.startsWith("---\n")) throw invalid("SKILL.md requires YAML frontmatter");
    int end = normalized.indexOf("\n---", 4);
    if (end < 0
        || !normalized.substring(end + 4).startsWith("\n")
        || normalized.substring(end + 4).isBlank()) throw invalid("Skill body must be nonempty");
    try {
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      options.setMaxAliasesForCollections(10);
      options.setCodePointLimit(65536);
      Object parsed = new Yaml(new SafeConstructor(options)).load(normalized.substring(4, end));
      if (!(parsed instanceof Map<?, ?> map)) throw invalid("Frontmatter must be a mapping");
      Map<String, Object> result = new LinkedHashMap<>();
      for (var e : map.entrySet()) {
        if (!(e.getKey() instanceof String key)) throw invalid("Metadata keys must be strings");
        result.put(key, e.getValue());
      }
      for (var entry : Map.of("name", 64, "description", 1024).entrySet()) {
        Object value = result.get(entry.getKey());
        if (!(value instanceof String s)
            || s.isBlank()
            || s.codePointCount(0, s.length()) > entry.getValue())
          throw invalid("Invalid " + entry.getKey());
      }
      encode(result);
      return result;
    } catch (BusinessException e) {
      throw e;
    } catch (RuntimeException e) {
      throw invalid("Invalid YAML metadata");
    }
  }

  public byte[] read(Version version, FileEntry file) {
    try {
      if (!file.versionId().equals(version.id()) || !"READY".equals(version.status()))
        throw unavailable();
      Path packageRoot = root.resolve("skills/packages/" + UUID.fromString(version.id()));
      String relative = file.relativePath();
      boolean directory = file.mime().equals("inode/directory");
      Path path =
          packageRoot.resolve(
              safePath(directory ? relative.substring(0, relative.length() - 1) : relative));
      if (directory) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(path)
            || !path.toRealPath().startsWith(packageRoot.toRealPath())) throw unavailable();
        return new byte[0];
      }
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
          || Files.isSymbolicLink(path)
          || !path.toRealPath().startsWith(packageRoot.toRealPath())) throw unavailable();
      if (Files.size(path) != file.sizeBytes() || file.sizeBytes() > FILE_LIMIT)
        throw unavailable();
      byte[] b = Files.readAllBytes(path);
      if (!hash(b).equals(file.hash().trim())) throw unavailable();
      return b;
    } catch (IOException e) {
      throw unavailable();
    }
  }

  public void discardVersion(String id) {
    UUID.fromString(id);
    removeTree(root.resolve("skills/packages/" + id));
  }

  public void deleteArtifact(String id) {
    UUID.fromString(id);
    removeTree(root.resolve("artifacts/" + id));
  }

  public void cleanup(Set<String> versions, Set<String> uploads, Set<String> artifacts) {
    cleanupDirectory(root.resolve("skills/staging"), uploads);
    cleanupDirectory(root.resolve("skills/packages"), versions);
    cleanupDirectory(root.resolve("artifacts"), artifacts);
  }

  private void cleanupDirectory(Path dir, Set<String> retained) {
    try (var paths = Files.list(dir)) {
      long cutoff = System.currentTimeMillis() - 86400000L;
      for (Path path : paths.toList())
        if (!retained.contains(path.getFileName().toString())
            && Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis() < cutoff)
          removeTree(path);
    } catch (IOException e) {
      throw unavailable();
    }
  }

  private static void removeTree(Path dir) {
    try {
      if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
      try (var paths = Files.walk(dir)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    } catch (IOException e) {
      throw unavailable();
    }
  }

  public void verify(Version v, List<FileEntry> files) {
    for (var f : files) read(v, f);
  }

  public byte[] export(Version version, List<FileEntry> files) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (ZipOutputStream zip = new ZipOutputStream(out)) {
        for (var f : files) {
          zip.putNextEntry(new ZipEntry(f.relativePath()));
          zip.write(read(version, f));
          zip.closeEntry();
        }
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw unavailable();
    }
  }

  public byte[] artifact(Artifact file) {
    try {
      Path path = root.resolve("artifacts/" + UUID.fromString(file.id()) + "/file");
      if (Files.isSymbolicLink(path)
          || Files.size(path) != file.sizeBytes()
          || file.sizeBytes() > FILE_LIMIT) throw unavailable();
      byte[] bytes = Files.readAllBytes(path);
      if (!hash(bytes).equals(file.hash().trim())) throw unavailable();
      return bytes;
    } catch (IOException e) {
      throw unavailable();
    }
  }

  public static String mime(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".md")) return "text/markdown";
    if (lower.endsWith(".txt")
        || lower.endsWith(".py")
        || lower.endsWith(".js")
        || lower.endsWith(".yaml")
        || lower.endsWith(".yml")) return "text/plain";
    if (lower.endsWith(".json")) return "application/json";
    if (lower.endsWith(".png")) return "image/png";
    if (lower.endsWith(".pdf")) return "application/pdf";
    return "application/octet-stream";
  }

  private static void sync(Path directory) throws IOException {
    try (FileChannel ch = FileChannel.open(directory, StandardOpenOption.READ)) {
      ch.force(true);
    }
  }

  private static BusinessException invalid(String m) {
    return new BusinessException("SKILL_INVALID_PACKAGE", m);
  }

  private static BusinessException limit() {
    return new BusinessException(
        "SKILL_UPLOAD_LIMIT_EXCEEDED", "Package exceeds the published upload limits");
  }

  private static BusinessException unavailable() {
    return new BusinessException("SKILL_PACKAGE_UNAVAILABLE", "Package file is missing or corrupt");
  }
}
