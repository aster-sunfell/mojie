package com.mojie.api;

import com.mojie.agent.run.infra.JdbcRunAdmission;
import com.mojie.agent.run.infra.JdbcRunQuery;
import com.mojie.agent.runtime.RunScheduler;
import com.mojie.proposal.infra.JdbcEditProposalService;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public final class AgentController {
    private final RunScheduler scheduler;
    private final JdbcRunQuery query;
    private final JdbcEditProposalService proposals;
    private final DataSource dataSource;
    private final String owner = System.getenv().getOrDefault("MOJIE_OWNER_ID", "local-author");
    private final boolean modelConfigured = System.getenv("ANTHROPIC_API_KEY") != null
            && !System.getenv("ANTHROPIC_API_KEY").isBlank();
    private final boolean traceEnabled = "true".equalsIgnoreCase(System.getenv("MOJIE_DEV_TRACE"));
    private final ScheduledExecutorService events = Executors.newScheduledThreadPool(2, r -> {
        Thread thread = new Thread(r, "mojie-sse"); thread.setDaemon(true); return thread;
    });
    public AgentController(RunScheduler scheduler, JdbcRunQuery query, JdbcEditProposalService proposals, DataSource dataSource) {
        this.scheduler = scheduler; this.query = query; this.proposals = proposals; this.dataSource = dataSource;
    }
    @PreDestroy public void closeEventPoller() { events.shutdownNow(); }
    private static Map<String,Object> data(Object value) { return Map.of("data", value); }
    public record Submit(String projectId, String targetChapterId, Integer expectedVersion,
                         String instruction, String clientRequestId) {}

    @GetMapping("/health") public ResponseEntity<Map<String,Object>> health() {
        try (Connection c = dataSource.getConnection(); ResultSet rs = c.createStatement().executeQuery("SELECT 1")) {
            rs.next();
            return ResponseEntity.ok(data(Map.of("status", scheduler.healthy() ? "UP" : "DEGRADED")));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(data(Map.of("status", "DOWN")));
        }
    }
    @GetMapping("/system/capabilities") public Map<String,Object> capabilities() {
        return data(Map.of("agentConfigured", modelConfigured && scheduler.healthy(),
                "maxInstructionCodePoints", 2000, "maxChapterCodePoints", 6000,
                "maxSettingCodePoints", 2000, "maxModelCalls", 8, "maxToolCalls", 12));
    }
    @PostMapping("/agent/runs") public ResponseEntity<Map<String,Object>> submit(@RequestBody Submit body) {
        if (!modelConfigured) throw new JdbcRunAdmission.AdmissionException("MODEL_ERROR", "Model is not configured");
        if (body.expectedVersion() == null) throw new IllegalArgumentException("expectedVersion is required");
        JdbcRunAdmission.Receipt receipt = scheduler.submit(new JdbcRunAdmission.Request(owner, body.projectId(),
                body.targetChapterId(), body.expectedVersion(), body.instruction(), body.clientRequestId()));
        return ResponseEntity.status(202).body(data(Map.of("runId", receipt.runId(), "status", receipt.status())));
    }
    @GetMapping("/agent/runs") public Map<String,Object> runs(@RequestParam String projectId,
            @RequestParam(required = false) String chapterId, @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        return data(query.page(owner, projectId, chapterId, status, cursor, limit));
    }
    @GetMapping("/agent/runs/{id}") public Map<String,Object> run(@PathVariable String id) {
        return data(query.detail(owner, id));
    }
    @PostMapping("/agent/runs/{id}/cancel") public Map<String,Object> cancel(@PathVariable String id) {
        String projectId = String.valueOf(query.detail(owner, id).get("projectId"));
        return data(scheduler.cancel(projectId, id));
    }
    @GetMapping("/agent/runs/{id}/trace") public Map<String,Object> trace(@PathVariable String id) {
        if (!traceEnabled) throw new JdbcRunQuery.NotFound();
        return data(query.trace(owner, id));
    }
    @GetMapping(value = "/agent/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id, @RequestParam(required = false) Long afterSeq,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        query.detail(owner, id);
        long cursor;
        try { cursor = lastEventId != null && !lastEventId.isBlank() ? Long.parseLong(lastEventId)
                : afterSeq == null ? 0 : afterSeq; }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid event cursor"); }
        if (cursor < 0) throw new IllegalArgumentException("Invalid event cursor");
        SseEmitter emitter = new SseEmitter(0L);
        AtomicLong next = new AtomicLong(cursor);
        AtomicLong lastHeartbeat = new AtomicLong(System.currentTimeMillis());
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();
        Runnable stop = () -> {
            closed.set(true);
            ScheduledFuture<?> task = future.get(); if (task != null) task.cancel(false);
        };
        emitter.onCompletion(stop); emitter.onTimeout(stop); emitter.onError(error -> stop.run());
        ScheduledFuture<?> task = events.scheduleAtFixedRate(() -> {
            if (closed.get()) return;
            try {
                List<JdbcRunQuery.Event> batch = query.events(owner, id, next.get(), 100);
                for (JdbcRunQuery.Event event : batch) {
                    emitter.send(SseEmitter.event().id(Long.toString(event.seq())).name(event.type()).data(Map.of(
                            "schemaVersion", 1, "runId", id, "seq", event.seq(), "type", event.type(),
                            "occurredAt", event.occurredAt(), "payload", event.payload())));
                    next.set(event.seq());
                }
                if (System.currentTimeMillis() - lastHeartbeat.get() >= 15_000) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    lastHeartbeat.set(System.currentTimeMillis());
                }
                Map<String,Object> snapshot = batch.isEmpty() ? query.detail(owner, id) : null;
                if (snapshot != null && terminal(String.valueOf(snapshot.get("status")))
                        && next.get() >= ((Number) snapshot.get("lastEventSeq")).longValue()) {
                    stop.run(); emitter.complete();
                } else if (batch.stream().anyMatch(event -> event.type().startsWith("run.")
                        && Set.of("run.succeeded", "run.failed", "run.cancelled").contains(event.type()))) {
                    stop.run(); emitter.complete();
                }
            } catch (Exception e) { stop.run(); emitter.completeWithError(e); }
        }, 0, 1, TimeUnit.SECONDS);
        future.set(task);
        if (closed.get()) task.cancel(false);
        return emitter;
    }
    private static boolean terminal(String status) { return Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status); }
    @GetMapping("/edit-proposals/{id}") public Map<String,Object> proposal(@PathVariable String id) {
        return data(query.proposal(owner, id));
    }
    @PostMapping("/edit-proposals/{id}/accept") public ResponseEntity<Map<String,Object>> accept(@PathVariable String id) {
        JdbcEditProposalService.Decision result = proposals.accept(owner, id);
        return ResponseEntity.status("CONFLICTED".equals(result.status()) ? 409 : 200).body(data(result));
    }
    @PostMapping("/edit-proposals/{id}/reject") public Map<String,Object> reject(@PathVariable String id) {
        return data(proposals.reject(owner, id));
    }
}
