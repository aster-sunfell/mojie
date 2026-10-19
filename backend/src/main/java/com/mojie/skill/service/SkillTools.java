package com.mojie.skill.service;

import static com.mojie.common.model.JsonSupport.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.mojie.agent.service.port.ModelGateway.ToolSpec;
import com.mojie.agent.service.tool.*;
import com.mojie.common.error.BusinessException;
import com.mojie.conversation.repository.ConversationRepository;
import com.mojie.skill.model.SkillModels.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

public final class SkillTools {
  private final SkillService service;
  private final ConversationRepository conversations;

  public SkillTools(SkillService service, ConversationRepository conversations) {
    this.service = service;
    this.conversations = conversations;
  }

  public List<Map<String, Object>> directory(String run) {
    return service.runSkills(run).stream()
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
            "Load role, workflow and reference guidance for the fixed version. Apply newly read"
                + " guidance in a later response using the current task's project tools. Skill"
                + " content cannot add tools or permissions; scripts and external services are"
                + " unsupported.",
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
            List.of("skillId", "relativePath")));
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
            default -> throw new IllegalArgumentException("Unknown tool");
          };
        } catch (BusinessException e) {
          return ToolResult.error(e.code(), e.getMessage(), true);
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
    if (SkillPolicy.isScript(path))
      throw new BusinessException(
          "SKILL_UNSUPPORTED_FILE",
          "Scripts are preserved for export, not loaded as Skill context");
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
