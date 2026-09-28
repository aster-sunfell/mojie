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

    @Test void readSettingsRejectsMixedScopeAsOneResult() {
        AgentTool tool = ContentTools.all(content, KEY).stream().filter(t -> t.spec().name().equals("read_settings")).findFirst().orElseThrow();
        ToolContext ctx = input().context();
        AgentTool.ToolResult result = tool.execute(node("{\"settingIds\":[\"s1\",\"foreign\"]}"), ctx);
        assertFalse(result.success());
        assertTrue(result.evidence().isEmpty());
        assertEquals("RESOURCE_NOT_FOUND", result.error().code());
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
        public List<ToolContext.Chapter> chapters(String projectId) {
            return List.of(new ToolContext.Chapter("prior", "前文", 1, 1, "林舟不会游泳，也不想游泳"),
                    new ToolContext.Chapter("target", "目标", 2, 2, "当前版本"));
        }
        public ToolContext.Chapter chapterRevision(String projectId, String id, long version) {
            return chapters(projectId).stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
        }
        public ChapterView captureChapterView(String runId, String projectId, String targetId, long baseVersion,
                                              Map<String, ToolContext.Chapter> alreadyRead) {
            return new ChapterView("view-" + runId, Map.of("prior", 1L, "target", baseVersion));
        }
        public List<Setting> settings(String projectId) { return List.of(new Setting("s1", "CHARACTER", "林舟", "怕水")); }
    }
}
