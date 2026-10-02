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
          case "ARTIFACT_EXPIRED" -> HttpStatus.GONE;
          case "RESOURCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
          case "RESOURCE_BUSY",
                  "VERSION_CONFLICT",
                  "IDEMPOTENCY_CONFLICT",
                  "PROPOSAL_STATE_CONFLICT",
                  "RUN_CAPACITY_EXCEEDED",
                  "CONVERSATION_VERSION_CONFLICT",
                  "SKILL_VERSION_CONFLICT",
                  "SKILL_UNAVAILABLE" ->
              HttpStatus.CONFLICT;
          case "SERVICE_UNAVAILABLE",
                  "MODEL_ERROR",
                  "CONVERSATION_MODEL_UNAVAILABLE",
                  "SKILL_EXECUTOR_UNAVAILABLE",
                  "SKILL_PACKAGE_UNAVAILABLE",
                  "SKILL_RUNTIME_UNAVAILABLE" ->
              HttpStatus.SERVICE_UNAVAILABLE;
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

  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  public ResponseEntity<Map<String, Object>> uploadLimit(Exception e) {
    return ResponseEntity.status(413)
        .body(
            Map.of(
                "code",
                "SKILL_UPLOAD_LIMIT_EXCEEDED",
                "message",
                "Upload exceeds the published limit",
                "requestId",
                UUID.randomUUID().toString(),
                "details",
                Map.of()));
  }

  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class
  })
  public ResponseEntity<Map<String, Object>> invalidRequest(Exception e) {
    return ResponseEntity.badRequest()
        .body(
            Map.of(
                "code",
                "VALIDATION_ERROR",
                "message",
                "Invalid request",
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
