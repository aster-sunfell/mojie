package com.mojie.agent.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.agent.loop.*;
import com.mojie.agent.model.ModelGateway;
import com.mojie.agent.run.infra.*;
import com.mojie.agent.tool.*;
import com.mojie.agent.tool.infra.JdbcContentRepository;
import com.mojie.agent.trace.infra.JdbcLoopTrace;
import org.flywaydb.core.Flyway;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;

/** Single-process, bounded run execution. Database state remains authoritative. */
public final class RunScheduler implements AutoCloseable {
    private enum Phase { REGISTERED, STARTED, CANCELLED_BEFORE_START, EXITED }
    private final DataSource dataSource;
    private final ModelGateway model;
    private final Clock clock;
    private final UUID processId = UUID.randomUUID();
    private final JdbcRunAdmission admission;
    private final JdbcRunTerminalStore terminal;
    private final JdbcRunLifecycle lifecycle;
    private final JdbcRunReader reader;
    private final JdbcContentRepository content;
    private final JdbcLoopTrace trace;
    private final List<AgentTool> tools;
    private final String systemPrompt, modelId, toolSchemaJson, modelApiPath;
    private final int maxOutputTokens, contextWindowTokens;
    private final Semaphore permits = new Semaphore(10);
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentMap<String, RunHandle> handles = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PendingTerminal> pendingTerminals = new ConcurrentHashMap<>();
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicReference<RuntimeException> lastSweepFailure = new AtomicReference<>();
    private record PendingTerminal(String projectId, String runId) {}

    public RunScheduler(DataSource dataSource, ModelGateway model, Clock clock, String systemPrompt,
                        String modelId, int maxOutputTokens, byte[] cursorKey) {
        this(dataSource, model, clock, systemPrompt, modelId, maxOutputTokens, cursorKey,
                com.mojie.agent.model.ModelApiConfig.MESSAGES);
    }

    public RunScheduler(DataSource dataSource, ModelGateway model, Clock clock, String systemPrompt,
                        String modelId, int maxOutputTokens, byte[] cursorKey, String modelApiPath) {
        this(dataSource, model, clock, systemPrompt, modelId, maxOutputTokens, cursorKey, modelApiPath, 1_000_000);
    }

    public RunScheduler(DataSource dataSource, ModelGateway model, Clock clock, String systemPrompt,
                        String modelId, int maxOutputTokens, byte[] cursorKey, String modelApiPath,
                        int contextWindowTokens) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.model = Objects.requireNonNull(model);
        this.clock = Objects.requireNonNull(clock);
        this.systemPrompt = Objects.requireNonNull(systemPrompt);
        this.modelId = Objects.requireNonNull(modelId);
        this.modelApiPath = Objects.requireNonNull(modelApiPath);
        this.maxOutputTokens = maxOutputTokens;
        if (contextWindowTokens <= 0) throw new IllegalArgumentException("Context window must be positive");
        this.contextWindowTokens = contextWindowTokens;
        this.admission = new JdbcRunAdmission(dataSource, clock, processId);
        this.terminal = new JdbcRunTerminalStore(dataSource, clock);
        this.lifecycle = new JdbcRunLifecycle(dataSource, terminal);
        this.reader = new JdbcRunReader(dataSource);
        this.content = new JdbcContentRepository(dataSource);
        this.trace = new JdbcLoopTrace(dataSource);
        this.tools = ContentTools.all(content, cursorKey);
        try { this.toolSchemaJson = JsonMapper.builder().build().writeValueAsString(
                tools.stream().map(AgentTool::spec).sorted(Comparator.comparing(ModelGateway.ToolSpec::name)).toList()); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize tool schema", e); }
    }

    /** Migrations and old-process cleanup precede readiness. Call once after constructing the scheduler. */
    public synchronized void start() {
        if (ready.get()) return;
        Flyway.configure().dataSource(dataSource).load().migrate();
        lifecycle.failPreviousProcessRuns(processId);
        sweeper.scheduleAtFixedRate(this::sweepSafely, 2, 2, TimeUnit.SECONDS);
        ready.set(true);
    }

    public JdbcRunAdmission.Receipt submit(JdbcRunAdmission.Request request) {
        ensureReady();
        Optional<JdbcRunAdmission.Receipt> existing = admission.findExisting(request);
        if (existing.isPresent()) return existing.get();
        if (!permits.tryAcquire()) throw new JdbcRunAdmission.AdmissionException("RUN_CAPACITY_EXCEEDED", "Run queue is full");
        JdbcRunAdmission.Receipt receipt;
        try {
            JdbcRunAdmission.Config config = new JdbcRunAdmission.Config(systemPrompt, toolSchemaJson, modelId,
                    Map.of("maxOutputTokens", maxOutputTokens, "apiPath", modelApiPath,
                            "contextWindowTokens", contextWindowTokens));
            receipt = admission.create(request, config);
        } catch (RuntimeException e) {
            permits.release();
            throw e;
        }
        if (!receipt.created()) { permits.release(); return receipt; }
        RunHandle handle = new RunHandle(receipt.runId(), request.projectId());
        if (handles.putIfAbsent(receipt.runId(), handle) != null) {
            permits.release();
            throw new IllegalStateException("Run handle already registered");
        }
        try { workers.execute(handle.task); }
        catch (RejectedExecutionException e) {
            handles.remove(handle.runId, handle);
            handle.releaseOnce();
            terminal.finish(handle.projectId, handle.runId,
                    new LoopOutcome.Failed("SCHEDULING_FAILED", "Run executor rejected the task"));
            return new JdbcRunAdmission.Receipt(receipt.runId(), "FAILED", true);
        }
        return receipt;
    }

    public JdbcRunLifecycle.CancelResult cancel(String projectId, String runId) {
        ensureReady();
        JdbcRunLifecycle.CancelResult result = lifecycle.requestCancel(projectId, runId);
        RunHandle handle = handles.get(runId);
        if (handle == null || !handle.projectId.equals(projectId)) return result;
        handle.signalCancel();
        if ("CANCELLED".equals(result.status()) && handle.phase.compareAndSet(Phase.REGISTERED, Phase.CANCELLED_BEFORE_START)) {
            handle.task.cancel(false);
            workers.remove(handle.task);
            handles.remove(runId, handle);
            handle.releaseOnce();
        } else if ("RUNNING".equals(result.status())) {
            handle.task.cancel(true);
        }
        return result;
    }

    public int occupiedCapacity() { return 10 - permits.availablePermits(); }
    public int runningThreads() { return workers.getActiveCount(); }
    public UUID processInstanceId() { return processId; }
    public boolean healthy() { return ready.get() && lastSweepFailure.get() == null && pendingTerminals.isEmpty(); }

    private void execute(RunHandle handle) {
        if (!handle.phase.compareAndSet(Phase.REGISTERED, Phase.STARTED)) return;
        try {
            if (!lifecycle.start(handle.projectId, handle.runId, processId)) {
                terminal.finish(handle.projectId, handle.runId,
                        new LoopOutcome.Failed("RUN_TIMEOUT", "Queued run was not claimed"));
                return;
            }
            JdbcRunReader.RunData run = reader.load(handle.runId);
            ToolContext context = new ToolContext(run.runId(), run.projectId(), run.targetChapterId(),
                    run.baseVersion(), run.baseContent());
            handle.context = context;
            AgentLoop loop = new AgentLoop(model, tools, trace,
                    LoopLimits.defaults().withContextWindowTokens(run.contextWindowTokens()),
                    clock, handle.cancel::get);
            LoopOutcome outcome = loop.run(new LoopInput(context, run.instruction(), run.systemPrompt(),
                    run.modelId(), run.maxOutputTokens(), run.deadline()));
            terminal.finish(handle.projectId, handle.runId, outcome);
        } catch (RuntimeException e) {
            // A failed commit may have succeeded. Retrying finish first reads the committed terminal state.
            try { terminal.finish(handle.projectId, handle.runId,
                    new LoopOutcome.Failed("PERSISTENCE_ERROR", "Run worker failed")); }
            catch (RuntimeException ignored) {
                pendingTerminals.put(handle.runId, new PendingTerminal(handle.projectId, handle.runId));
            }
        } finally {
            handle.phase.set(Phase.EXITED);
            handles.remove(handle.runId, handle);
            handle.releaseOnce();
        }
    }

    private void sweepSafely() {
        try {
            for (String runId : lifecycle.sweepTimeouts()) {
                RunHandle handle = handles.get(runId);
                if (handle == null) continue;
                handle.signalCancel();
                if (handle.phase.compareAndSet(Phase.REGISTERED, Phase.CANCELLED_BEFORE_START)) {
                    handle.task.cancel(false);
                    workers.remove(handle.task);
                    handles.remove(runId, handle);
                    handle.releaseOnce();
                } else handle.task.cancel(true);
            }
            retryPendingTerminals();
            lastSweepFailure.set(null);
        } catch (RuntimeException failure) { lastSweepFailure.set(failure); }
    }
    private void retryPendingTerminals() {
        RuntimeException firstFailure = null;
        for (PendingTerminal pending : pendingTerminals.values()) {
            try {
                terminal.finish(pending.projectId(), pending.runId(),
                        new LoopOutcome.Failed("PERSISTENCE_ERROR", "Run worker failed"));
                pendingTerminals.remove(pending.runId(), pending);
            } catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
            }
        }
        if (firstFailure != null) throw firstFailure;
    }
    private void ensureReady() {
        if (!healthy()) throw new JdbcRunAdmission.AdmissionException("SERVICE_UNAVAILABLE", "Agent runner is unavailable");
    }

    @Override public void close() {
        ready.set(false);
        for (RunHandle handle : handles.values()) handle.signalCancel();
        sweeper.shutdownNow();
        workers.shutdownNow();
    }

    private final class RunHandle {
        final String runId, projectId;
        final UUID executionId = UUID.randomUUID();
        final AtomicBoolean cancel = new AtomicBoolean();
        volatile ToolContext context;
        final AtomicBoolean released = new AtomicBoolean();
        final AtomicReference<Phase> phase = new AtomicReference<>(Phase.REGISTERED);
        final FutureTask<Void> task;
        RunHandle(String runId, String projectId) {
            this.runId = runId; this.projectId = projectId;
            this.task = new FutureTask<>(() -> { execute(this); return null; });
        }
        void signalCancel() {
            cancel.set(true);
            ToolContext active = context;
            if (active != null) active.cancelActiveSearch();
        }
        void releaseOnce() { if (released.compareAndSet(false, true)) permits.release(); }
    }
}
