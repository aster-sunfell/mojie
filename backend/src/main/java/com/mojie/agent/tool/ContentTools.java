package com.mojie.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.agent.model.ModelGateway.ToolSpec;
import com.mojie.agent.tool.AgentTool.Evidence;
import com.mojie.agent.tool.AgentTool.ToolResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Seven stateless tools; all mutable run state lives in ToolContext. */
public final class ContentTools {
    private ContentTools() {}
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static List<AgentTool> all(ContentRepository repository, byte[] cursorKey) {
        if (cursorKey.length < 16) throw new IllegalArgumentException("Cursor key must have at least 16 bytes");
        Cursor cursor = new Cursor(cursorKey.clone());
        return List.of(
                tool("list_chapters", "List chapter metadata", listChapters(repository, cursor)),
                tool("read_chapter", "Read a complete chapter", readChapter(repository)),
                tool("search_chapters", "Search chapter text literally", searchChapters(repository, cursor)),
                tool("list_settings", "List setting metadata", listSettings(repository, cursor)),
                tool("read_settings", "Read complete settings", readSettings(repository)),
                tool("search_settings", "Search setting text literally", searchSettings(repository, cursor)),
                tool("propose_edit", "Submit one candidate edit for the target chapter", propose()));
    }

    private interface Action { ToolResult apply(JsonNode args, ToolContext context); }
    private static AgentTool tool(String name, String description, Action action) {
        return new AgentTool() {
            public ToolSpec spec() { return new ToolSpec(name, description, schema(name)); }
            public ToolResult execute(JsonNode args, ToolContext context) { return action.apply(args, context); }
        };
    }

    private static Map<String, Object> schema(String name) {
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        switch (name) {
            case "list_chapters" -> { props.put("cursor", stringSchema()); props.put("limit", integerSchema(1, 50)); }
            case "read_chapter" -> { props.put("chapterId", stringSchema()); required.add("chapterId"); }
            case "search_chapters" -> {
                props.put("keyword", stringSchema()); props.put("scope", Map.of("type", "string", "enum", List.of("BEFORE_TARGET", "ALL_CHAPTERS")));
                props.put("limit", integerSchema(1, 20)); props.put("cursor", stringSchema()); required.add("keyword");
            }
            case "list_settings" -> { props.put("type", stringSchema()); props.put("cursor", stringSchema()); props.put("limit", integerSchema(1, 50)); }
            case "read_settings" -> {
                props.put("settingIds", Map.of("type", "array", "items", stringSchema(), "minItems", 1, "maxItems", 5)); required.add("settingIds");
            }
            case "search_settings" -> {
                props.put("keyword", stringSchema()); props.put("type", stringSchema());
                props.put("limit", integerSchema(1, 20)); props.put("cursor", stringSchema()); required.add("keyword");
            }
            case "propose_edit" -> {
                props.put("proposedContent", stringSchema()); props.put("reason", stringSchema());
                required.addAll(List.of("proposedContent", "reason"));
            }
            default -> throw new IllegalArgumentException("Unknown tool schema");
        }
        return Map.of("type", "object", "properties", props, "required", required, "additionalProperties", false);
    }
    private static Map<String, Object> stringSchema() { return Map.of("type", "string"); }
    private static Map<String, Object> integerSchema(int min, int max) { return Map.of("type", "integer", "minimum", min, "maximum", max); }

    private static Action listChapters(ContentRepository repository, Cursor cursor) {
        return (args, ctx) -> {
            requireKeys(args, "cursor", "limit");
            int limit = number(args, "limit", 20, 1, 50);
            String query = "list_chapters";
            int offset = cursor.offset(args, ctx, query);
            List<ToolContext.Chapter> chapters = repository.chapters(ctx.projectId()).stream()
                    .sorted(Comparator.comparingInt(ToolContext.Chapter::order).thenComparing(ToolContext.Chapter::id)).toList();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ToolContext.Chapter c : chapters.subList(Math.min(offset, chapters.size()), Math.min(offset + limit, chapters.size())))
                rows.add(Map.of("chapterId", c.id(), "chapterOrder", c.order(), "title", c.title(), "version", c.version()));
            return page(rows, offset, limit, chapters.size(), cursor, ctx, query, List.of());
        };
    }

    private static Action readChapter(ContentRepository repository) {
        return (args, ctx) -> {
            requireKeys(args, "chapterId");
            String id = required(args, "chapterId", 1, 128);
            ToolContext.Chapter chapter;
            if (id.equals(ctx.targetChapterId())) {
                ToolContext.Chapter metadata = repository.chapters(ctx.projectId()).stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
                if (metadata == null) return missing();
                chapter = new ToolContext.Chapter(id, metadata.title(), metadata.order(), ctx.baseVersion(), ctx.baseContent());
            } else {
                chapter = ctx.readChapters().get(id);
                if (chapter == null) {
                    ToolContext.Chapter current = repository.chapters(ctx.projectId()).stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
                    if (current == null) return missing();
                    long version = ctx.chapterView() == null ? current.version() : ctx.chapterView().getOrDefault(id, -1L);
                    if (version < 0) return missing();
                    chapter = repository.chapterRevision(ctx.projectId(), id, version);
                    if (chapter == null) return missing();
                }
            }
            return ToolResult.ok(Map.of("chapterId", id, "title", chapter.title(), "chapterOrder", chapter.order(),
                    "version", chapter.version(), "content", chapter.content()),
                    List.of(new Evidence("FULL_CHAPTER", "CHAPTER", id, Long.toString(chapter.version()), null, null)));
        };
    }

    private static Action searchChapters(ContentRepository repository, Cursor cursor) {
        return (args, ctx) -> {
            long searchEnd = System.nanoTime() + 5_000_000_000L;
            try {
            requireKeys(args, "keyword", "scope", "limit", "cursor");
            String keyword = required(args, "keyword", 1, 128);
            if (keyword.contains("\n") || keyword.contains("\r")) throw new IllegalArgumentException("keyword cannot contain a newline");
            String scope = optional(args, "scope", "BEFORE_TARGET");
            if (!scope.equals("BEFORE_TARGET") && !scope.equals("ALL_CHAPTERS")) throw new IllegalArgumentException("Invalid scope");
            int limit = number(args, "limit", 10, 1, 20);
            List<ToolContext.Chapter> chapters = repository.chapters(ctx.projectId(), searchRemaining(ctx, searchEnd)).stream()
                    .sorted(Comparator.comparingInt(ToolContext.Chapter::order).thenComparing(ToolContext.Chapter::id)).toList();
            if (chapters.size() > 1000) return ToolResult.error("SEARCH_SCOPE_TOO_LARGE", "Search view exceeds 1000 chapters", false);
            if (ctx.chapterView() == null) {
                ContentRepository.ChapterView view = repository.captureChapterView(ctx.runId(), ctx.projectId(),
                        ctx.targetChapterId(), ctx.baseVersion(), ctx.readChapters(), searchRemaining(ctx, searchEnd));
                ctx.setChapterView(view.versions(), view.viewId());
            }
            String query = "search_chapters:" + ctx.chapterViewId() + ":" + keyword + ":" + scope;
            SearchPosition after = cursor.searchPosition(args, ctx, query);
            int targetOrder = chapters.stream().filter(c -> c.id().equals(ctx.targetChapterId())).findFirst()
                    .map(ToolContext.Chapter::order).orElseThrow(() -> new IllegalArgumentException("Target chapter unavailable"));
            ToolContext.Chapter afterChapter = after == null ? null : chapters.stream()
                    .filter(c -> c.id().equals(after.chapterId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("INVALID_SEARCH_CURSOR"));
            Comparator<ToolContext.Chapter> chapterOrder = Comparator.comparingInt(ToolContext.Chapter::order)
                    .thenComparing(ToolContext.Chapter::id);
            List<Map<String, Object>> hits = new ArrayList<>();
            List<Evidence> evidence = new ArrayList<>();
            SearchPosition lastReturned = null;
            boolean hasMore = false;
            scan:
            for (ToolContext.Chapter meta : chapters) {
                searchRemaining(ctx, searchEnd);
                if (scope.equals("BEFORE_TARGET") && meta.order() >= targetOrder) continue;
                if (afterChapter != null && chapterOrder.compare(meta, afterChapter) < 0) continue;
                Long version = ctx.chapterView().get(meta.id());
                if (version == null) continue;
                String content = meta.id().equals(ctx.targetChapterId()) ? ctx.baseContent()
                        : repository.chapterRevision(ctx.projectId(), meta.id(), version, searchRemaining(ctx, searchEnd)).content();
                int line = 0;
                for (String value : content.split("\n", -1)) {
                    searchRemaining(ctx, searchEnd);
                    line++;
                    if (afterChapter != null && meta.id().equals(after.chapterId()) && line < after.lineNumber()) continue;
                    int from = 0, at;
                    while ((at = value.indexOf(keyword, from)) >= 0) {
                        searchRemaining(ctx, searchEnd);
                        from = at + keyword.length();
                        int cp = value.codePointCount(0, at);
                        if (afterChapter != null && meta.id().equals(after.chapterId())
                                && line == after.lineNumber() && cp <= after.matchStart()) continue;
                        if (hits.size() == limit) { hasMore = true; break scan; }
                        Snippet excerpt = snippet(value, at, keyword.length());
                        Map<String, Object> hit = new LinkedHashMap<>();
                        hit.put("chapterId", meta.id()); hit.put("chapterOrder", meta.order()); hit.put("title", meta.title());
                        hit.put("version", version); hit.put("lineNumber", line); hit.put("matchStart", cp);
                        hit.put("matchEnd", cp + keyword.codePointCount(0, keyword.length())); hit.put("snippetStart", excerpt.start());
                        hit.put("snippet", excerpt.text()); hit.put("snippetTruncated", excerpt.truncated());
                        hits.add(hit);
                        evidence.add(new Evidence("SEARCH_SNIPPET", "CHAPTER", meta.id(), Long.toString(version), line, cp));
                        lastReturned = new SearchPosition(meta.id(), line, cp);
                    }
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("keyword", keyword); data.put("scope", scope); data.put("viewId", ctx.chapterViewId());
            data.put("matches", hits); data.put("hasMore", hasMore);
            data.put("nextCursor", hasMore ? cursor.encodeSearchPosition(ctx, query, lastReturned) : null);
            if (jsonSize(data) > 12_000) return ToolResult.error("RESULT_TOO_LARGE", "Search page exceeds 12000 code points", true);
            return ToolResult.ok(data, evidence);
            } catch (ContentRepository.SearchTimeoutException e) {
                return ToolResult.error("SEARCH_TIMEOUT", "Search exceeded its deadline", false);
            }
        };
    }

    private static Action listSettings(ContentRepository repository, Cursor cursor) {
        return (args, ctx) -> {
            requireKeys(args, "type", "cursor", "limit");
            String type = optional(args, "type", "");
            int limit = number(args, "limit", 20, 1, 50);
            String query = "list_settings:" + type;
            int offset = cursor.offset(args, ctx, query);
            List<ContentRepository.Setting> settings = repository.settings(ctx.projectId()).stream()
                    .filter(s -> type.isEmpty() || s.type().equals(type)).sorted(Comparator.comparing(ContentRepository.Setting::id)).toList();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ContentRepository.Setting s : settings.subList(Math.min(offset, settings.size()), Math.min(offset + limit, settings.size())))
                rows.add(Map.of("settingId", s.id(), "type", s.type(), "title", s.title()));
            return page(rows, offset, limit, settings.size(), cursor, ctx, query, List.of());
        };
    }

    private static Action readSettings(ContentRepository repository) {
        return (args, ctx) -> {
            requireKeys(args, "settingIds");
            JsonNode ids = args.get("settingIds");
            if (ids == null || !ids.isArray() || ids.size() < 1 || ids.size() > 5) throw new IllegalArgumentException("settingIds must contain 1-5 IDs");
            Set<String> seen = new HashSet<>();
            Map<String, ContentRepository.Setting> byId = new HashMap<>();
            for (ContentRepository.Setting setting : repository.settings(ctx.projectId())) byId.put(setting.id(), setting);
            List<Map<String, Object>> rows = new ArrayList<>();
            List<Evidence> evidence = new ArrayList<>();
            for (JsonNode node : ids) {
                if (!node.isTextual() || node.asText().isBlank()) throw new IllegalArgumentException("Invalid setting ID");
                String id = node.asText();
                if (!seen.add(id)) throw new IllegalArgumentException("Duplicate setting ID");
                ContentRepository.Setting s = byId.get(id);
                if (s == null) return missing();
                rows.add(Map.of("settingId", id, "type", s.type(), "title", s.title(), "content", s.content()));
                evidence.add(new Evidence("FULL_SETTING", "SETTING", id, sha256(s.title() + "\n" + s.content()), null, null));
            }
            return ToolResult.ok(Map.of("settings", rows), evidence);
        };
    }

    private static Action searchSettings(ContentRepository repository, Cursor cursor) {
        return (args, ctx) -> {
            long searchEnd = System.nanoTime() + 5_000_000_000L;
            try {
            requireKeys(args, "keyword", "type", "limit", "cursor");
            String keyword = required(args, "keyword", 1, 128);
            if (keyword.contains("\n") || keyword.contains("\r")) throw new IllegalArgumentException("keyword cannot contain a newline");
            String type = optional(args, "type", "");
            int limit = number(args, "limit", 5, 1, 20);
            String query = "search_settings:" + keyword + ":" + type;
            int offset = cursor.offset(args, ctx, query);
            List<Map<String, Object>> hits = new ArrayList<>();
            List<Evidence> evidence = new ArrayList<>();
            for (ContentRepository.Setting s : repository.settings(ctx.projectId(), searchRemaining(ctx, searchEnd)).stream().sorted(Comparator.comparing(ContentRepository.Setting::id)).toList()) {
                searchRemaining(ctx, searchEnd);
                if (!type.isEmpty() && !s.type().equals(type)) continue;
                int titleAt = s.title().indexOf(keyword);
                int contentAt = titleAt < 0 ? s.content().indexOf(keyword) : -1;
                if (titleAt < 0 && contentAt < 0) continue;
                String field = titleAt >= 0 ? "TITLE" : "CONTENT";
                String value = titleAt >= 0 ? s.title() : s.content();
                int at = titleAt >= 0 ? titleAt : contentAt;
                int cp = value.codePointCount(0, at);
                hits.add(Map.of("settingId", s.id(), "type", s.type(), "title", s.title(), "matchField", field,
                        "snippet", snippet(value, at, keyword.length()).text(), "codePointOffset", cp));
                evidence.add(new Evidence("SEARCH_SNIPPET", "SETTING", s.id(), sha256(s.title() + "\n" + s.content()), null, cp));
            }
            int end = Math.min(offset + limit, hits.size());
            int start = Math.min(offset, end);
            ToolResult page = page(hits.subList(start, end), offset, limit, hits.size(), cursor, ctx, query, evidence.subList(start, end));
            if (jsonSize(page.data()) > 12_000) return ToolResult.error("RESULT_TOO_LARGE", "Search page exceeds 12000 code points", true);
            return page;
            } catch (ContentRepository.SearchTimeoutException e) {
                return ToolResult.error("SEARCH_TIMEOUT", "Search exceeded its deadline", false);
            }
        };
    }

    private static long searchRemaining(ToolContext ctx, long searchEnd) {
        if (ctx.cancelled()) throw new java.util.concurrent.CancellationException("Search cancelled");
        long own = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(searchEnd - System.nanoTime());
        long remaining = Math.min(own, ctx.remainingMillis());
        if (remaining <= 0) throw new ContentRepository.SearchTimeoutException(null);
        return remaining;
    }

    private static Action propose() {
        return (args, ctx) -> {
            requireKeys(args, "proposedContent", "reason");
            if (!ctx.targetRead()) return ToolResult.error("INSUFFICIENT_CONTEXT", "Read the target chapter first", true);
            if (ctx.candidate() != null) return ToolResult.error("PROPOSAL_ALREADY_EXISTS", "One candidate already exists", false);
            String content = required(args, "proposedContent", 1, 6000);
            String reason = required(args, "reason", 1, 2000);
            if (content.equals(ctx.baseContent())) return ToolResult.error("VALIDATION_ERROR", "Candidate matches base content", true);
            ctx.setCandidate(new ToolContext.Candidate(content, reason));
            return ToolResult.ok(Map.of("accepted", true, "baseVersion", ctx.baseVersion()), List.of());
        };
    }

    private static ToolResult page(List<Map<String, Object>> rows, int offset, int limit, int total,
                                   Cursor cursor, ToolContext ctx, String query, List<Evidence> evidence) {
        boolean more = offset + limit < total;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", rows); data.put("hasMore", more);
        data.put("nextCursor", more ? cursor.encode(ctx, query, offset + limit) : null);
        return ToolResult.ok(data, evidence);
    }
    private static ToolResult missing() { return ToolResult.error("RESOURCE_NOT_FOUND", "Resource unavailable in this project", true); }
    private static void requireKeys(JsonNode args, String... allowed) {
        Set<String> keys = Set.of(allowed);
        args.fieldNames().forEachRemaining(key -> { if (!keys.contains(key)) throw new IllegalArgumentException("Unknown argument: " + key); });
    }
    private static String required(JsonNode args, String name, int min, int max) {
        JsonNode value = args.get(name);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException(name + " must be text");
        String text = value.asText();
        int size = text.codePointCount(0, text.length());
        if (text.isBlank() || size < min || size > max) throw new IllegalArgumentException(name + " length is invalid");
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
        if (!value.isIntegralNumber() || value.asInt() < min || value.asInt() > max) throw new IllegalArgumentException(name + " out of range");
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
        return new Snippet(startCp, line.substring(line.offsetByCodePoints(0, startCp), line.offsetByCodePoints(0, endCp)),
                startCp > 0 || endCp < total);
    }
    private static int codePoints(String text) { return text.codePointCount(0, text.length()); }
    private static int jsonSize(Object value) {
        try { return codePoints(JSON.writeValueAsString(value)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    private record SearchPosition(String chapterId, int lineNumber, int matchStart) {}
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static final class Cursor {
        private final byte[] key;
        Cursor(byte[] key) { this.key = key; }
        String encode(ToolContext ctx, String query, int offset) {
            return encode(ctx, query, Integer.toString(offset));
        }
        String encodeSearchPosition(ToolContext ctx, String query, SearchPosition position) {
            String id = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(position.chapterId().getBytes(StandardCharsets.UTF_8));
            return encode(ctx, query, "search:" + id + ":" + position.lineNumber() + ":" + position.matchStart());
        }
        private String encode(ToolContext ctx, String query, String position) {
            String payload = ctx.runId() + "\n" + query + "\n" + position;
            String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            return raw + "." + sign(raw);
        }
        int offset(JsonNode args, ToolContext ctx, String query) {
            String value = position(args, ctx, query);
            if (value == null) return 0;
            try {
                int offset = Integer.parseInt(value);
                if (offset < 0) throw new IllegalArgumentException("INVALID_SEARCH_CURSOR");
                return offset;
            } catch (IllegalArgumentException e) { throw new IllegalArgumentException("INVALID_SEARCH_CURSOR"); }
        }
        SearchPosition searchPosition(JsonNode args, ToolContext ctx, String query) {
            String value = position(args, ctx, query);
            if (value == null) return null;
            String[] fields = value.split(":", -1);
            if (fields.length != 4 || !fields[0].equals("search")) throw new IllegalArgumentException("INVALID_SEARCH_CURSOR");
            try {
                String id = new String(Base64.getUrlDecoder().decode(fields[1]), StandardCharsets.UTF_8);
                int line = Integer.parseInt(fields[2]);
                int start = Integer.parseInt(fields[3]);
                if (id.isBlank() || line < 1 || start < 0) throw new IllegalArgumentException("INVALID_SEARCH_CURSOR");
                return new SearchPosition(id, line, start);
            } catch (IllegalArgumentException e) { throw new IllegalArgumentException("INVALID_SEARCH_CURSOR"); }
        }
        private String position(JsonNode args, ToolContext ctx, String query) {
            String value = optional(args, "cursor", "");
            if (value.isEmpty()) return null;
            if (value.length() > 4096) throw new IllegalArgumentException("INVALID_SEARCH_CURSOR");
            String[] parts = value.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]).getBytes(StandardCharsets.US_ASCII), parts[1].getBytes(StandardCharsets.US_ASCII)))
                throw new IllegalArgumentException("INVALID_SEARCH_CURSOR");
            try {
                String[] decoded = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8).split("\n", -1);
                if (decoded.length != 3 || !decoded[0].equals(ctx.runId()) || !decoded[1].equals(query)) throw new IllegalArgumentException("INVALID_SEARCH_CURSOR");
                return decoded[2];
            } catch (IllegalArgumentException e) { throw new IllegalArgumentException("INVALID_SEARCH_CURSOR"); }
        }
        private String sign(String raw) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
                return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(raw.getBytes(StandardCharsets.US_ASCII)));
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
    }
}
