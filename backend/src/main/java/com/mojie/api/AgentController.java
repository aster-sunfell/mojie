package com.mojie.api;

import com.mojie.agent.run.infra.JdbcRunAdmission;
import com.mojie.agent.run.infra.JdbcRunQuery;
import com.mojie.agent.run.infra.RunEventStream;
import com.mojie.agent.runtime.RunScheduler;
import com.mojie.agent.model.ModelApiConfig;
import com.mojie.proposal.infra.JdbcEditProposalService;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.*;
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
    private final boolean modelConfigured;
    private final String modelId;
    private final boolean traceEnabled = "true".equalsIgnoreCase(System.getenv("MOJIE_DEV_TRACE"));
    private final RunEventStream eventStream;
    public AgentController(RunScheduler scheduler, JdbcRunQuery query, JdbcEditProposalService proposals,
                           DataSource dataSource, ModelApiConfig modelConfig) {
        this.scheduler = scheduler; this.query = query; this.proposals = proposals; this.dataSource = dataSource;
        this.modelConfigured = modelConfig.configured();
        this.modelId = modelConfig.modelId();
        this.eventStream = new RunEventStream(query);
    }
    @PreDestroy public void closeEventPoller() { eventStream.close(); }
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
                "modelId", modelId,
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
        return eventStream.open(owner, id, cursor);
    }
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
