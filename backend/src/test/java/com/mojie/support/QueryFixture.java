package com.mojie.support;

import com.mojie.agent.model.RunEvent;
import com.mojie.agent.service.*;
import com.mojie.common.pagination.Page;
import com.mojie.proposal.service.ProposalService;
import java.util.*;
import javax.sql.DataSource;

public final class QueryFixture {
  private final RunQueryService query;
  private final ProposalService proposals;
  private final RunEventQueryService events;

  public QueryFixture(DataSource source) {
    query = TestServices.query(source);
    proposals = TestServices.proposals(source);
    events = TestServices.events(source);
  }

  public Map<String, Object> detail(String owner, String id) {
    return query.detail(owner, id);
  }

  public Map<String, Object> trace(String owner, String id) {
    return query.trace(owner, id);
  }

  public Map<String, Object> proposal(String owner, String id) {
    return proposals.detail(owner, id);
  }

  public List<RunEvent> events(String owner, String id, long after, int limit) {
    return events.events(owner, id, after, limit);
  }

  public List<Map<String, Object>> list(
      String owner, String project, String chapter, String status, int limit) {
    return query.list(owner, project, chapter, status, limit);
  }

  public Page<Map<String, Object>> page(
      String owner, String project, String chapter, String status, String cursor, int limit) {
    return query.page(owner, project, chapter, status, cursor, limit);
  }
}
