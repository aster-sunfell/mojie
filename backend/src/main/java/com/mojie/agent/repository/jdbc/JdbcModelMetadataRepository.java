package com.mojie.agent.repository.jdbc;

import com.mojie.agent.model.ModelMetadata;
import com.mojie.agent.repository.ModelMetadataRepository;
import com.mojie.common.repository.jdbc.JdbcAccess;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;

public final class JdbcModelMetadataRepository implements ModelMetadataRepository {
  private final JdbcAccess db;

  public JdbcModelMetadataRepository(DataSource source) {
    db = new JdbcAccess(source);
  }

  @Override
  public ModelMetadata find(String provider, String baseUrl, String modelId) {
    var row =
        db.first(
            """
            SELECT context_window_tokens, source, fetched_at FROM tb_model_metadata_cache
             WHERE provider = ? AND base_url = ? AND model_id = ?
            """,
            provider,
            baseUrl,
            modelId);
    return row == null
        ? null
        : new ModelMetadata(
            ((Number) row.get("contextWindowTokens")).intValue(),
            (String) row.get("source"),
            OffsetDateTime.parse(row.get("fetchedAt").toString()).toInstant());
  }

  @Override
  public void save(String provider, String baseUrl, String modelId, ModelMetadata metadata) {
    db.update(
        """
        INSERT INTO tb_model_metadata_cache(provider, base_url, model_id,
            context_window_tokens, source, fetched_at) VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (provider, base_url, model_id) DO UPDATE SET
            context_window_tokens = EXCLUDED.context_window_tokens,
            source = EXCLUDED.source, fetched_at = EXCLUDED.fetched_at
        """,
        provider,
        baseUrl,
        modelId,
        metadata.contextWindowTokens(),
        metadata.source(),
        OffsetDateTime.ofInstant(metadata.fetchedAt(), ZoneOffset.UTC));
  }
}
