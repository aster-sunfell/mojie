package com.mojie.common.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mojie.common.error.BusinessException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ApiExceptionHandlerTest {
  private final StreamController controller = new StreamController();
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(controller)
          .setControllerAdvice(new ApiExceptionHandler())
          .build();

  @Test
  void sseValidationErrorsUseJsonEvenWhenTheClientAcceptsOnlyEvents() throws Exception {
    assertJsonError("/expected", 404, "RESOURCE_NOT_FOUND");
    assertJsonError("/invalid?count=bad", 400, "VALIDATION_ERROR");
  }

  @Test
  void uncommittedSseFailureReplacesThePresetContentType() throws Exception {
    MvcResult opened = mvc.perform(get("/stream").accept(MediaType.TEXT_EVENT_STREAM)).andReturn();
    assertTrue(opened.getRequest().isAsyncStarted());
    controller.emitter.completeWithError(new IllegalStateException("Stream query failed"));
    var response = mvc.perform(asyncDispatch(opened)).andReturn().getResponse();
    assertEquals(500, response.getStatus());
    assertEquals(MediaType.APPLICATION_JSON_VALUE, response.getContentType());
    assertEquals(
        "INTERNAL_ERROR",
        JsonMapper.builder().build().readTree(response.getContentAsString()).path("code").asText());
  }

  @Test
  void committedSseFailureDoesNotAppendJsonToTheStream() throws Exception {
    for (Exception failure :
        List.of(
            new IllegalStateException("Stream query failed"),
            new BusinessException("RESOURCE_NOT_FOUND", "Run unavailable"))) {
      MvcResult opened =
          mvc.perform(get("/stream").accept(MediaType.TEXT_EVENT_STREAM)).andReturn();
      controller.emitter.send(SseEmitter.event().name("run.failed").data("MODEL_TIMEOUT"));
      assertTrue(opened.getResponse().isCommitted());
      String sent = opened.getResponse().getContentAsString();
      controller.emitter.completeWithError(failure);
      var response = mvc.perform(asyncDispatch(opened)).andReturn().getResponse();
      assertEquals(MediaType.TEXT_EVENT_STREAM_VALUE, response.getContentType());
      assertEquals(sent, response.getContentAsString());
    }
  }

  @Test
  void disconnectedSseClientDoesNotTriggerAnotherWrite() throws Exception {
    MvcResult opened = mvc.perform(get("/stream").accept(MediaType.TEXT_EVENT_STREAM)).andReturn();
    controller.emitter.completeWithError(new IOException("Broken pipe"));
    var response = mvc.perform(asyncDispatch(opened)).andReturn().getResponse();
    assertEquals("", response.getContentAsString());
  }

  private void assertJsonError(String url, int status, String code) throws Exception {
    var response =
        mvc.perform(get(url).accept(MediaType.TEXT_EVENT_STREAM)).andReturn().getResponse();
    assertEquals(status, response.getStatus());
    assertEquals(MediaType.APPLICATION_JSON_VALUE, response.getContentType());
    assertEquals(
        code,
        JsonMapper.builder().build().readTree(response.getContentAsString()).path("code").asText());
  }

  @RestController
  static class StreamController {
    SseEmitter emitter;

    @GetMapping(value = "/expected", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter expected(HttpServletResponse response) {
      response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
      throw new BusinessException("RESOURCE_NOT_FOUND", "Run unavailable");
    }

    @GetMapping(value = "/invalid", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter invalid(@RequestParam int count) {
      throw new AssertionError("Invalid count must be rejected before entering the controller");
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream() {
      emitter = new SseEmitter(0L);
      return emitter;
    }
  }
}
