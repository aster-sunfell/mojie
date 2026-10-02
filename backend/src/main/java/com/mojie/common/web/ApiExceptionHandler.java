package com.mojie.common.web;

import com.mojie.common.error.BusinessException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ApiExceptionHandler {
  @ExceptionHandler({BusinessException.class, IllegalArgumentException.class})
  public ResponseEntity<Map<String, Object>> expected(RuntimeException e) {
    String code = e instanceof BusinessException business ? business.code() : "VALIDATION_ERROR";
    HttpStatus status =
        switch (code) {
          case "RESOURCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
          case "RESOURCE_BUSY",
                  "VERSION_CONFLICT",
                  "IDEMPOTENCY_CONFLICT",
                  "PROPOSAL_STATE_CONFLICT",
                  "RUN_CAPACITY_EXCEEDED" ->
              HttpStatus.CONFLICT;
          case "SERVICE_UNAVAILABLE", "MODEL_ERROR" -> HttpStatus.SERVICE_UNAVAILABLE;
          case "STREAM_CAPACITY_EXCEEDED" -> HttpStatus.TOO_MANY_REQUESTS;
          default -> HttpStatus.BAD_REQUEST;
        };
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "code",
                code,
                "message",
                e.getMessage(),
                "requestId",
                UUID.randomUUID().toString(),
                "details",
                Map.of()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
    return ResponseEntity.status(500)
        .body(
            Map.of(
                "code",
                "INTERNAL_ERROR",
                "message",
                "Server request failed",
                "requestId",
                UUID.randomUUID().toString(),
                "details",
                Map.of()));
  }
}
