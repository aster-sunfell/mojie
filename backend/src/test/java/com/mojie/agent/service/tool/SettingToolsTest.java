package com.mojie.agent.service.tool;

import static com.mojie.common.model.JsonSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SettingToolsTest {
  private final AgentTool tool =
      ContentTools.all(null, "test-tool-key-123456".getBytes()).stream()
          .filter(t -> t.spec().name().equals("propose_setting_edit"))
          .findFirst()
          .orElseThrow();

  private ToolContext context() {
    var ctx = new ToolContext("run", "project", null, 0, null);
    ctx.unified(true);
    ctx.readSettings().put("s", new ToolContext.SettingInput("CHARACTER", "林舟", "不会游泳", 2, "hash"));
    return ctx;
  }

  private com.fasterxml.jackson.databind.JsonNode args(int version, String content) {
    return tree(
        encode(
            Map.of(
                "settingId",
                "s",
                "expectedVersion",
                version,
                "proposedType",
                "CHARACTER",
                "proposedTitle",
                "林舟",
                "proposedContent",
                content,
                "reason",
                "调整动机")));
  }

  @Test
  void staleAndUnprovidedReadsCannotCreateCandidates() {
    var ctx = context();
    assertEquals("INSUFFICIENT_CONTEXT", tool.execute(args(2, "过桥"), ctx).error().code());
    ctx.markReadsProvided();
    assertEquals("VERSION_CONFLICT", tool.execute(args(1, "过桥"), ctx).error().code());
    assertEquals("VALIDATION_ERROR", tool.execute(args(2, "不会游泳"), ctx).error().code());
    assertNull(ctx.candidate());
    assertTrue(tool.execute(args(2, "过桥"), ctx).success());
    assertEquals("不会游泳", ctx.candidate().baseContent());
    assertEquals("SETTING", ctx.candidate().targetType());
    assertEquals("PROPOSAL_ALREADY_EXISTS", tool.execute(args(2, "乘船"), ctx).error().code());
  }

  @Test
  void analysisOnlyAndLegacyTurnsCannotProposeSettingEdits() {
    var ctx = context();
    ctx.markReadsProvided();
    ctx.analysisOnly(true);
    assertEquals("EDIT_DISABLED", tool.execute(args(2, "过桥"), ctx).error().code());
    ctx.analysisOnly(false);
    ctx.unified(false);
    assertEquals("EDIT_DISABLED", tool.execute(args(2, "过桥"), ctx).error().code());
    assertNull(ctx.candidate());
  }

  @Test
  void settingLengthLimitIsEnforcedBeforeStoringCandidate() {
    var ctx = context();
    ctx.markReadsProvided();
    assertThrows(
        IllegalArgumentException.class, () -> tool.execute(args(2, "字".repeat(2001)), ctx));
    assertNull(ctx.candidate());
    assertTrue(tool.execute(args(2, "字".repeat(2000)), ctx).success());
  }
}
