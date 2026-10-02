package com.mojie.agent.repository.jdbc;

import com.mojie.agent.model.RunEvent;
import com.mojie.agent.repository.RunEventRepository;
import com.mojie.common.repository.jdbc.JdbcAccess;
import java.util.*;
import javax.sql.DataSource;

public final class JdbcRunEventRepository implements RunEventRepository {
  private final JdbcAccess db;

  public JdbcRunEventRepository(DataSource source) {
    this.db = new JdbcAccess(source);
  }

  @Override
  public long nextSequence(String runId) {
    return ((Number)
            db.first(
                    "UPDATE tb_agent_run SET last_event_seq = last_event_seq + 1, updated_at ="
                        + " now() WHERE id = ?::uuid RETURNING last_event_seq",
                    runId)
                .get("lastEventSeq"))
        .longValue();
  }

  @Override
  public int insert(String runId, long seq, String type, String payload) {
    return db.update(
        "INSERT INTO tb_agent_event(run_id, seq, type, payload) VALUES (?::uuid, ?, ?, ?::jsonb)",
        runId,
        seq,
        type,
        payload);
  }

  @Override
  public List<RunEvent> after(String runId, long afterSeq, int limit) {
    return db
        .query(
            "SELECT seq, type, payload, occurred_at FROM tb_agent_event WHERE run_id = ?::uuid AND"
                + " seq > ? ORDER BY seq LIMIT ?",
            runId,
            afterSeq,
            limit)
        .stream()
        .map(
            r -> {
              Object payload;
              try {
                payload =
                    com.fasterxml.jackson.databind.json.JsonMapper.builder()
                        .build()
                        .readTree(r.get("payload").toString());
              } catch (Exception e) {
                throw new com.mojie.common.error.StorageException("Stored event is invalid", e);
              }
              return new RunEvent(
                  ((Number) r.get("seq")).longValue(),
                  (String) r.get("type"),
                  payload,
                  java.time.Instant.parse(r.get("occurredAt").toString())
                      .atOffset(java.time.ZoneOffset.UTC)
                      .toString());
            })
        .toList();
  }
}
