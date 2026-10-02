package com.mojie.agent.service.port;

import java.util.List;
import java.util.function.Consumer;

public interface RunExecutor extends RuntimeStatus {
  interface Reservation extends AutoCloseable {
    @Override
    void close();
  }

  interface ExecutionControl {
    boolean cancelled();

    void onCancel(Runnable callback);
  }

  record Execution(String projectId, String runId, Consumer<ExecutionControl> work) {}

  record PendingTerminal(String projectId, String runId, String code, String detail) {}

  Reservation reserve();

  void schedule(Reservation reservation, Execution execution);

  void signalCancel(String projectId, String runId, boolean terminal);

  void deferTerminal(PendingTerminal pending);

  List<PendingTerminal> pendingTerminals();

  void terminalRecovered(PendingTerminal pending);
}
