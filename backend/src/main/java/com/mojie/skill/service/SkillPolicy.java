package com.mojie.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** The same context-only capability description applies to new and historical packages. */
public final class SkillPolicy {
  private SkillPolicy() {}

  private static final Set<String> PROJECT_TOOLS =
      Set.of(
          "read_project",
          "list_chapters",
          "read_chapter",
          "search_chapters",
          "list_settings",
          "read_settings",
          "search_settings",
          "propose_chapter_edit",
          "propose_setting_edit",
          "read_skill",
          "read_skill_file");

  public static boolean isScript(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    return !lower.endsWith("/")
        && (lower.startsWith("scripts/")
            || lower.matches(
                ".*\\.(py|pyw|js|mjs|cjs|ts|tsx|jsx|sh|bash|zsh|bat|cmd|ps1|rb|pl|php|exe|wasm)$"));
  }

  public static Map<String, Object> compatibility(List<String> paths, JsonNode metadata) {
    List<String> scripts = paths.stream().filter(SkillPolicy::isScript).sorted().toList();
    Set<String> unsupportedTools = new TreeSet<>();
    for (String key : List.of("allowed-tools", "tools")) {
      JsonNode declaration = metadata.path(key);
      if (declaration.isArray()) declaration.forEach(item -> collectTools(item, unsupportedTools));
      else collectTools(declaration, unsupportedTools);
    }
    List<String> warnings = new ArrayList<>();
    if (!scripts.isEmpty()) warnings.add("此包包含脚本，本项目不执行脚本；文件仅保留供预览和导出。");
    if (!unsupportedTools.isEmpty())
      warnings.add("此包声明了不支持的工具：" + String.join("、", unsupportedTools) + "。这些声明不会添加工具或权限。");
    for (String key : List.of("mcp", "mcpServers", "dependencies", "compatibility")) {
      JsonNode declaration = metadata.path(key);
      if (!declaration.isMissingNode() && !declaration.isNull() && !declaration.asText().isBlank()
          || declaration.isContainerNode() && !declaration.isEmpty())
        warnings.add("此包声明了 " + key + " 要求，请核对指导；本项目不安装依赖或连接外部服务。");
    }
    return Map.of(
        "mode",
        "CONTEXT_ONLY",
        "readable",
        true,
        "scriptExecution",
        false,
        "scripts",
        scripts,
        "unsupportedTools",
        List.copyOf(unsupportedTools),
        "warnings",
        warnings);
  }

  private static void collectTools(JsonNode value, Set<String> unsupported) {
    if (value.isMissingNode() || value.isNull()) return;
    if (!value.isTextual()) {
      unsupported.add(value.toString());
      return;
    }
    for (String name : value.asText().split("[\\s,]+"))
      if (!name.isBlank() && !PROJECT_TOOLS.contains(name)) unsupported.add(name);
  }
}
