package com.mojie.common.web;

import com.mojie.common.error.BusinessException;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.util.DisconnectedClientHelper;

@RestControllerAdvice
public final class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
  private static final DisconnectedClientHelper disconnectedClients =
      new DisconnectedClientHelper(ApiExceptionHandler.class.getName());

  @ExceptionHandler({BusinessException.class, IllegalArgumentException.class})
  public ResponseEntity<Map<String, Object>> expected(
      RuntimeException e, HttpServletResponse response) {
    if (responseUnavailable(e, response)) return null;
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
              "MODEL_METADATA_UNAVAILABLE",
              "CONVERSATION_MODEL_UNAVAILABLE",
              "SKILL_EXECUTOR_UNAVAILABLE",
              "SKILL_PACKAGE_UNAVAILABLE",
              "SKILL_RUNTIME_UNAVAILABLE" ->
              HttpStatus.SERVICE_UNAVAILABLE;
          case "STREAM_CAPACITY_EXCEEDED" -> HttpStatus.TOO_MANY_REQUESTS;
          default -> HttpStatus.BAD_REQUEST;
        };
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
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
  public ResponseEntity<Map<String, Object>> uploadLimit(
      Exception e, HttpServletResponse response) {
    if (responseUnavailable(e, response)) return null;
    return ResponseEntity.status(413)
        .contentType(MediaType.APPLICATION_JSON)
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
  public ResponseEntity<Map<String, Object>> invalidRequest(
      Exception e, HttpServletResponse response) {
    if (responseUnavailable(e, response)) return null;
    return ResponseEntity.badRequest()
        .contentType(MediaType.APPLICATION_JSON)
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
  public ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletResponse response) {
    if (responseUnavailable(e, response)) return null;
    log.error("Server request failed", e);
    return ResponseEntity.status(500)
        .contentType(MediaType.APPLICATION_JSON)
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

  private static boolean responseUnavailable(Exception e, HttpServletResponse response) {
    // SSE clients can close as soon as they receive a terminal event. The response is then
    // unusable; trying to serialize a JSON error would obscure the original failure.
    if (disconnectedClients.checkAndLogClientDisconnectedException(e)) return true;
    if (!response.isCommitted()) return false;
    log.error("Request failed after the response was committed", e);
    return true;
  }
}
