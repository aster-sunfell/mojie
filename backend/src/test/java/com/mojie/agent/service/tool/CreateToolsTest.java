package com.mojie.agent.service.tool;

import static com.mojie.common.model.JsonSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class CreateToolsTest {
  private AgentTool tool(String name) {
    return ContentTools.all(null, "test-tool-key-123456".getBytes()).stream()
        .filter(t -> t.spec().name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private ToolContext context() {
    var ctx = new ToolContext("run", "project", null, 0, null);
    ctx.unified(true);
    return ctx;
  }

  private Map<String, Object> args(String title) {
    return new HashMap<>(
        Map.of(
            "operation",
            "CREATE",
            "proposedType",
            "RULE",
            "proposedTitle",
            title,
            "proposedContent",
            "完整草稿",
            "reason",
            "新建"));
  }

  @Test
  void newSettingsNeedNoReadAndRejectDuplicateTargetsAndNinthDraft() {
    var ctx = context();
    var tool = tool("propose_setting_edit");
    for (int i = 0; i < 8; i++)
      assertTrue(tool.execute(tree(encode(args("设定" + i))), ctx).success());
    assertEquals(
        "PROPOSAL_ALREADY_EXISTS", tool.execute(tree(encode(args("设定0"))), ctx).error().code());
    assertEquals(
        "PROPOSAL_LIMIT_REACHED", tool.execute(tree(encode(args("设定8"))), ctx).error().code());
    assertEquals(8, ctx.candidates().size());
    assertNull(ctx.candidate().settingEdit().settingId());
  }

  @Test
  void createCannotCarryInventedIdsAndUpdateNeverBecomesCreate() {
    var tool = tool("propose_setting_edit");
    var ctx = context();
    var args = args("世界观");
    args.put("settingId", "invented");
    assertThrows(IllegalArgumentException.class, () -> tool.execute(tree(encode(args)), ctx));
    args.put("operation", "UPDATE");
    args.put("expectedVersion", 1);
    assertEquals("INSUFFICIENT_CONTEXT", tool.execute(tree(encode(args)), ctx).error().code());
    assertTrue(ctx.candidates().isEmpty());
  }

  @Test
  void chaptersAndSettingsShareEightDraftBudgetAndCreateHasLengthLimits() {
    var ctx = context();
    var setting = tool("propose_setting_edit");
    var chapter = tool("propose_chapter_edit");
    assertTrue(setting.execute(tree(encode(args("世界观"))), ctx).success());
    var chapterArgs = args("第一章");
    chapterArgs.remove("proposedType");
    assertTrue(chapter.execute(tree(encode(chapterArgs)), ctx).success());
    assertEquals("CHAPTER", ctx.candidates().get(1).targetType());
    assertEquals("CREATE", ctx.candidates().get(1).operation());
    var longArgs = args("太长");
    longArgs.put("proposedContent", "字".repeat(2001));
    assertThrows(
        IllegalArgumentException.class, () -> setting.execute(tree(encode(longArgs)), ctx));
    assertEquals(2, ctx.candidates().size());
  }
}
