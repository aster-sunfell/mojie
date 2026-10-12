package com.mojie.infrastructure.model;

import com.mojie.agent.service.port.ModelGateway.ModelException;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Bounded UTF-8 SSE framing, independent of HTTP chunk boundaries and multi-line data fields. */
public final class SseFrames {
  private static final int MAX_FRAME_CHARS = 1_048_576;
  private SseFrames() {}

  @FunctionalInterface public interface Handler {
    boolean accept(String data) throws ModelException;
  }

  public static void read(InputStream input, StreamWatchdog watchdog, Handler handler)
      throws IOException, ModelException {
    var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    StringBuilder data = new StringBuilder();
    String line;
    while ((line = line(reader)) != null) {
      watchdog.ensureActive();
      if (line.isEmpty()) {
        if (!data.isEmpty()) {
          watchdog.eventReceived();
          if (!handler.accept(data.substring(0, data.length() - 1))) return;
          data.setLength(0);
        }
      } else if (line.startsWith(":")) {
        watchdog.eventReceived();
      } else if (line.equals("data") || line.startsWith("data:")) {
        String value = line.length() <= 5 ? "" : line.substring(5);
        if (value.startsWith(" ")) value = value.substring(1);
        if (data.length() + value.length() + 1 > MAX_FRAME_CHARS)
          throw new ModelException("INVALID_MODEL_RESPONSE", "Model stream frame is too large");
        data.append(value).append('\n');
      }
    }
    watchdog.ensureActive();
    throw new ModelException("INVALID_MODEL_RESPONSE", "Model stream ended before its terminal event");
  }

  private static String line(BufferedReader reader) throws IOException, ModelException {
    StringBuilder line = new StringBuilder();
    int c;
    while ((c = reader.read()) != -1) {
      if (c == '\n') break;
      if (c == '\r') {
        reader.mark(1);
        if (reader.read() != '\n') reader.reset();
        break;
      }
      if (line.length() >= MAX_FRAME_CHARS)
        throw new ModelException("INVALID_MODEL_RESPONSE", "Model stream line is too large");
      line.append((char) c);
    }
    return c == -1 && line.isEmpty() ? null : line.toString();
  }
}
