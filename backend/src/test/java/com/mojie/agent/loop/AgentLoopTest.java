package com.mojie.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.agent.model.ModelGateway;
import com.mojie.agent.model.ModelGateway.*;
import com.mojie.agent.tool.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class AgentLoopTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes();
    private final RecordingTrace trace = new RecordingTrace();
    private final FakeContent content = new FakeContent();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    @Test void mixedTextAndSequentialToolsDoNotPublishIntermediateText() {
        Script model = new Script(List.of(
                response("premature answer", List.of(call("r", "read_chapter", "{\"chapterId\":\"target\"}"),
                        call("p", "propose_edit", "{\"proposedContent\":\"新版😀\",\"reason\":\"修正\"}")), FinishReason.TOOL_CALLS),
                response("最终分析", List.of(), FinishReason.FINAL)));
        LoopOutcome outcome = loop(model, LoopLimits.defaults()).run(input());
        assertInstanceOf(LoopOutcome.Completed.class, outcome);
        LoopOutcome.Completed completed = (LoopOutcome.Completed) outcome;
        assertEquals("最终分析", completed.analysis());
        assertEquals("新版😀", completed.candidate().proposedContent());
        assertTrue(((TextPart) model.requests.getFirst().messages().getFirst().content().getFirst()).text()
                .contains("目标章节 ID: target"));
        assertEquals(List.of("read_chapter", "propose_edit"), trace.startedTools);
        assertEquals(List.of("r", "p"), model.requests.get(1).messages().get(2).replies().stream().map(ToolReply::callId).toList());
        assertEquals(List.of(1L, 2L, 3L, 4L), trace.modelInputs.get(1));
    }

    @Test void differentToolPathCanAnalyzeWithoutCandidate() {
        Script model = new Script(List.of(
                response("", List.of(call("s", "search_settings", "{\"keyword\":\"怕水\"}")), FinishReason.TOOL_CALLS),
                response("", List.of(call("f", "read_settings", "{\"settingIds\":[\"s1\"]}"),
                        call("q", "search_chapters", "{\"keyword\":\"游泳\"}")), FinishReason.TOOL_CALLS),
                response("", List.of(call("r", "read_chapter", "{\"chapterId\":\"target\"}")), FinishReason.TOOL_CALLS),
                response("无须修改", List.of(), FinishReason.FINAL)));
        LoopOutcome.Completed completed = (LoopOutcome.Completed) loop(model, LoopLimits.defaults()).run(input());
        assertNull(completed.candidate());
        assertEquals(List.of("search_settings", "read_settings", "search_chapters", "read_chapter"), trace.startedTools);
        assertEquals(4, model.requests.size());
    }

    @Test void opaqueProviderBlocksKeepTheirOrderAcrossTurns() {
        OpaquePart opaque = new OpaquePart(node("{\"type\":\"thinking\",\"signature\":\"signed\"}"));
        ToolCall read = call("r", "read_chapter", "{\"chapterId\":\"target\"}");
        Response first = new Response(List.of(opaque, new TextPart("中间文本"), read), FinishReason.TOOL_CALLS,
                "request", "tool_use", null);
        Script model = new Script(List.of(first, response("结论", List.of(), FinishReason.FINAL)));
        assertInstanceOf(LoopOutcome.Completed.class, loop(model, LoopLimits.defaults()).run(input()));
        assertEquals(List.of(opaque, new TextPart("中间文本"), read), model.requests.get(1).messages().get(1).content());
    }

    @Test void missingTargetReadFailsEvenWithSearchHit() {
        Script model = new Script(List.of(response("结论", List.of(), FinishReason.FINAL)));
        assertEquals("INSUFFICIENT_CONTEXT", ((LoopOutcome.Failed) loop(model, LoopLimits.defaults()).run(input())).code());
    }

    @Test void duplicateIdsRejectWholeBatchBeforeToolsRun() {
        Script model = new Script(List.of(response("", List.of(call("x", "read_chapter", "{\"chapterId\":\"target\"}"),
                call("x", "list_settings", "{}")), FinishReason.TOOL_CALLS)));
        assertEquals("INVALID_MODEL_RESPONSE", ((LoopOutcome.Failed) loop(model, LoopLimits.defaults()).run(input())).code());
        assertTrue(trace.startedTools.isEmpty());
    }

    @Test void conflictingFinishReasonRejectsWholeBatch() {
        Script model = new Script(List.of(response("analysis", List.of(call("r", "read_chapter", "{\"chapterId\":\"target\"}")), FinishReason.FINAL)));
        assertEquals("INVALID_MODEL_RESPONSE", ((LoopOutcome.Failed) loop(model, LoopLimits.defaults()).run(input())).code());
        assertTrue(trace.startedTools.isEmpty());
    }

    @Test void fullContextBudgetIsCheckedBeforeAProviderAttempt() {
        Script model = new Script(List.of());
        LoopLimits limits = new LoopLimits(8, 12, 30_000, 10, 60_000);
        assertEquals("LIMIT_EXCEEDED", ((LoopOutcome.Failed) loop(model, limits).run(input())).code());
        assertTrue(model.requests.isEmpty());
        assertTrue(trace.modelInputs.isEmpty());
    }

    @Test void unknownToolAndBadArgumentsAreReturnedAndCounted() {
        Script model = new Script(List.of(
                response("", List.of(call("u", "unknown", "{}"), call("b", "read_chapter", "[]")), FinishReason.TOOL_CALLS),
                response("", List.of(call("r", "read_chapter", "{\"chapterId\":\"target\"}")), FinishReason.TOOL_CALLS),
                response("ok", List.of(), FinishReason.FINAL)));
        LoopOutcome.Completed result = (LoopOutcome.Completed) loop(model, LoopLimits.defaults()).run(input());
        assertEquals(3, result.toolCalls());
        assertEquals("UNKNOWN_TOOL", ((Map<?, ?>) model.requests.get(1).messages().get(2).replies().get(0).result().get("error")).get("code"));
        assertEquals("VALIDATION_ERROR", ((Map<?, ?>) model.requests.get(1).messages().get(2).replies().get(1).result().get("error")).get("code"));
    }

    @Test void eighthModelFinalSucceedsButToolsAreAborted() {
        List<Response> turns = new ArrayList<>();
        turns.add(response("", List.of(call("read", "read_chapter", "{\"chapterId\":\"target\"}")), FinishReason.TOOL_CALLS));
        for (int i = 2; i <= 7; i++) turns.add(response("", List.of(call("l" + i, "list_chapters", "{}")), FinishReason.TOOL_CALLS));
        turns.add(response("分析", List.of(), FinishReason.FINAL));
        assertInstanceOf(LoopOutcome.Completed.class, loop(new Script(turns), LoopLimits.defaults()).run(input()));
        turns.set(7, response("", List.of(call("extra", "list_settings", "{}")), FinishReason.TOOL_CALLS));
        RecordingTrace secondTrace = new RecordingTrace();
        LoopOutcome failed = new AgentLoop(new Script(turns), ContentTools.all(content, KEY), secondTrace,
                LoopLimits.defaults(), Clock.systemUTC(), cancelled::get).run(input());
        assertEquals("LIMIT_EXCEEDED", ((LoopOutcome.Failed) failed).code());
        assertEquals(List.of("extra"), secondTrace.aborted);
        assertEquals(7, secondTrace.startedTools.size());
    }

    @Test void insufficientBatchBudgetAbortsAllCalls() {
        Script model = new Script(List.of(
                response("", List.of(call("r", "read_chapter", "{\"chapterId\":\"target\"}")), FinishReason.TOOL_CALLS),
                response("", List.of(call("a", "list_chapters", "{}"), call("b", "list_settings", "{}")), FinishReason.TOOL_CALLS)));
        LoopLimits limits = new LoopLimits(8, 2, 30_000, 200_000, 60_000);
        assertEquals("LIMIT_EXCEEDED", ((LoopOutcome.Failed) loop(model, limits).run(input())).code());
        assertEquals(List.of("read_chapter"), trace.startedTools);
        assertEquals(List.of("a", "b"), trace.aborted);
    }

    @Test void candidateIsNeverReturnedOnLaterFailureOrCancel() {
        List<Response> turns = List.of(response("", List.of(call("r", "read_chapter", "{\"chapterId\":\"target\"}"),
                call("p", "propose_edit", "{\"proposedContent\":\"new\",\"reason\":\"why\"}")), FinishReason.TOOL_CALLS),
                response("", List.of(), FinishReason.FINAL));
        assertInstanceOf(LoopOutcome.Failed.class, loop(new Script(turns), LoopLimits.defaults()).run(input()));
        RecordingTrace other = new RecordingTrace();
        Script model = new Script(turns);
        model.afterCall = i -> { if (i == 1) cancelled.set(true); };
        assertInstanceOf(LoopOutcome.Cancelled.class, new AgentLoop(model, ContentTools.all(content, KEY), other,
                LoopLimits.defaults(), Clock.systemUTC(), cancelled::get).run(input()));
    }

    @Test void toolResultTextUsesUnicodeCodePointsAndNoHistoricalDoubleCount() {
        Script model = new Script(List.of(
                response("", List.of(call("r", "read_chapter", "{\"chapterId\":\"target\"}")), FinishReason.TOOL_CALLS),
                response("", List.of(call("r2", "read_chapter", "{\"chapterId\":\"target\"}")), FinishReason.TOOL_CALLS),
                response("分析", List.of(), FinishReason.FINAL)));
        LoopOutcome.Completed done = (LoopOutcome.Completed) loop(model, LoopLimits.defaults()).run(input());
        int each = trace.wireResults.get(0).codePointCount(0, trace.wireResults.get(0).length());
        assertEquals(each * 2, done.toolTextCodePoints());
        assertEquals(2, trace.wireResults.size());
    }

    @Test void resultSavedButNotSentIsNotACompletedRun() {
        Script model = new Script(List.of(response("", List.of(call("r", "read_chapter", "{\"chapterId\":\"target\"}")), FinishReason.TOOL_CALLS)));
        trace.afterToolFinished = () -> cancelled.set(true);
        assertInstanceOf(LoopOutcome.Cancelled.class, loop(model, LoopLimits.defaults()).run(input()));
        assertEquals(1, trace.wireResults.size());
        assertEquals(1, trace.modelInputs.size());
    }

    @Test void persistenceFailureStopsBeforeNextModelCall() {
        Script model = new Script(List.of(response("", List.of(call("r", "read_chapter", "{\"chapterId\":\"target\"}")), FinishReason.TOOL_CALLS)));
        trace.afterToolFinished = () -> { throw new IllegalStateException("database unavailable"); };
        LoopOutcome outcome = loop(model, LoopLimits.defaults()).run(input());
        assertEquals("PERSISTENCE_ERROR", ((LoopOutcome.Failed) outcome).code());
        assertEquals(1, model.requests.size());
    }

    @Test void exactlyTwelfthToolMayBeFollowedByFinalAnalysis() {
        List<Response> turns = new ArrayList<>();
        turns.add(response("", List.of(call("read", "read_chapter", "{\"chapterId\":\"target\"}")), FinishReason.TOOL_CALLS));
        for (int round = 2; round <= 6; round++) turns.add(response("", List.of(
                call("a" + round, "list_chapters", "{}"), call("b" + round, "list_settings", "{}")), FinishReason.TOOL_CALLS));
        turns.add(response("", List.of(call("last", "list_chapters", "{}")), FinishReason.TOOL_CALLS));
        turns.add(response("分析", List.of(), FinishReason.FINAL));
        LoopOutcome.Completed result = (LoopOutcome.Completed) loop(new Script(turns), LoopLimits.defaults()).run(input());
        assertEquals(12, result.toolCalls());
        assertEquals(8, result.modelCalls());
    }

    @Test void exactToolTextLimitAllowsFinalAndNextCharacterFails() {
        List<Response> turns = List.of(
                response("", List.of(call("r", "read_chapter", "{\"chapterId\":\"target\"}")), FinishReason.TOOL_CALLS),
                response("分析", List.of(), FinishReason.FINAL));
        LoopOutcome.Completed baseline = (LoopOutcome.Completed) loop(new Script(turns), LoopLimits.defaults()).run(input());
        int exact = baseline.toolTextCodePoints();
        RecordingTrace exactTrace = new RecordingTrace();
        LoopOutcome.Completed exactResult = (LoopOutcome.Completed) new AgentLoop(new Script(turns), ContentTools.all(content, KEY),
                exactTrace, new LoopLimits(8, 12, exact, 200_000, 60_000), Clock.systemUTC(), cancelled::get).run(input());
        assertEquals(exact, exactResult.toolTextCodePoints());
        RecordingTrace shortTrace = new RecordingTrace();
        LoopOutcome tooShort = new AgentLoop(new Script(turns), ContentTools.all(content, KEY), shortTrace,
                new LoopLimits(8, 12, exact - 1, 200_000, 60_000), Clock.systemUTC(), cancelled::get).run(input());
        assertEquals("LIMIT_EXCEEDED", ((LoopOutcome.Failed) tooShort).code());
        assertTrue(shortTrace.wireResults.getFirst().contains("RESULT_TOO_LARGE"));
    }

    @Test void chapterSearchReportsCodePointPositionsAndRejectsForeignCursor() {
        AgentTool search = ContentTools.all(content, KEY).stream().filter(t -> t.spec().name().equals("search_chapters")).findFirst().orElseThrow();
        ToolContext first = input().context();
        AgentTool.ToolResult result = search.execute(node("{\"keyword\":\"游泳\",\"scope\":\"ALL_CHAPTERS\",\"limit\":1}"), first);
        assertTrue(result.success());
        Map<?, ?> data = (Map<?, ?>) result.data();
        assertEquals(1, ((List<?>) data.get("matches")).size());
        assertEquals(4, ((Map<?, ?>) ((List<?>) data.get("matches")).getFirst()).get("matchStart"));
        assertEquals("SEARCH_SNIPPET", result.evidence().getFirst().kind());
        ToolContext other = input().context();
        String cursor = (String) data.get("nextCursor");
        if (cursor != null) {
            assertThrows(IllegalArgumentException.class, () -> search.execute(node("{\"keyword\":\"游泳\",\"scope\":\"ALL_CHAPTERS\",\"cursor\":\"" + cursor + "\"}"), other));
        }
    }

    @Test void chapterSearchKeepsNonLfCharactersAndReplaysLiteralPages() {
        FakeContent repository = new FakeContent("😀甲\u2028乙游泳游泳\n%_\\.* 游泳");
        AgentTool search = ContentTools.all(repository, KEY).stream()
                .filter(tool -> tool.spec().name().equals("search_chapters")).findFirst().orElseThrow();
        ToolContext context = new ToolContext(UUID.randomUUID().toString(), "p", "target", 2, "目标正文");
        AgentTool.ToolResult first = search.execute(JSON.valueToTree(Map.of("keyword", "游泳", "limit", 1)), context);
        assertTrue(first.success());
        Map<?,?> firstData = (Map<?,?>) first.data();
        Map<?,?> firstHit = (Map<?,?>) ((List<?>) firstData.get("matches")).getFirst();
        assertEquals(1, firstHit.get("lineNumber"));
        assertEquals(4, firstHit.get("matchStart"));
        assertEquals(6, firstHit.get("matchEnd"));
        assertEquals(true, firstData.get("hasMore"));

        String cursor = (String) firstData.get("nextCursor");
        var nextArgs = JSON.valueToTree(Map.of("keyword", "游泳", "limit", 2, "cursor", cursor));
        Map<?,?> next = (Map<?,?>) search.execute(nextArgs, context).data();
        List<?> matches = (List<?>) next.get("matches");
        assertEquals(2, matches.size());
        assertEquals(1, ((Map<?,?>) matches.get(0)).get("lineNumber"));
        assertEquals(6, ((Map<?,?>) matches.get(0)).get("matchStart"));
        assertEquals(2, ((Map<?,?>) matches.get(1)).get("lineNumber"));
        assertEquals(6, ((Map<?,?>) matches.get(1)).get("matchStart"));
        assertEquals(false, next.get("hasMore"));
        assertEquals(next, search.execute(nextArgs, context).data());

        Map<?,?> literal = (Map<?,?>) search.execute(JSON.valueToTree(Map.of("keyword", "%_\\.*")), context).data();
        assertEquals(1, ((List<?>) literal.get("matches")).size());
        assertEquals(2, ((Map<?,?>) ((List<?>) literal.get("matches")).getFirst()).get("lineNumber"));
        assertThrows(IllegalArgumentException.class, () -> search.execute(
                JSON.valueToTree(Map.of("keyword", "泳", "cursor", cursor)), context));
        assertThrows(IllegalArgumentException.class, () -> search.execute(
                JSON.valueToTree(Map.of("keyword", "游泳\n")), context));
    }

    @Test void denseChapterSearchPagesByLastMatchWithoutCollectingAllMatches() {
        FakeContent repository = new FakeContent("游".repeat(6000));
        AgentTool search = ContentTools.all(repository, KEY).stream()
                .filter(tool -> tool.spec().name().equals("search_chapters")).findFirst().orElseThrow();
        ToolContext context = input().context();
        AgentTool.ToolResult first = search.execute(JSON.valueToTree(Map.of("keyword", "游", "limit", 20)), context);
        assertTrue(first.success());
        Map<?,?> firstData = (Map<?,?>) first.data();
        List<?> firstMatches = (List<?>) firstData.get("matches");
        assertEquals(20, firstMatches.size());
        assertEquals(20, first.evidence().size());
        assertEquals(0, ((Map<?,?>) firstMatches.getFirst()).get("matchStart"));
        assertEquals(19, ((Map<?,?>) firstMatches.getLast()).get("matchStart"));
        assertEquals(true, firstData.get("hasMore"));
        String cursor = (String) firstData.get("nextCursor");
        String decoded = new String(Base64.getUrlDecoder().decode(cursor.split("\\.")[0]));
        assertTrue(decoded.contains("\nsearch:"));
        assertTrue(decoded.endsWith(":1:19"));

        JsonNode nextArgs = JSON.valueToTree(Map.of("keyword", "游", "limit", 7, "cursor", cursor));
        AgentTool.ToolResult second = search.execute(nextArgs, context);
        List<?> secondMatches = (List<?>) ((Map<?,?>) second.data()).get("matches");
        assertEquals(7, secondMatches.size());
        assertEquals(20, ((Map<?,?>) secondMatches.getFirst()).get("matchStart"));
        assertEquals(26, ((Map<?,?>) secondMatches.getLast()).get("matchStart"));
        assertEquals(second.data(), search.execute(nextArgs, context).data());
    }

    @Test void chapterSearchCursorContinuesAcrossChapterBoundary() {
        ContentRepository repository = new ContentRepository() {
            public List<ChapterMetadata> chapters(String projectId) {
                return List.of(new ChapterMetadata("a", "第一章", 1, 1),
                        new ChapterMetadata("b", "第二章", 2, 1),
                        new ChapterMetadata("target", "目标章", 3, 1));
            }
            public ToolContext.Chapter chapterRevision(String projectId, String id, long version) {
                return chapters(projectId).stream().filter(chapter -> chapter.id().equals(id))
                        .map(chapter -> new ToolContext.Chapter(id, chapter.title(), chapter.order(), version, "甲"))
                        .findFirst().orElse(null);
            }
            public ChapterView captureChapterView(String runId, String projectId, String targetId, long baseVersion,
                                                  Map<String, ToolContext.Chapter> alreadyRead) {
                return new ChapterView("view-" + runId, Map.of("a", 1L, "b", 1L, "target", baseVersion));
            }
            public List<Setting> settings(String projectId) { return List.of(); }
        };
        AgentTool search = ContentTools.all(repository, KEY).stream()
                .filter(tool -> tool.spec().name().equals("search_chapters")).findFirst().orElseThrow();
        ToolContext context = input().context();
        Map<?,?> first = (Map<?,?>) search.execute(JSON.valueToTree(Map.of("keyword", "甲", "limit", 1)), context).data();
        assertEquals("a", ((Map<?,?>) ((List<?>) first.get("matches")).getFirst()).get("chapterId"));
        assertEquals(true, first.get("hasMore"));
        Map<?,?> second = (Map<?,?>) search.execute(JSON.valueToTree(Map.of(
                "keyword", "甲", "limit", 1, "cursor", first.get("nextCursor"))), context).data();
        assertEquals("b", ((Map<?,?>) ((List<?>) second.get("matches")).getFirst()).get("chapterId"));
        assertEquals(false, second.get("hasMore"));
        assertNull(second.get("nextCursor"));
    }

    @Test void chapterSearchReadsPinnedRevisionsInBoundedBatches() {
        List<Integer> batchSizes = new ArrayList<>();
        ContentRepository repository = new ContentRepository() {
            public List<ChapterMetadata> chapters(String projectId) {
                List<ChapterMetadata> result = new ArrayList<>();
                for (int i = 1; i <= 40; i++) result.add(new ChapterMetadata("c" + i, "章节" + i, i, 1));
                result.add(new ChapterMetadata("target", "目标", 41, 1));
                return result;
            }
            public ToolContext.Chapter chapterRevision(String projectId, String id, long version) {
                throw new AssertionError("Search should use batch reads");
            }
            public List<ToolContext.Chapter> chapterRevisions(String projectId, List<ChapterVersion> chapters, long timeoutMillis) {
                batchSizes.add(chapters.size());
                return chapters.stream().map(chapter -> new ToolContext.Chapter(
                        chapter.id(), chapter.id(), 1, chapter.version(), "无关正文")).toList();
            }
            public ChapterView captureChapterView(String runId, String projectId, String targetId, long baseVersion,
                                                  Map<String, ToolContext.Chapter> alreadyRead) {
                Map<String, Long> versions = new HashMap<>();
                for (ChapterMetadata chapter : chapters(projectId)) versions.put(chapter.id(), chapter.version());
                return new ChapterView("view-" + runId, versions);
            }
            public List<Setting> settings(String projectId) { return List.of(); }
        };
        AgentTool search = ContentTools.all(repository, KEY).stream()
                .filter(tool -> tool.spec().name().equals("search_chapters")).findFirst().orElseThrow();
        AgentTool.ToolResult result = search.execute(JSON.valueToTree(Map.of("keyword", "不存在")), input().context());
        assertTrue(result.success());
        assertEquals(List.of(32, 8), batchSizes);
        assertEquals(List.of(), ((Map<?,?>) result.data()).get("matches"));
    }

    @Test void timedOutLaterSearchBatchDiscardsEarlierMatches() {
        ContentRepository repository = new ContentRepository() {
            public List<ChapterMetadata> chapters(String projectId) {
                List<ChapterMetadata> result = new ArrayList<>();
                for (int i = 1; i <= 33; i++) result.add(new ChapterMetadata("c" + i, "章节" + i, i, 1));
                result.add(new ChapterMetadata("target", "目标", 34, 1));
                return result;
            }
            public ToolContext.Chapter chapterRevision(String projectId, String id, long version) {
                throw new AssertionError("Search should use batch reads");
            }
            public List<ToolContext.Chapter> chapterRevisions(String projectId, List<ChapterVersion> chapters, long timeoutMillis) {
                if (chapters.getFirst().id().equals("c33")) throw new SearchTimeoutException(null);
                return chapters.stream().map(chapter -> new ToolContext.Chapter(chapter.id(), chapter.id(), 1,
                        chapter.version(), chapter.id().equals("c1") ? "矛盾" : "无关正文")).toList();
            }
            public ChapterView captureChapterView(String runId, String projectId, String targetId, long baseVersion,
                                                  Map<String, ToolContext.Chapter> alreadyRead) {
                Map<String, Long> versions = new HashMap<>();
                for (ChapterMetadata chapter : chapters(projectId)) versions.put(chapter.id(), chapter.version());
                return new ChapterView("view-" + runId, versions);
            }
            public List<Setting> settings(String projectId) { return List.of(); }
        };
        AgentTool search = ContentTools.all(repository, KEY).stream()
                .filter(tool -> tool.spec().name().equals("search_chapters")).findFirst().orElseThrow();
        AgentTool.ToolResult result = search.execute(JSON.valueToTree(Map.of("keyword", "矛盾")), input().context());
        assertFalse(result.success());
        assertEquals("SEARCH_TIMEOUT", result.error().code());
        assertFalse(result.error().retryableByModel());
        assertNull(result.data());
        assertTrue(result.evidence().isEmpty());
    }

    @Test void cancellationDuringChapterScanStopsWithoutReturningPartialEvidence() {
        AtomicBoolean stopped = new AtomicBoolean();
        FakeContent base = new FakeContent("林舟不会游泳。\n林舟再次说不会游泳。");
        ContentRepository repository = new ContentRepository() {
            public List<ChapterMetadata> chapters(String projectId) { return base.chapters(projectId); }
            public ToolContext.Chapter chapterRevision(String projectId, String id, long version) {
                ToolContext.Chapter chapter = base.chapterRevision(projectId, id, version);
                stopped.set(true);
                return chapter;
            }
            public ChapterView captureChapterView(String runId, String projectId, String targetId, long baseVersion,
                                                  Map<String, ToolContext.Chapter> alreadyRead) {
                return base.captureChapterView(runId, projectId, targetId, baseVersion, alreadyRead);
            }
            public List<Setting> settings(String projectId) { return base.settings(projectId); }
        };
        Script model = new Script(List.of(response("", List.of(call("search", "search_chapters",
                "{\"keyword\":\"游泳\"}")), FinishReason.TOOL_CALLS)));
        RecordingTrace scanTrace = new RecordingTrace();
        LoopOutcome outcome = new AgentLoop(model, ContentTools.all(repository, KEY), scanTrace,
                LoopLimits.defaults(), Clock.systemUTC(), stopped::get).run(input());
        assertInstanceOf(LoopOutcome.Cancelled.class, outcome);
        assertEquals(List.of("search_chapters"), scanTrace.startedTools);
        assertTrue(scanTrace.wireResults.isEmpty());
        assertEquals(1, model.requests.size());
    }

    @Test void longLineSnippetKeepsOriginalCodePointPositionAndFullRead() {
        String longLine = "😀" + "甲".repeat(5997) + "游泳";
        FakeContent repository = new FakeContent(longLine);
        List<AgentTool> tools = ContentTools.all(repository, KEY);
        ToolContext context = input().context();
        AgentTool search = tools.stream().filter(tool -> tool.spec().name().equals("search_chapters"))
                .findFirst().orElseThrow();
        AgentTool.ToolResult found = search.execute(JSON.valueToTree(Map.of("keyword", "游泳")), context);
        assertTrue(found.success());
        Map<?,?> match = (Map<?,?>) ((List<?>) ((Map<?,?>) found.data()).get("matches")).getFirst();
        assertEquals(5998, match.get("matchStart"));
        assertEquals(6000, match.get("matchEnd"));
        assertEquals(5680, match.get("snippetStart"));
        String snippet = (String) match.get("snippet");
        assertEquals(320, snippet.codePointCount(0, snippet.length()));
        assertTrue(snippet.endsWith("游泳"));
        assertEquals(true, match.get("snippetTruncated"));

        AgentTool read = tools.stream().filter(tool -> tool.spec().name().equals("read_chapter"))
                .findFirst().orElseThrow();
        AgentTool.ToolResult full = read.execute(JSON.valueToTree(Map.of("chapterId", "prior")), context);
        assertTrue(full.success());
        assertEquals(longLine, ((Map<?,?>) full.data()).get("content"));
        assertEquals("FULL_CHAPTER", full.evidence().getFirst().kind());
    }

    @Test void readSettingsRejectsMixedScopeAsOneResult() {
        AgentTool tool = ContentTools.all(content, KEY).stream().filter(t -> t.spec().name().equals("read_settings")).findFirst().orElseThrow();
        ToolContext ctx = input().context();
        AgentTool.ToolResult result = tool.execute(node("{\"settingIds\":[\"s1\",\"foreign\"]}"), ctx);
        assertFalse(result.success());
        assertTrue(result.evidence().isEmpty());
        assertEquals("RESOURCE_NOT_FOUND", result.error().code());
    }

    @Test void settingSearchPagesByLastIdWithDifferentNextLimit() {
        FakeContent repository = new FakeContent("前文", List.of(
                new ContentRepository.Setting("s1", "CHARACTER", "游泳", "标题命中"),
                new ContentRepository.Setting("s2", "CHARACTER", "人物", "会游泳"),
                new ContentRepository.Setting("s3", "WORLD", "游泳池", "地点")));
        AgentTool search = ContentTools.all(repository, KEY).stream()
                .filter(tool -> tool.spec().name().equals("search_settings")).findFirst().orElseThrow();
        ToolContext context = input().context();
        AgentTool.ToolResult first = search.execute(JSON.valueToTree(Map.of("keyword", "游泳", "limit", 1)), context);
        assertTrue(first.success());
        Map<?,?> firstData = (Map<?,?>) first.data();
        assertEquals("s1", ((Map<?,?>) ((List<?>) firstData.get("items")).getFirst()).get("settingId"));
        assertEquals("TITLE", ((Map<?,?>) ((List<?>) firstData.get("items")).getFirst()).get("matchField"));
        assertEquals(1, first.evidence().size());
        assertEquals(true, firstData.get("hasMore"));
        String cursor = (String) firstData.get("nextCursor");
        assertTrue(new String(Base64.getUrlDecoder().decode(cursor.split("\\.")[0])).contains("\nsetting:"));

        JsonNode nextArgs = JSON.valueToTree(Map.of("keyword", "游泳", "limit", 2, "cursor", cursor));
        Map<?,?> second = (Map<?,?>) search.execute(nextArgs, context).data();
        assertEquals(List.of("s2", "s3"), ((List<?>) second.get("items")).stream()
                .map(item -> ((Map<?,?>) item).get("settingId")).toList());
        assertEquals(false, second.get("hasMore"));
        assertNull(second.get("nextCursor"));
        assertEquals(second, search.execute(nextArgs, context).data());
        assertThrows(IllegalArgumentException.class, () -> search.execute(
                JSON.valueToTree(Map.of("keyword", "人物", "cursor", cursor)), context));
    }

    private AgentLoop loop(Script model, LoopLimits limits) {
        return new AgentLoop(model, ContentTools.all(content, KEY), trace, limits, Clock.systemUTC(), cancelled::get);
    }
    private LoopInput input() { return new LoopInput(new ToolContext(UUID.randomUUID().toString(), "p", "target", 2, "旧章😀"),
            "检查设定", "系统约束", "scripted", 100, Instant.now().plusSeconds(180)); }
    private static JsonNode node(String json) { try { return JSON.readTree(json); } catch (Exception e) { throw new AssertionError(e); } }
    private static ToolCall call(String id, String name, String args) { return new ToolCall(id, name, node(args)); }
    private static Response response(String text, List<ToolCall> calls, FinishReason reason) {
        return new Response(text, calls, reason, "request", reason.name(), null);
    }

    static final class Script implements ModelGateway {
        final List<Response> turns;
        final List<Request> requests = new ArrayList<>();
        Consumer<Integer> afterCall = ignored -> {};
        Script(List<Response> turns) { this.turns = turns; }
        public Response call(Request request) {
            requests.add(request);
            int count = requests.size();
            afterCall.accept(count);
            return turns.get(count - 1);
        }
    }
    static final class RecordingTrace implements LoopTrace {
        long nextSeq = 1;
        final List<List<Long>> modelInputs = new ArrayList<>();
        final List<String> startedTools = new ArrayList<>(), aborted = new ArrayList<>(), wireResults = new ArrayList<>();
        Runnable afterToolFinished = () -> {};
        public void modelStarted(String runId, int index, List<Long> input) { modelInputs.add(input); }
        public long modelFinished(String runId, int index, Response response) { return ++nextSeq; }
        public long modelFailed(String runId, int index, String code, String detail) { return ++nextSeq; }
        public String toolStarted(String runId, int index, ToolCall call) { startedTools.add(call.name()); return UUID.randomUUID().toString(); }
        public long toolFinished(String runId, int index, ToolCall call, String executionId,
                                 AgentTool.ToolResult result, String json, int cumulative, boolean returnedToModel) {
            wireResults.add(json); afterToolFinished.run(); return returnedToModel ? ++nextSeq : -1;
        }
        public void toolAborted(String runId, int index, ToolCall call, String reason) { aborted.add(call.id()); }
    }
    static final class FakeContent implements ContentRepository {
        private final String priorContent;
        private final List<Setting> settingRows;
        FakeContent() { this("林舟不会游泳，也不想游泳"); }
        FakeContent(String priorContent) { this(priorContent, List.of(new Setting("s1", "CHARACTER", "林舟", "怕水"))); }
        FakeContent(String priorContent, List<Setting> settingRows) {
            this.priorContent = priorContent;
            this.settingRows = settingRows;
        }
        public List<ChapterMetadata> chapters(String projectId) {
            return List.of(new ChapterMetadata("prior", "前文", 1, 1),
                    new ChapterMetadata("target", "目标", 2, 2));
        }
        public ToolContext.Chapter chapterRevision(String projectId, String id, long version) {
            return chapters(projectId).stream().filter(c -> c.id().equals(id))
                    .map(c -> new ToolContext.Chapter(c.id(), c.title(), c.order(), version,
                            c.id().equals("prior") ? priorContent : "当前版本"))
                    .findFirst().orElse(null);
        }
        public ChapterView captureChapterView(String runId, String projectId, String targetId, long baseVersion,
                                              Map<String, ToolContext.Chapter> alreadyRead) {
            return new ChapterView("view-" + runId, Map.of("prior", 1L, "target", baseVersion));
        }
        public List<Setting> settings(String projectId) { return settingRows; }
    }
}
