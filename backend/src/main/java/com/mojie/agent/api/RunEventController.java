package com.mojie.agent.api;

import com.mojie.agent.api.sse.RunEventStream;
import com.mojie.agent.service.RunEventQueryService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public final class RunEventController {
  private final RunEventQueryService query;
  private final RunEventStream eventStream;

  public RunEventController(RunEventQueryService query, RunEventStream eventStream) {
    this.query = query;
    this.eventStream = eventStream;
  }

  @GetMapping(value = "/agent/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(
      @PathVariable String id,
      @RequestParam(required = false) Long afterSeq,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    query.requireExists(id);
    long cursor;
    try {
      cursor =
          lastEventId != null && !lastEventId.isBlank()
              ? Long.parseLong(lastEventId)
              : afterSeq == null ? 0 : afterSeq;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid event cursor");
    }
    if (cursor < 0) throw new IllegalArgumentException("Invalid event cursor");
    return eventStream.open(id, cursor);
  }
}
