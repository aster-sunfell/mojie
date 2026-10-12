package com.mojie.skill.service;

import static com.mojie.common.model.JsonSupport.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.mojie.agent.repository.RunEventRepository;
import com.mojie.agent.service.port.ModelGateway.ToolSpec;
import com.mojie.agent.service.tool.*;
import com.mojie.common.error.BusinessException;
import com.mojie.common.transaction.TransactionRunner;
import com.mojie.conversation.repository.ConversationRepository;
import com.mojie.skill.model.SkillModels.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

public final class SkillTools {
  private final SkillService service;
  private final ConversationRepository conversations;
  private final TransactionRunner tx;
  private final RunEventRepository events;

  public SkillTools(
      SkillService s, ConversationRepository c, TransactionRunner t, RunEventRepository e) {
    service = s;
    conversations = c;
    tx = t;
    events = e;
  }

  public List<Map<String, Object>> directory(String run) {
    return service.repository().runSkills(run).stream()
        .map(
            s ->
                Map.<String, Object>of(
                    "skillId",
                    s.skillId(),
                    "name",
                    s.metadata().path("name").asText(),
                    "description",
                    s.metadata().path("description").asText(),
                    "versionId",
                    s.versionId(),
                    "readLocation",
                    "read_skill(" + s.skillId() + ")",
                    "compatibility",
                    s.compatibility()))
        .toList();
  }

  public List<Map<String, Object>> explicit(String run) {
    return service.repository().runSkills(run).stream()
        .filter(s -> s.source().equals("EXPLICIT"))
        .map(
            s ->
                Map.<String, Object>of(
                    "skillId",
                    s.skillId(),
                    "name",
                    s.metadata().path("name").asText(),
                    "versionId",
                    s.versionId()))
        .toList();
  }

  private RunSkill authorized(String run, String id, boolean loaded) {
    var meta = conversations.runContext(run);
    if (meta == null || meta.conversationId() == null)
      throw new BusinessException("SKILL_UNAVAILABLE", "Skill tools are unavailable for this task");
    var skill = service.requireSkill(id);
    if (!skill.enabled()) throw new BusinessException("SKILL_UNAVAILABLE", "Skill is disabled");
    RunSkill s =
        service.repository().runSkills(run).stream()
            .filter(x -> x.skillId().equals(id))
            .findFirst()
            .orElseThrow(
                () ->
                    new BusinessException(
                        "SKILL_UNAVAILABLE", "Skill is not in this Run's directory"));
    if (loaded && (!s.loaded() || !s.providedToModel()))
      throw new BusinessException(
          "SKILL_UNAVAILABLE", "Load Skill guidance in an earlier model request first");
    service.requireVersion(id, s.versionId());
    return s;
  }

  private Map<String, Object> read(String run, String id) {
    RunSkill s = authorized(run, id, false);
    if (!s.loaded()
        && service.repository().runSkills(run).stream().filter(RunSkill::loaded).count() >= 8)
      throw new BusinessException("SKILL_UPLOAD_LIMIT_EXCEEDED", "This Run has loaded 8 Skills");
    var meta = conversations.runContext(run);
    Version v = service.requireVersion(id, s.versionId());
    var files = service.repository().files(v.id());
    FileEntry body =
        files.stream()
            .filter(f -> f.relativePath().equals("SKILL.md"))
            .findFirst()
            .orElseThrow(
                () -> new BusinessException("SKILL_PACKAGE_UNAVAILABLE", "Missing SKILL.md"));
    return Map.of(
        "skillId",
        id,
        "versionId",
        v.id(),
        "body",
        text(service.storage().read(v, body)),
        "root",
        "/skill",
        "files",
        files,
        "compatibility",
        v.compatibility());
  }

  public List<AgentTool> tools() {
    return List.of(
        tool(
            "read_skill",
            "Load complete Skill guidance for the fixed version. Plan dependent file/script"
                + " operations in a later response.",
            Map.of("skillId", Map.of("type", "string")),
            List.of("skillId")),
        tool(
            "read_skill_file",
            "Read a same-version file. Text pagination uses Unicode codepoint offset/limit (maximum"
                + " 16000); binary files return a protected download reference, not read evidence.",
            Map.of(
                "skillId",
                Map.of("type", "string"),
                "relativePath",
                Map.of("type", "string"),
                "offset",
                Map.of("type", "integer", "minimum", 0),
                "limit",
                Map.of("type", "integer", "minimum", 1, "maximum", 16000)),
            List.of("skillId", "relativePath")),
        tool(
            "run_skill_script",
            "Execute a package Python/Node script in the isolated no-network environment. Inputs:"
                + " [{name,source:{type:'TEXT',text}}], or CHAPTER with chapterId, or ARTIFACT with"
                + " artifactId. Package=/skill; input=/input; output=/output. Arguments do not"
                + " materialize input files. If a script reads /input/content.txt, provide inputs"
                + " [{name: 'content.txt', source: {type: 'CHAPTER', chapterId: 'a chapter fully"
                + " read in this Run'}}]. Do not pass shell commands or environment variables.",
            Map.of(
                "skillId",
                Map.of("type", "string"),
                "relativePath",
                Map.of("type", "string"),
                "arguments",
                Map.of("type", "array", "items", Map.of("type", "string")),
                "inputs",
                Map.of(
                    "type",
                    "array",
                    "maxItems",
                    20,
                    "items",
                    Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                            "name",
                            Map.of(
                                "type", "string", "pattern", "^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$"),
                            "source",
                            Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of(
                                    "type",
                                    Map.of(
                                        "type",
                                        "string",
                                        "enum",
                                        List.of("TEXT", "CHAPTER", "SETTING", "ARTIFACT")),
                                    "text",
                                    Map.of("type", "string"),
                                    "chapterId",
                                    Map.of("type", "string"),
                                    "settingId",
                                    Map.of("type", "string"),
                                    "artifactId",
                                    Map.of("type", "string")),
                                "required",
                                List.of("type"),
                                "additionalProperties",
                                false)),
                        "required",
                        List.of("name", "source"),
                        "additionalProperties",
                        false))),
            List.of("skillId", "relativePath", "arguments", "inputs")));
  }

  private AgentTool tool(
      String name, String description, Map<String, Object> properties, List<String> required) {
    return new AgentTool() {
      public ToolSpec spec() {
        return new ToolSpec(
            name,
            description,
            Map.of(
                "type",
                "object",
                "properties",
                properties,
                "required",
                required,
                "additionalProperties",
                false));
      }

      public ToolResult execute(JsonNode args, ToolContext ctx) {
        try {
          var keys = args.fieldNames();
          while (keys.hasNext())
            if (!properties.containsKey(keys.next()))
              throw new IllegalArgumentException("Unknown tool argument");
          String skill = required(args, "skillId");
          return switch (name) {
            case "read_skill" -> {
              RunSkill existing = authorized(ctx.runId(), skill, false);
              yield ToolResult.ok(
                  existing.loaded() && existing.providedToModel()
                      ? Map.of(
                          "skillId",
                          skill,
                          "versionId",
                          existing.versionId(),
                          "alreadyLoaded",
                          true,
                          "message",
                          "Guidance is already in the current effective context")
                      : read(ctx.runId(), skill),
                  List.of());
            }
            case "read_skill_file" -> ToolResult.ok(readFile(ctx.runId(), skill, args), List.of());
            case "run_skill_script" -> script(ctx, skill, args);
            default -> throw new IllegalArgumentException("Unknown tool");
          };
        } catch (BusinessException e) {
          if (name.equals("run_skill_script"))
            tx.required(
                () -> {
                  service.repository().failRunExecutions(ctx.runId(), e.code());
                  return null;
                });
          return ToolResult.error(e.code(), e.getMessage(), true);
        } catch (java.util.concurrent.CancellationException e) {
          tx.required(
              () -> {
                service.repository().failRunExecutions(ctx.runId(), "CANCELLED");
                return null;
              });
          throw e;
        }
      }
    };
  }

  private Map<String, Object> readFile(String run, String skill, JsonNode args) {
    RunSkill s = authorized(run, skill, true);
    Version v = service.repository().version(skill, s.versionId());
    String path = required(args, "relativePath");
    var file =
        service.repository().files(v.id()).stream()
            .filter(f -> f.relativePath().equals(path))
            .findFirst()
            .orElseThrow(
                () -> new BusinessException("RESOURCE_NOT_FOUND", "Package file unavailable"));
    byte[] bytes = service.storage().read(v, file);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("file", file);
    result.put("versionId", v.id());
    if (!file.mime().startsWith("text/") && !file.mime().equals("application/json")) {
      result.put("binary", true);
      result.put(
          "download", "/api/v1/skills/" + skill + "/versions/" + v.id() + "/files/" + file.id());
      return result;
    }
    String content = text(bytes);
    int offset = args.path("offset").asInt(0),
        limit = args.path("limit").asInt(16000),
        total = content.codePointCount(0, content.length());
    if (offset < 0 || offset > total || limit < 1 || limit > 16000)
      throw new IllegalArgumentException("Invalid text page");
    int end = Math.min(total, offset + limit);
    result.put(
        "content",
        content.substring(
            content.offsetByCodePoints(0, offset), content.offsetByCodePoints(0, end)));
    result.put("offset", offset);
    result.put("totalCodePoints", total);
    result.put("hasMore", end < total);
    result.put("nextOffset", end);
    result.put("complete", offset == 0 && end == total);
    return result;
  }

  private AgentTool.ToolResult script(ToolContext ctx, String skill, JsonNode args) {
    RunSkill s = authorized(ctx.runId(), skill, true);
    if (!s.providedToModel())
      throw new BusinessException(
          "SKILL_UNAVAILABLE", "Skill guidance must reach a prior model request before execution");
    String path = required(args, "relativePath");
    if (!path.startsWith("scripts/")
        || (!path.endsWith(".py") && !path.endsWith(".js") && !path.endsWith(".mjs")))
      throw new BusinessException(
          "SKILL_RUNTIME_UNAVAILABLE",
          "Only package Python and Node script entrypoints are supported");
    Version v = service.repository().version(skill, s.versionId());
    Map<String, byte[]> files = new LinkedHashMap<>();
    for (var f : service.repository().files(v.id()))
      files.put(f.relativePath(), service.storage().read(v, f));
    if (!files.containsKey(path))
      throw new BusinessException("RESOURCE_NOT_FOUND", "Script file unavailable");
    if (!args.path("arguments").isArray() || args.path("arguments").size() > 64)
      throw new IllegalArgumentException("Invalid arguments");
    List<String> arguments = new ArrayList<>();
    for (var a : args.path("arguments")) {
      if (!a.isTextual() || a.asText().length() > 4096 || a.asText().indexOf('\0') >= 0)
        throw new IllegalArgumentException("Invalid script argument");
      arguments.add(a.asText());
    }
    var meta = conversations.runContext(ctx.runId());
    Map<String, byte[]> inputs = new LinkedHashMap<>();
    List<Map<String, Object>> sources = new ArrayList<>();
    JsonNode selected = args.path("inputs");
    if (!selected.isMissingNode() && (!selected.isArray() || selected.size() > 20))
      throw new IllegalArgumentException("Invalid inputs");
    for (var input : selected) {
      String name = required(input, "name");
      if (!name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}") || inputs.containsKey(name))
        throw new IllegalArgumentException("Invalid input filename");
      var source = input.path("source");
      byte[] bytes;
      String type = required(source, "type");
      switch (type) {
        case "TEXT" -> {
          bytes = required(source, "text").getBytes(StandardCharsets.UTF_8);
          if (bytes.length > 65536) throw new IllegalArgumentException("Text input exceeds 64 KiB");
        }
        case "CHAPTER" -> {
          String chapter = required(source, "chapterId");
          if (Objects.equals(chapter, ctx.targetChapterId()) && ctx.targetRead())
            bytes = ctx.baseContent().getBytes(StandardCharsets.UTF_8);
          else if (ctx.readChapters().containsKey(chapter))
            bytes = ctx.readChapters().get(chapter).content().getBytes(StandardCharsets.UTF_8);
          else throw new IllegalArgumentException("Chapter must be fully read in this Run");
        }
        case "SETTING" -> {
          String setting = required(source, "settingId");
          var read = ctx.readSettings().get(setting);
          if (read == null)
            throw new IllegalArgumentException("Setting must be fully read in this Run");
          bytes = read.content().getBytes(StandardCharsets.UTF_8);
        }
        case "ARTIFACT" -> {
          String artifact = required(source, "artifactId");
          var a = service.artifact(artifact);
          var origin = conversations.runContext(a.runId());
          if (origin == null || !Objects.equals(origin.conversationId(), meta.conversationId()))
            throw new BusinessException(
                "RESOURCE_NOT_FOUND", "Artifact unavailable in this conversation");
          bytes = service.downloadArtifact(artifact);
        }
        default -> throw new IllegalArgumentException("Unsupported input source");
      }
      inputs.put(name, bytes);
      var provenance = source.deepCopy();
      if (provenance instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
        if (type.equals("CHAPTER")) {
          String chapter = source.path("chapterId").asText();
          object.put(
              "version",
              Objects.equals(chapter, ctx.targetChapterId())
                  ? ctx.baseVersion()
                  : ctx.readChapters().get(chapter).version());
        }
        if (type.equals("SETTING"))
          object.put(
              "settingHash", ctx.readSettings().get(source.path("settingId").asText()).hash());
      }
      sources.add(
          Map.of(
              "name",
              name,
              "source",
              provenance,
              "hash",
              hash(bytes),
              "bytes",
              bytes.length,
              "provenance",
              type.equals("TEXT") ? "MODEL_GENERATED" : type));
    }
    String execution = UUID.randomUUID().toString();
    tx.required(
        () -> {
          service
              .repository()
              .startExecution(
                  execution,
                  ctx.runId(),
                  ctx.toolExecutionId(),
                  v.id(),
                  path,
                  encode(arguments),
                  encode(sources),
                  encode(service.executor().capabilities()));
          event(ctx.runId(), "script.started", Map.of("executionId", execution));
          return null;
        });
    JsonNode response;
    try {
      response = service.executor().execute(execution, files, path, arguments, inputs, ctx);
      if (response.path("environment").isObject())
        tx.required(
            () -> {
              service
                  .repository()
                  .executionEnvironment(execution, encode(response.path("environment")));
              return null;
            });
      String status = response.path("status").asText("FAILED"),
          code = response.path("errorCode").asText(null);
      Integer exit = response.hasNonNull("exitCode") ? response.path("exitCode").asInt() : null;
      List<Map<String, Object>> artifacts = new ArrayList<>();
      if (ctx.cancelled()) throw new java.util.concurrent.CancellationException();
      if (status.equals("SUCCEEDED")) {
        int count = 0;
        long total = 0;
        for (var artifact : response.path("artifacts")) {
          String name = artifact.path("name").asText();
          if (!safeArtifactName(name) || ++count > 20)
            throw new BusinessException(
                "SCRIPT_OUTPUT_LIMIT_EXCEEDED", "Invalid generated artifact");
          byte[] bytes = Base64.getDecoder().decode(artifact.path("base64").asText());
          if ((total += bytes.length) > 20 * 1024 * 1024L)
            throw new BusinessException(
                "SCRIPT_OUTPUT_LIMIT_EXCEEDED", "Artifact output exceeds 20 MiB");
          String id = UUID.randomUUID().toString();
          service.storage().saveArtifact(id, bytes);
          tx.required(
              () -> {
                service
                    .repository()
                    .publishArtifact(
                        meta.projectId(),
                        ctx.runId(),
                        execution,
                        id,
                        name,
                        hash(bytes),
                        bytes.length,
                        artifact.path("mime").asText("application/octet-stream"));
                event(ctx.runId(), "artifact.created", Map.of("artifactId", id));
                return null;
              });
          artifacts.add(
              Map.of(
                  "artifactId",
                  id,
                  "name",
                  name,
                  "hash",
                  hash(bytes),
                  "download",
                  "/api/v1/artifacts/" + id + "/download"));
        }
      }
      tx.required(
          () -> {
            service
                .repository()
                .finishExecution(
                    execution,
                    status,
                    exit,
                    response.path("stdout").asText(),
                    response.path("stderr").asText(),
                    code);
            event(
                ctx.runId(), "script.finished", Map.of("executionId", execution, "status", status));
            return null;
          });
      if (!status.equals("SUCCEEDED"))
        return AgentTool.ToolResult.error(
            code == null ? "SCRIPT_FAILED" : code,
            "Script failed; executionId="
                + execution
                + "; exit="
                + exit
                + "; stderr="
                + response.path("stderr").asText(),
            true);
      return AgentTool.ToolResult.ok(
          Map.of(
              "executionId",
              execution,
              "exitCode",
              exit == null ? 0 : exit,
              "stdout",
              response.path("stdout").asText(),
              "stderr",
              response.path("stderr").asText(),
              "artifacts",
              artifacts,
              "environment",
              response.path("environment").isObject()
                  ? response.path("environment")
                  : service.executor().capabilities()),
          List.of());
    } catch (RuntimeException failure) {
      String status =
          failure instanceof java.util.concurrent.CancellationException ? "CANCELLED" : "FAILED";
      String code =
          failure instanceof BusinessException b
              ? b.code()
              : status.equals("CANCELLED") ? "CANCELLED" : "SKILL_EXECUTOR_UNAVAILABLE";
      tx.required(
          () -> {
            service.repository().finishExecution(execution, status, null, "", "", code);
            event(
                ctx.runId(),
                "script.finished",
                Map.of("executionId", execution, "status", status, "errorCode", code));
            return null;
          });
      throw failure;
    }
  }

  private void event(String run, String type, Map<String, Object> data) {
    if (!service.repository().active(run)) return;
    events.insert(run, events.nextSequence(run), type, encode(data));
  }

  private static boolean safeArtifactName(String name) {
    if (name.isBlank()
        || name.length() > 256
        || name.startsWith("/")
        || name.contains("\\")
        || name.matches("^[A-Za-z]:.*")
        || name.codePoints().anyMatch(c -> c < 32)) return false;
    for (String part : name.split("/", -1))
      if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
    return true;
  }

  private static String required(JsonNode args, String name) {
    JsonNode n = args.get(name);
    if (n == null || !n.isTextual() || n.asText().isBlank())
      throw new IllegalArgumentException(name + " is required text");
    return n.asText();
  }

  private static String text(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      throw new BusinessException("SKILL_INVALID_PACKAGE", "File is not valid UTF-8");
    }
  }
}
