package com.mojie.agent.repository;

import com.mojie.agent.model.RunEvent;
import java.util.*;

public interface RunEventRepository {
  long nextSequence(String runId);

  int insert(String runId, long seq, String type, String payload);

  List<RunEvent> after(String runId, long afterSeq, int limit);
}
