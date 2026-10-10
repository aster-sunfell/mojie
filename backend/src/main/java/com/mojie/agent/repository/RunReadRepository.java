package com.mojie.agent.repository;

import com.mojie.agent.model.*;
import java.util.*;

public interface RunReadRepository {
  RunSnapshot scoped(String runId);

  List<RunSource> listSources(String runId);

  List<RunStep> listSteps(String runId);

  List<TraceSource> listSourcesDetailed(String runId);

  List<TraceStep> listStepsDetailed(String runId);

  List<TraceMessage> listMessages(String runId);

  List<TraceModelCall> listModelCalls(String runId);

  ModelSelection modelSelection(String runId);

  List<RunSummary> page(
      String projectId,
      String chapterId,
      String status,
      String afterTime,
      String afterId,
      int size);

  RunInputRow inputs(String runId);
}
