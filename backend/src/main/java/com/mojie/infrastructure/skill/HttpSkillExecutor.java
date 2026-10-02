package com.mojie.infrastructure.skill;

import static com.mojie.common.model.JsonSupport.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.mojie.agent.service.tool.ToolContext;
import com.mojie.common.error.BusinessException;
import com.mojie.skill.service.port.SkillExecutor;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

public final class HttpSkillExecutor implements SkillExecutor {
  private final String url;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  public HttpSkillExecutor(String url) {
    this.url = url == null ? "" : url.replaceAll("/$", "");
  }

  public Map<String, Object> capabilities() {
    return Map.of(
        "configured",
        !url.isBlank(),
        "runtimes",
        List.of("Python 3", "Node.js"),
        "dependencies",
        List.of("Python standard library", "Node standard library"),
        "network",
        false,
        "timeoutSeconds",
        30,
        "memoryMiB",
        512,
        "cpus",
        1,
        "pids",
        64,
        "maxOutputBytes",
        262144);
  }

  public JsonNode execute(
      String id,
      Map<String, byte[]> files,
      String script,
      List<String> arguments,
      Map<String, byte[]> inputs,
      ToolContext cancel) {
    if (url.isBlank())
      throw new BusinessException("SKILL_EXECUTOR_UNAVAILABLE", "Skill executor is not configured");
    Map<String, Object> payload =
        Map.of(
            "id",
            id,
            "files",
            encoded(files),
            "script",
            script,
            "arguments",
            arguments,
            "inputs",
            encoded(inputs),
            "timeoutSeconds",
            Math.max(1, Math.min(30, cancel.remainingMillis() / 1000)));
    try {
      send("POST", "/executions", encode(payload));
      long deadline = System.nanoTime() + Math.min(35000, cancel.remainingMillis()) * 1000000L;
      while (System.nanoTime() < deadline) {
        if (cancel.cancelled() || Thread.currentThread().isInterrupted()) {
          stop(id);
          throw new java.util.concurrent.CancellationException();
        }
        JsonNode response = send("GET", "/executions/" + id, null);
        if (!response.path("status").asText().equals("STARTED")) return response;
        Thread.sleep(100);
      }
      stop(id);
      throw new BusinessException("SCRIPT_TIMEOUT", "Script deadline reached");
    } catch (InterruptedException e) {
      stop(id);
      Thread.currentThread().interrupt();
      throw new java.util.concurrent.CancellationException();
    } catch (java.util.concurrent.CancellationException | BusinessException e) {
      throw e;
    } catch (Exception e) {
      stop(id);
      throw new BusinessException("SKILL_EXECUTOR_UNAVAILABLE", "Skill executor request failed");
    }
  }

  private static Map<String, String> encoded(Map<String, byte[]> files) {
    Map<String, String> result = new LinkedHashMap<>();
    files.forEach((path, bytes) -> result.put(path, Base64.getEncoder().encodeToString(bytes)));
    return result;
  }

  private JsonNode send(String method, String path, String body) throws Exception {
    var b = HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(5));
    if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
    else
      b.header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(body));
    var r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    if (r.statusCode() >= 300)
      throw new BusinessException(
          "SKILL_EXECUTOR_UNAVAILABLE", "Skill executor rejected the request");
    return tree(r.body());
  }

  public void stop(String id) {
    boolean interrupted = Thread.interrupted();
    try {
      send("DELETE", "/executions/" + id, null);
    } catch (Exception ignored) {
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }
}
