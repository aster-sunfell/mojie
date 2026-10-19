package com.mojie.skill;

import static com.mojie.common.model.JsonSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.mojie.infrastructure.skill.FileSkillStorage;
import com.mojie.skill.service.SkillPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillPolicyTest {
  @TempDir Path data;

  @Test
  void reportsUnsupportedRequirementsWithoutTreatingDeclarationsAsPermissions() {
    var compatible =
        SkillPolicy.compatibility(
            List.of("SKILL.md", "references/review.md"),
            tree("{\"allowed-tools\":[\"read_chapter\",\"read_settings\"]}"));
    assertEquals(false, compatible.get("scriptExecution"));
    assertEquals(List.of(), compatible.get("warnings"));
    var restricted =
        SkillPolicy.compatibility(
            List.of(
                "SKILL.md", "scripts/check.py", "helper.js", "scripts/", "references/review.md"),
            tree(
                "{\"allowed-tools\":\"read_chapter Bash"
                    + " WebFetch\",\"mcpServers\":{\"remote\":{}}}"));
    assertEquals(List.of("helper.js", "scripts/check.py"), restricted.get("scripts"));
    assertEquals(List.of("Bash", "WebFetch"), restricted.get("unsupportedTools"));
    assertEquals(3, ((List<?>) restricted.get("warnings")).size());
    assertEquals("CONTEXT_ONLY", restricted.get("mode"));
  }

  @Test
  void publishedGuideTemplateCanBeImportedWithoutUnsupportedRequirements() throws Exception {
    String guide = Files.readString(Path.of("../docs/user-guide/skills.md"));
    String template = guide.split("```markdown\\n", 2)[1].split("\\n```", 2)[0];
    var published =
        new FileSkillStorage(data)
            .publish(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                Map.of("SKILL.md", template.getBytes(StandardCharsets.UTF_8)));
    assertEquals("人物一致性审稿", published.metadata().get("name"));
    assertEquals(
        List.of(),
        SkillPolicy.compatibility(List.of("SKILL.md"), tree(encode(published.metadata())))
            .get("warnings"));
  }
}
