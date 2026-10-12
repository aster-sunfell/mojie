package com.mojie.agent.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.agent.model.ChapterView;
import com.mojie.agent.service.port.ModelGateway.ToolSpec;
import com.mojie.agent.service.tool.AgentTool.Evidence;
import com.mojie.agent.service.tool.AgentTool.ToolResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Stateless tools; all mutable run state lives in ToolContext. */
public final class ContentTools {
  private ContentTools() {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public static List<AgentTool> all(ToolContentReader repository, byte[] cursorKey) {
    if (cursorKey.length < 16)
      throw new IllegalArgumentException("Cursor key must have at least 16 bytes");
    Cursor cursor = new Cursor(cursorKey.clone());
    return List.of(
        tool(
            "read_project",
            "Read the current work's saved title and project ID when discussing its title, naming"
                + " or identity. No arguments; the first successful read is fixed for this run.",
            readProject(repository)),
        tool("list_chapters", "List chapter metadata", listChapters(repository, cursor)),
        tool("read_chapter", "Read a complete chapter", readChapter(repository)),
        tool(
            "search_chapters", "Search chapter text literally", searchChapters(repository, cursor)),
        tool("list_settings", "List setting metadata", listSettings(repository, cursor)),
        tool("read_settings", "Read complete settings", readSettings(repository)),
        tool(
            "search_settings", "Search setting text literally", searchSettings(repository, cursor)),
        tool(
            "propose_chapter_edit",
            "Submit a candidate edit after reading the complete chapter; ASSISTANT requires"
                + " chapterId and expectedVersion from that read",
            propose()),
        tool(
            "propose_setting_edit",
            "Submit a setting edit after reading the complete setting and consuming its result; use"
                + " settingId and expectedVersion from that read",
            proposeSetting()));
  }

  private interface Action {
    ToolResult apply(JsonNode args, ToolContext context);
  }

  private static AgentTool tool(String name, String description, Action action) {
    return new AgentTool() {
      public ToolSpec spec() {
        return new ToolSpec(name, description, schema(name));
      }

      public ToolResult execute(JsonNode args, ToolContext context) {
        return action.apply(args, context);
      }
    };
  }

  private static Map<String, Object> schema(String name) {
    Map<String, Object> props = new LinkedHashMap<>();
    List<String> required = new ArrayList<>();
    switch (name) {
      case "read_project" -> {}
      case "list_chapters" -> {
        props.put("cursor", stringSchema());
        props.put("limit", integerSchema(1, 50));
      }
      case "read_chapter" -> {
        props.put("chapterId", stringSchema());
        required.add("chapterId");
      }
      case "search_chapters" -> {
        props.put("keyword", stringSchema());
        props.put(
            "scope", Map.of("type", "string", "enum", List.of("BEFORE_TARGET", "ALL_CHAPTERS")));
        props.put("limit", integerSchema(1, 20));
        props.put("cursor", stringSchema());
        required.add("keyword");
      }
      case "list_settings" -> {
        props.put("type", stringSchema());
        props.put("cursor", stringSchema());
        props.put("limit", integerSchema(1, 50));
      }
      case "read_settings" -> {
        props.put(
            "settingIds",
            Map.of("type", "array", "items", stringSchema(), "minItems", 1, "maxItems", 5));
        required.add("settingIds");
      }
      case "search_settings" -> {
        props.put("keyword", stringSchema());
        props.put("type", stringSchema());
        props.put("limit", integerSchema(1, 20));
        props.put("cursor", stringSchema());
        required.add("keyword");
      }
      case "propose_chapter_edit" -> {
        props.put("chapterId", stringSchema());
        props.put("expectedVersion", integerSchema(1, Integer.MAX_VALUE));
        props.put("proposedContent", stringSchema());
        props.put("reason", stringSchema());
        required.addAll(List.of("proposedContent", "reason"));
      }
      case "propose_setting_edit" -> {
        props.put("settingId", stringSchema());
        props.put("expectedVersion", integerSchema(1, Integer.MAX_VALUE));
        props.put(
            "proposedType",
            Map.of("type", "string", "enum", List.of("CHARACTER", "WORLD", "RULE")));
        props.put("proposedTitle", stringSchema());
        props.put("proposedContent", stringSchema());
        props.put("reason", stringSchema());
        required.addAll(
            List.of(
                "settingId",
                "expectedVersion",
                "proposedType",
                "proposedTitle",
                "proposedContent",
                "reason"));
      }
      default -> throw new IllegalArgumentException("Unknown tool schema");
    }
    return Map.of(
        "type", "object", "properties", props, "required", required, "additionalProperties", false);
  }

  private static Map<String, Object> stringSchema() {
    return Map.of("type", "string");
  }

  private static Map<String, Object> integerSchema(int min, int max) {
    return Map.of("type", "integer", "minimum", min, "maximum", max);
  }

  private static Action readProject(ToolContentReader repository) {
    return (args, ctx) -> {
      requireKeys(args);
      if (ctx.cancelled()) throw new java.util.concurrent.CancellationException("Read cancelled");
      ToolContentReader.ProjectMetadata project = ctx.readProject();
      if (project == null) {
        long timeout = Math.min(5_000, ctx.remainingMillis());
        if (timeout <= 0)
          return ToolResult.error("READ_TIMEOUT", "Project read exceeded its deadline", false);
        try {
          project = repository.project(ctx.projectId(), timeout, ctx);
        } catch (ToolContentReader.SearchTimeoutException e) {
          if (ctx.cancelled())
            throw new java.util.concurrent.CancellationException("Read cancelled");
          return ToolResult.error("READ_TIMEOUT", "Project read exceeded its deadline", false);
        }
        if (project == null) return missing();
        ctx.cacheProject(project);
      }
      return ToolResult.ok(
          Map.of("projectId", project.id(), "title", project.title()), List.of());
    };
  }

  private static Action listChapters(ToolContentReader repository, Cursor cursor) {
    return (args, ctx) -> {
      requireKeys(args, "cursor", "limit");
      int limit = number(args, "limit", 20, 1, 50);
      String query = "list_chapters";
      int offset = cursor.offset(args, ctx, query);
      List<ToolContentReader.ChapterMetadata> chapters =
          repository.chapters(ctx.projectId()).stream()
              .sorted(
                  Comparator.comparingInt(ToolContentReader.ChapterMetadata::order)
                      .thenComparing(ToolContentReader.ChapterMetadata::id))
              .toList();
      List<Map<String, Object>> rows = new ArrayList<>();
      for (ToolContentReader.ChapterMetadata c :
          chapters.subList(
              Math.min(offset, chapters.size()), Math.min(offset + limit, chapters.size())))
        rows.add(
            Map.of(
                "chapterId",
                c.id(),
                "chapterOrder",
                c.order(),
                "title",
                c.title(),
                "version",
                c.version()));
      return page(rows, offset, limit, chapters.size(), cursor, ctx, query, List.of());
    };
  }

  private static Action readChapter(ToolContentReader repository) {
    return (args, ctx) -> {
      requireKeys(args, "chapterId");
      String id = required(args, "chapterId", 1, 128);
      ToolContext.Chapter chapter;
      if (id.equals(ctx.targetChapterId())) {
        ToolContentReader.ChapterMetadata metadata =
            repository.chapters(ctx.projectId()).stream()
                .filter(c -> c.id().equals(id))
                .findFirst()
                .orElse(null);
        if (metadata == null) return missing();
        chapter =
            new ToolContext.Chapter(
                id, metadata.title(), metadata.order(), ctx.baseVersion(), ctx.baseContent());
      } else if (id.equals(ctx.preferredChapterId())) {
        ToolContentReader.ChapterMetadata metadata =
            repository.chapters(ctx.projectId()).stream()
                .filter(c -> c.id().equals(id))
                .findFirst()
                .orElse(null);
        if (metadata == null) return missing();
        chapter =
            new ToolContext.Chapter(
                id,
                metadata.title(),
                metadata.order(),
                ctx.preferredVersion(),
                ctx.preferredContent());
      } else {
        chapter = ctx.readChapters().get(id);
        if (chapter == null) {
          ToolContentReader.ChapterMetadata current =
              repository.chapters(ctx.projectId()).stream()
                  .filter(c -> c.id().equals(id))
                  .findFirst()
                  .orElse(null);
          if (current == null) return missing();
          long version =
              ctx.chapterView() == null
                  ? current.version()
                  : ctx.chapterView().getOrDefault(id, -1L);
          if (version < 0) return missing();
          chapter = repository.chapterRevision(ctx.projectId(), id, version);
          if (chapter == null) return missing();
        }
      }
      return ToolResult.ok(
          Map.of(
              "chapterId",
              id,
              "title",
              chapter.title(),
              "chapterOrder",
              chapter.order(),
              "version",
              chapter.version(),
              "content",
              chapter.content()),
          List.of(
              new Evidence(
                  "FULL_CHAPTER", "CHAPTER", id, Long.toString(chapter.version()), null, null)));
    };
  }

  private static Action searchChapters(ToolContentReader repository, Cursor cursor) {
    return (args, ctx) -> {
      long searchEnd = System.nanoTime() + 5_000_000_000L;
      try {
        requireKeys(args, "keyword", "scope", "limit", "cursor");
        String keyword = required(args, "keyword", 1, 128);
        if (keyword.contains("\n") || keyword.contains("\r"))
          throw new IllegalArgumentException("keyword cannot contain a newline");
        String anchor =
            ctx.targetChapterId() == null ? ctx.preferredChapterId() : ctx.targetChapterId();
        String scope =
            optional(
                args, "scope", ctx.unified() || anchor == null ? "ALL_CHAPTERS" : "BEFORE_TARGET");
        if (anchor == null && scope.equals("BEFORE_TARGET"))
          throw new IllegalArgumentException("A turn without a target supports ALL_CHAPTERS only");
        if (!scope.equals("BEFORE_TARGET") && !scope.equals("ALL_CHAPTERS"))
          throw new IllegalArgumentException("Invalid scope");
        int limit = number(args, "limit", 10, 1, 20);
        List<ToolContentReader.ChapterMetadata> chapters =
            repository.chapters(ctx.projectId(), searchRemaining(ctx, searchEnd), ctx).stream()
                .sorted(
                    Comparator.comparingInt(ToolContentReader.ChapterMetadata::order)
                        .thenComparing(ToolContentReader.ChapterMetadata::id))
                .toList();
        if (chapters.size() > 1000)
          return ToolResult.error(
              "SEARCH_SCOPE_TOO_LARGE", "Search view exceeds 1000 chapters", false);
        if (ctx.chapterView() == null) {
          ChapterView view =
              repository.captureChapterView(
                  ctx.runId(),
                  ctx.projectId(),
                  anchor,
                  ctx.targetChapterId() == null ? ctx.preferredVersion() : ctx.baseVersion(),
                  ctx.readChapters(),
                  searchRemaining(ctx, searchEnd),
                  ctx);
          ctx.setChapterView(view.versions(), view.viewId());
        }
        String query = "search_chapters:" + ctx.chapterViewId() + ":" + keyword + ":" + scope;
        SearchPosition after = cursor.searchPosition(args, ctx, query);
        int targetOrder =
            anchor == null
                ? 0
                : chapters.stream()
                    .filter(c -> c.id().equals(anchor))
                    .findFirst()
                    .map(ToolContentReader.ChapterMetadata::order)
                    .orElseThrow(() -> new IllegalArgumentException("Target chapter unavailable"));
        ToolContentReader.ChapterMetadata afterChapter =
            after == null
                ? null
                : chapters.stream()
                    .filter(c -> c.id().equals(after.chapterId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("INVALID_SEARCH_CURSOR"));
        Comparator<ToolContentReader.ChapterMetadata> chapterOrder =
            Comparator.comparingInt(ToolContentReader.ChapterMetadata::order)
                .thenComparing(ToolContentReader.ChapterMetadata::id);
        List<Map<String, Object>> hits = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        SearchPosition lastReturned = null;
        boolean hasMore = false;
        List<ToolContentReader.ChapterMetadata> eligible =
            chapters.stream()
                .filter(meta -> !scope.equals("BEFORE_TARGET") || meta.order() < targetOrder)
                .filter(
                    meta -> afterChapter == null || chapterOrder.compare(meta, afterChapter) >= 0)
                .filter(meta -> ctx.chapterView().containsKey(meta.id()))
                .toList();
        scan:
        for (int start = 0; start < eligible.size(); start += 32) {
          searchRemaining(ctx, searchEnd);
          List<ToolContentReader.ChapterMetadata> batch =
              eligible.subList(start, Math.min(start + 32, eligible.size()));
          List<ToolContentReader.ChapterVersion> versions =
              batch.stream()
                  .filter(meta -> !meta.id().equals(ctx.targetChapterId()))
                  .map(
                      meta ->
                          new ToolContentReader.ChapterVersion(
                              meta.id(), ctx.chapterView().get(meta.id())))
                  .toList();
          Map<String, ToolContext.Chapter> loaded = new HashMap<>();
          for (ToolContext.Chapter chapter :
              repository.chapterRevisions(
                  ctx.projectId(), versions, searchRemaining(ctx, searchEnd), ctx))
            loaded.put(chapter.id(), chapter);
          for (ToolContentReader.ChapterMetadata meta : batch) {
            searchRemaining(ctx, searchEnd);
            Long version = ctx.chapterView().get(meta.id());
            String content =
                meta.id().equals(ctx.targetChapterId())
                    ? ctx.baseContent()
                    : Optional.ofNullable(loaded.get(meta.id()))
                        .orElseThrow(
                            () ->
                                new IllegalStateException("Search chapter revision is unavailable"))
                        .content();
            int line = 0;
            for (String value : content.split("\n", -1)) {
              searchRemaining(ctx, searchEnd);
              line++;
              if (afterChapter != null
                  && meta.id().equals(after.chapterId())
                  && line < after.lineNumber()) continue;
              int from = 0, at;
              while ((at = value.indexOf(keyword, from)) >= 0) {
                searchRemaining(ctx, searchEnd);
                from = at + keyword.length();
                int cp = value.codePointCount(0, at);
                if (afterChapter != null
                    && meta.id().equals(after.chapterId())
                    && line == after.lineNumber()
                    && cp <= after.matchStart()) continue;
                if (hits.size() == limit) {
                  hasMore = true;
                  break scan;
                }
                Snippet excerpt = snippet(value, at, keyword.length());
                Map<String, Object> hit = new LinkedHashMap<>();
                hit.put("chapterId", meta.id());
                hit.put("chapterOrder", meta.order());
                hit.put("title", meta.title());
                hit.put("version", version);
                hit.put("lineNumber", line);
                hit.put("matchStart", cp);
                hit.put("matchEnd", cp + keyword.codePointCount(0, keyword.length()));
                hit.put("snippetStart", excerpt.start());
                hit.put("snippet", excerpt.text());
                hit.put("snippetTruncated", excerpt.truncated());
                hits.add(hit);
                evidence.add(
                    new Evidence(
                        "SEARCH_SNIPPET", "CHAPTER", meta.id(), Long.toString(version), line, cp));
                lastReturned = new SearchPosition(meta.id(), line, cp);
              }
            }
          }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("keyword", keyword);
        data.put("scope", scope);
        data.put("viewId", ctx.chapterViewId());
        data.put("matches", hits);
        data.put("hasMore", hasMore);
        data.put(
            "nextCursor", hasMore ? cursor.encodeSearchPosition(ctx, query, lastReturned) : null);
        if (jsonSize(data) > 12_000)
          return ToolResult.error(
              "RESULT_TOO_LARGE", "Search page exceeds 12000 code points", true);
        return ToolResult.ok(data, evidence);
      } catch (ToolContentReader.SearchTimeoutException e) {
        if (ctx.cancelled())
          throw new java.util.concurrent.CancellationException("Search cancelled");
        return ToolResult.error("SEARCH_TIMEOUT", "Search exceeded its deadline", false);
      }
    };
  }

  private static Action listSettings(ToolContentReader repository, Cursor cursor) {
    return (args, ctx) -> {
      requireKeys(args, "type", "cursor", "limit");
      String type = optional(args, "type", "");
      int limit = number(args, "limit", 20, 1, 50);
      String query = "list_settings:" + type;
      int offset = cursor.offset(args, ctx, query);
      List<ToolContentReader.Setting> settings =
          repository.settings(ctx.projectId()).stream()
              .filter(s -> type.isEmpty() || s.type().equals(type))
              .sorted(Comparator.comparing(ToolContentReader.Setting::id))
              .toList();
      List<Map<String, Object>> rows = new ArrayList<>();
      for (ToolContentReader.Setting s :
          settings.subList(
              Math.min(offset, settings.size()), Math.min(offset + limit, settings.size())))
        rows.add(
            Map.of(
                "settingId", s.id(), "type", s.type(), "title", s.title(), "version", s.version()));
      return page(rows, offset, limit, settings.size(), cursor, ctx, query, List.of());
    };
  }

  private static Action readSettings(ToolContentReader repository) {
    return (args, ctx) -> {
      requireKeys(args, "settingIds");
      JsonNode ids = args.get("settingIds");
      if (ids == null || !ids.isArray() || ids.size() < 1 || ids.size() > 5)
        throw new IllegalArgumentException("settingIds must contain 1-5 IDs");
      Set<String> seen = new HashSet<>();
      Map<String, ToolContentReader.Setting> byId = new HashMap<>();
      for (ToolContentReader.Setting setting : repository.settings(ctx.projectId()))
        byId.put(setting.id(), setting);
      List<Map<String, Object>> rows = new ArrayList<>();
      List<Evidence> evidence = new ArrayList<>();
      for (JsonNode node : ids) {
        if (!node.isTextual() || node.asText().isBlank())
          throw new IllegalArgumentException("Invalid setting ID");
        String id = node.asText();
        if (!seen.add(id)) throw new IllegalArgumentException("Duplicate setting ID");
        ToolContentReader.Setting s = byId.get(id);
        if (s == null) return missing();
        rows.add(
            Map.of(
                "settingId",
                id,
                "type",
                s.type(),
                "title",
                s.title(),
                "content",
                s.content(),
                "version",
                s.version()));
        evidence.add(
            new Evidence(
                "FULL_SETTING", "SETTING", id, sha256(s.title() + "\n" + s.content()), null, null));
      }
      return ToolResult.ok(Map.of("settings", rows), evidence);
    };
  }

  private static Action searchSettings(ToolContentReader repository, Cursor cursor) {
    return (args, ctx) -> {
      long searchEnd = System.nanoTime() + 5_000_000_000L;
      try {
        requireKeys(args, "keyword", "type", "limit", "cursor");
        String keyword = required(args, "keyword", 1, 128);
        if (keyword.contains("\n") || keyword.contains("\r"))
          throw new IllegalArgumentException("keyword cannot contain a newline");
        String type = optional(args, "type", "");
        int limit = number(args, "limit", 5, 1, 20);
        String query = "search_settings:" + keyword + ":" + type;
        String afterId = cursor.settingId(args, ctx, query);
        List<Map<String, Object>> hits = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        String lastReturned = null;
        boolean hasMore = false;
        for (ToolContentReader.Setting s :
            repository.settings(ctx.projectId(), searchRemaining(ctx, searchEnd), ctx).stream()
                .sorted(Comparator.comparing(ToolContentReader.Setting::id))
                .toList()) {
          searchRemaining(ctx, searchEnd);
          if (afterId != null && s.id().compareTo(afterId) <= 0) continue;
          if (!type.isEmpty() && !s.type().equals(type)) continue;
          int titleAt = s.title().indexOf(keyword);
          int contentAt = titleAt < 0 ? s.content().indexOf(keyword) : -1;
          if (titleAt < 0 && contentAt < 0) continue;
          if (hits.size() == limit) {
            hasMore = true;
            break;
          }
          String field = titleAt >= 0 ? "TITLE" : "CONTENT";
          String value = titleAt >= 0 ? s.title() : s.content();
          int at = titleAt >= 0 ? titleAt : contentAt;
          int cp = value.codePointCount(0, at);
          hits.add(
              Map.of(
                  "settingId",
                  s.id(),
                  "type",
                  s.type(),
                  "title",
                  s.title(),
                  "matchField",
                  field,
                  "snippet",
                  snippet(value, at, keyword.length()).text(),
                  "codePointOffset",
                  cp));
          evidence.add(
              new Evidence(
                  "SEARCH_SNIPPET",
                  "SETTING",
                  s.id(),
                  sha256(s.title() + "\n" + s.content()),
                  null,
                  cp));
          lastReturned = s.id();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", hits);
        data.put("hasMore", hasMore);
        data.put("nextCursor", hasMore ? cursor.encodeSettingId(ctx, query, lastReturned) : null);
        if (jsonSize(data) > 12_000)
          return ToolResult.error(
              "RESULT_TOO_LARGE", "Search page exceeds 12000 code points", true);
        return ToolResult.ok(data, evidence);
      } catch (ToolContentReader.SearchTimeoutException e) {
        if (ctx.cancelled())
          throw new java.util.concurrent.CancellationException("Search cancelled");
        return ToolResult.error("SEARCH_TIMEOUT", "Search exceeded its deadline", false);
      }
    };
  }

  private static long searchRemaining(ToolContext ctx, long searchEnd) {
    if (ctx.cancelled()) throw new java.util.concurrent.CancellationException("Search cancelled");
    long own = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(searchEnd - System.nanoTime());
    long remaining = Math.min(own, ctx.remainingMillis());
    if (remaining <= 0) throw new ToolContentReader.SearchTimeoutException(null);
    return remaining;
  }

  private static Action propose() {
    return (args, ctx) -> {
      requireKeys(args, "chapterId", "expectedVersion", "proposedContent", "reason");
      String chapterId = ctx.targetChapterId();
      int version = Math.toIntExact(ctx.baseVersion());
      String base = ctx.baseContent();
      if (ctx.unified()) {
        chapterId = required(args, "chapterId", 1, 128);
        if (!args.has("expectedVersion"))
          throw new IllegalArgumentException("expectedVersion is required for assistant edits");
        version = number(args, "expectedVersion", -1, 1, Integer.MAX_VALUE);
        ToolContext.Chapter chapter = ctx.readChapters().get(chapterId);
        if (chapter == null || !ctx.chapterProvided(chapterId, chapter.version()))
          return ToolResult.error(
              "INSUFFICIENT_CONTEXT",
              "Read the complete chapter and consume its result before proposing an edit",
              true);
        if (chapter.version() != version)
          return ToolResult.error(
              "VERSION_CONFLICT", "Use the version returned by read_chapter", true);
        base = chapter.content();
      } else {
        if (!ctx.targetRead())
          return ToolResult.error("INSUFFICIENT_CONTEXT", "Read the target chapter first", true);
        if (args.has("chapterId") && !Objects.equals(chapterId, required(args, "chapterId", 1, 128))
            || args.has("expectedVersion")
                && number(args, "expectedVersion", -1, 1, Integer.MAX_VALUE) != version)
          return ToolResult.error(
              "VALIDATION_ERROR", "Legacy edits must use the fixed target and version", true);
      }
      if (ctx.candidate() != null)
        return ToolResult.error("PROPOSAL_ALREADY_EXISTS", "One candidate already exists", false);
      String content = required(args, "proposedContent", 1, 6000);
      String reason = required(args, "reason", 1, 2000);
      if (content.equals(base))
        return ToolResult.error("VALIDATION_ERROR", "Candidate matches base content", true);
      ctx.setCandidate(new ToolContext.Candidate(content, reason, chapterId, version, base));
      return ToolResult.ok(
          Map.of("accepted", true, "chapterId", chapterId, "baseVersion", version), List.of());
    };
  }

  private static Action proposeSetting() {
    return (args, ctx) -> {
      if (!ctx.unified())
        return ToolResult.error(
            "EDIT_DISABLED", "Setting edits are unavailable for this turn", false);
      requireKeys(
          args,
          "settingId",
          "expectedVersion",
          "proposedType",
          "proposedTitle",
          "proposedContent",
          "reason");
      String id = required(args, "settingId", 1, 128);
      int version = number(args, "expectedVersion", -1, 1, Integer.MAX_VALUE);
      ToolContext.SettingInput base = ctx.readSettings().get(id);
      if (base == null || !ctx.settingProvided(id, base.version()))
        return ToolResult.error(
            "INSUFFICIENT_CONTEXT",
            "Read the complete setting in this turn and consume its result before proposing an"
                + " edit",
            true);
      if (version != base.version())
        return ToolResult.error(
            "VERSION_CONFLICT", "Use the version returned by read_settings", true);
      if (ctx.candidate() != null)
        return ToolResult.error("PROPOSAL_ALREADY_EXISTS", "One candidate already exists", false);
      String type = required(args, "proposedType", 1, 32);
      if (!List.of("CHARACTER", "WORLD", "RULE").contains(type))
        throw new IllegalArgumentException("Invalid setting type");
      String title = required(args, "proposedTitle", 1, 80);
      String content = required(args, "proposedContent", 1, 2000);
      String reason = required(args, "reason", 1, 2000);
      if (type.equals(base.type()) && title.equals(base.title()) && content.equals(base.content()))
        return ToolResult.error("VALIDATION_ERROR", "Candidate matches base setting", true);
      ctx.setCandidate(
          new ToolContext.Candidate(
              content,
              reason,
              null,
              version,
              base.content(),
              new ToolContext.SettingEdit(id, base.type(), base.title(), type, title)));
      return ToolResult.ok(
          Map.of("accepted", true, "settingId", id, "baseVersion", version), List.of());
    };
  }

  private static ToolResult page(
      List<Map<String, Object>> rows,
      int offset,
      int limit,
      int total,
      Cursor cursor,
      ToolContext ctx,
      String query,
      List<Evidence> evidence) {
    boolean more = offset + limit < total;
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", rows);
    data.put("hasMore", more);
    data.put("nextCursor", more ? cursor.encode(ctx, query, offset + limit) : null);
    return ToolResult.ok(data, evidence);
  }

  private static ToolResult missing() {
    return ToolResult.error("RESOURCE_NOT_FOUND", "Resource unavailable in this project", true);
  }

  private static void requireKeys(JsonNode args, String... allowed) {
    Set<String> keys = Set.of(allowed);
    args.fieldNames()
        .forEachRemaining(
            key -> {
              if (!keys.contains(key))
                throw new IllegalArgumentException("Unknown argument: " + key);
            });
  }

  private static String required(JsonNode args, String name, int min, int max) {
    JsonNode value = args.get(name);
    if (value == null || !value.isTextual())
      throw new IllegalArgumentException(name + " must be text");
    String text = value.asText();
    int size = text.codePointCount(0, text.length());
    if (text.isBlank() || size < min || size > max)
      throw new IllegalArgumentException(name + " length is invalid");
    return text;
  }

  private static String optional(JsonNode args, String name, String fallback) {
    JsonNode value = args.get(name);
    if (value == null || value.isNull()) return fallback;
    if (!value.isTextual()) throw new IllegalArgumentException(name + " must be text");
    return value.asText();
  }

  private static int number(JsonNode args, String name, int fallback, int min, int max) {
    JsonNode value = args.get(name);
    if (value == null) return fallback;
    if (!value.isIntegralNumber() || value.asInt() < min || value.asInt() > max)
      throw new IllegalArgumentException(name + " out of range");
    return value.asInt();
  }

  private record Snippet(int start, String text, boolean truncated) {}

  private static Snippet snippet(String line, int at, int length) {
    int total = codePoints(line);
    int matchStart = line.codePointCount(0, at);
    int matchEnd = line.codePointCount(0, at + length);
    int width = Math.max(320, matchEnd - matchStart);
    int startCp = Math.max(0, matchStart - Math.max(0, (width - (matchEnd - matchStart)) / 2));
    int endCp = Math.min(total, startCp + width);
    startCp = Math.max(0, endCp - width);
    return new Snippet(
        startCp,
        line.substring(line.offsetByCodePoints(0, startCp), line.offsetByCodePoints(0, endCp)),
        startCp > 0 || endCp < total);
  }

  private static int codePoints(String text) {
    return text.codePointCount(0, text.length());
  }

  private static int jsonSize(Object value) {
    try {
      return codePoints(JSON.writeValueAsString(value));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private record SearchPosition(String chapterId, int lineNumber, int matchStart) {}

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static final class Cursor {
    private final byte[] key;

    Cursor(byte[] key) {
      this.key = key;
    }

    String encode(ToolContext ctx, String query, int offset) {
      return encode(ctx, query, Integer.toString(offset));
    }

    String encodeSearchPosition(ToolContext ctx, String query, SearchPosition position) {
      String id =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(position.chapterId().getBytes(StandardCharsets.UTF_8));
      return encode(
          ctx, query, "search:" + id + ":" + position.lineNumber() + ":" + position.matchStart());
    }

    String encodeSettingId(ToolContext ctx, String query, String id) {
      String encoded =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(id.getBytes(StandardCharsets.UTF_8));
      return encode(ctx, query, "setting:" + encoded);
    }

    private String encode(ToolContext ctx, String query, String position) {
      String payload = ctx.runId() + "\n" + query + "\n" + position;
      String raw =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
      return raw + "." + sign(raw);
    }

    int offset(JsonNode args, ToolContext ctx, String query) {
      String value = position(args, ctx, query);
      if (value == null) return 0;
      try {
        int offset = Integer.parseInt(value);
        if (offset < 0) throw new IllegalArgumentException("INVALID_SEARCH_CURSOR");
        return offset;
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("INVALID_SEARCH_CURSOR");
      }
    }

    SearchPosition searchPosition(JsonNode args, ToolContext ctx, String query) {
      String value = position(args, ctx, query);
      if (value == null) return null;
      String[] fields = value.split(":", -1);
      if (fields.length != 4 || !fields[0].equals("search"))
        throw new IllegalArgumentException("INVALID_SEARCH_CURSOR");
      try {
        String id = new String(Base64.getUrlDecoder().decode(fields[1]), StandardCharsets.UTF_8);
        int line = Integer.parseInt(fields[2]);
        int start = Integer.parseInt(fields[3]);
        if (id.isBlank() || line < 1 || start < 0)
          throw new IllegalArgumentException("INVALID_SEARCH_CURSOR");
        return new SearchPosition(id, line, start);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("INVALID_SEARCH_CURSOR");
      }
    }

    String settingId(JsonNode args, ToolContext ctx, String query) {
      String value = position(args, ctx, query);
      if (value == null) return null;
      if (!value.startsWith("setting:"))
        throw new IllegalArgumentException("INVALID_SEARCH_CURSOR");
      try {
        String id =
            new String(
                Base64.getUrlDecoder().decode(value.substring("setting:".length())),
                StandardCharsets.UTF_8);
        if (id.isBlank()) throw new IllegalArgumentException("INVALID_SEARCH_CURSOR");
        return id;
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("INVALID_SEARCH_CURSOR");
      }
    }

    private String position(JsonNode args, ToolContext ctx, String query) {
      String value = optional(args, "cursor", "");
      if (value.isEmpty()) return null;
      if (value.length() > 4096) throw new IllegalArgumentException("INVALID_SEARCH_CURSOR");
      String[] parts = value.split("\\.", -1);
      if (parts.length != 2
          || !MessageDigest.isEqual(
              sign(parts[0]).getBytes(StandardCharsets.US_ASCII),
              parts[1].getBytes(StandardCharsets.US_ASCII)))
        throw new IllegalArgumentException("INVALID_SEARCH_CURSOR");
      try {
        String[] decoded =
            new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)
                .split("\n", -1);
        if (decoded.length != 3 || !decoded[0].equals(ctx.runId()) || !decoded[1].equals(query))
          throw new IllegalArgumentException("INVALID_SEARCH_CURSOR");
        return decoded[2];
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("INVALID_SEARCH_CURSOR");
      }
    }

    private String sign(String raw) {
      try {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(mac.doFinal(raw.getBytes(StandardCharsets.US_ASCII)));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
