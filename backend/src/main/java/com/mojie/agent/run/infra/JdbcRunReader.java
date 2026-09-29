package com.mojie.agent.run.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.sql.*;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;

/** Loads immutable run inputs after the worker claims a queued task. */
public final class JdbcRunReader {
    private final DataSource dataSource;
    private final JsonMapper json = JsonMapper.builder().build();
    public JdbcRunReader(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource); }

    public record RunData(String runId, String projectId, String targetChapterId, int baseVersion,
                          String baseContent, String instruction, String systemPrompt, String modelId,
                          int maxOutputTokens, int contextWindowTokens, Instant deadline) {}

    public RunData load(String runId) {
        String sql = """
                SELECT r.id, r.project_id, r.target_chapter_id, r.base_version, r.base_content,
                       r.instruction, p.content, r.model_id, r.config_snapshot, r.deadline_at
                  FROM tb_agent_run r JOIN tb_agent_prompt_snapshot p ON p.hash = r.prompt_hash
                 WHERE r.id = ?::uuid AND r.status = 'RUNNING'
                """;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Run is not active");
                JsonNode config = json.readTree(rows.getString(9));
                int maxOutputTokens = config.path("maxOutputTokens").asInt(16_000);
                int contextWindowTokens = config.path("contextWindowTokens").asInt(0);
                if (maxOutputTokens <= 0 || contextWindowTokens <= 0)
                    throw new IllegalStateException("Invalid run model configuration");
                return new RunData(rows.getString(1), rows.getString(2), rows.getString(3), rows.getInt(4),
                        rows.getString(5), rows.getString(6), rows.getString(7), rows.getString(8),
                        maxOutputTokens, contextWindowTokens,
                        rows.getObject(10, java.time.OffsetDateTime.class).toInstant());
            }
        } catch (Exception e) { throw new IllegalStateException("Run input unavailable", e); }
    }
}
