# Agent loop core

Run the deterministic tests with JDK 21:

```bash
cd backend
mvn test
```

`AgentLoop` owns model turn progression, whole-batch protocol validation, sequential tool execution, reply pairing, budgets, cancellation and deadline checks. `ContentTools` supplies the seven PRD tools. `ToolContext` is created per run and must never be shared between runs. `ModelGateway` makes one provider attempt and must preserve the provider's complete response; it must not execute tools or retry. `LoopTrace` methods are **durable transaction boundaries**, and their returned message sequence numbers must refer to committed messages. The runner must commit a `Completed` outcome and an optional proposal in one conditional transaction after rechecking cancellation and deadline; a candidate in `ToolContext` is never a published proposal.

This module can be tested without Spring or PostgreSQL. The repository has no content service, database migrations, run scheduler, HTTP endpoints, SSE, UI or provider integration yet. Those adapters are required before this is a deployable Mojie feature. A PostgreSQL `ContentRepository` must enforce project ownership, persist the first chapter view, read immutable revisions, and enforce search statement timeouts; a PostgreSQL `LoopTrace` must atomically save tool results, messages, evidence and events. The model adapter must apply an actual model context limit in addition to the core's conservative code point check.
