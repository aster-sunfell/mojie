package com.mojie.api;

import com.mojie.agent.run.infra.JdbcRunAdmission;
import com.mojie.agent.run.infra.JdbcRunQuery;
import com.mojie.agent.run.infra.RunEventStream;
import com.mojie.content.JdbcContentService;
import com.mojie.proposal.infra.JdbcEditProposalService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ApiErrors {
    @ExceptionHandler({JdbcContentService.ContentException.class, JdbcRunAdmission.AdmissionException.class,
            JdbcEditProposalService.ProposalException.class, JdbcRunQuery.NotFound.class,
            IllegalArgumentException.class, RunEventStream.CapacityExceeded.class})
    public ResponseEntity<Map<String,Object>> expected(RuntimeException e) {
        String code = e instanceof JdbcContentService.ContentException c ? c.code()
                : e instanceof JdbcRunAdmission.AdmissionException a ? a.code()
                : e instanceof JdbcEditProposalService.ProposalException p ? p.code()
                : e instanceof JdbcRunQuery.NotFound ? "RESOURCE_NOT_FOUND"
                : e instanceof RunEventStream.CapacityExceeded ? "STREAM_CAPACITY_EXCEEDED" : "VALIDATION_ERROR";
        HttpStatus status = switch (code) {
            case "RESOURCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "RESOURCE_BUSY", "VERSION_CONFLICT", "IDEMPOTENCY_CONFLICT", "PROPOSAL_STATE_CONFLICT", "RUN_CAPACITY_EXCEEDED" -> HttpStatus.CONFLICT;
            case "SERVICE_UNAVAILABLE", "MODEL_ERROR" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "STREAM_CAPACITY_EXCEEDED" -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of("code", code, "message", e.getMessage(),
                "requestId", UUID.randomUUID().toString(), "details", Map.of()));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,Object>> unexpected(Exception e) {
        return ResponseEntity.status(500).body(Map.of("code", "INTERNAL_ERROR", "message", "Server request failed",
                "requestId", UUID.randomUUID().toString(), "details", Map.of()));
    }
}
