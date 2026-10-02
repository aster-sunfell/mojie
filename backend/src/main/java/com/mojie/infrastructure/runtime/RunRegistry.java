package com.mojie.infrastructure.runtime;

import com.mojie.agent.service.port.RunExecutor.PendingTerminal;
import java.util.concurrent.*;

public final class RunRegistry {
  final ConcurrentMap<String, RunHandle> handles = new ConcurrentHashMap<>();
  final ConcurrentMap<String, PendingTerminal> pending = new ConcurrentHashMap<>();
}
