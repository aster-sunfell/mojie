# Agent loop core

Run deterministic tests with JDK 21:

```bash
cd backend
mvn test
```

Run the PostgreSQL integration test against an isolated PostgreSQL 18 database:

```bash
MOJIE_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/postgres \
MOJIE_TEST_DB_USER=postgres MOJIE_TEST_DB_PASSWORD=your_test_password mvn verify
```

The integration tests create and drop their own random schemas. They apply V1–V4 through Flyway and verify chapter view pinning, trace atomicity, terminal success, proposal publication and adoption, idempotent admission, bounded scheduling, cancellation and timeout precedence.

The Anthropic adapter also has an opt-in local HTTP test:

```bash
MOJIE_TEST_LOCAL_HTTP=1 mvn -Dtest=AnthropicModelGatewayIT test
```

Production credentials are read only from `ANTHROPIC_API_KEY`. The SDK client uses a 10 second connect timeout, a 60 second maximum request timeout, per-run remaining-time overrides and `maxRetries(0)`. The local test checks ordered thinking/tool blocks, strict schema serialization and exactly one HTTP attempt on a 429 response. It does not evaluate a real model.

`AgentLoop` owns model turn progression, whole-batch protocol validation, sequential tool execution, reply pairing, budgets, cancellation and deadline checks. `ContentTools` supplies the seven PRD tools. `ToolContext` is created per run and must never be shared between runs. `ModelGateway` makes one provider attempt and must preserve the provider's complete response; it must not execute tools or retry. `JdbcLoopTrace` implements **durable transaction boundaries**, and its message sequence numbers refer to committed messages. `JdbcRunTerminalStore` commits a `Completed` outcome and an optional proposal in one conditional transaction; a candidate in `ToolContext` is never a published proposal.

The repository includes 13 PostgreSQL tables, JDBC adapters and `RunScheduler`. The scheduler applies migrations, fails old-process tasks before readiness, admits runs with idempotency and a 2+8 capacity limit, and scans timeouts every two seconds. `JdbcEditProposalService` accepts or rejects a published proposal in a separate transaction, with version conflict and single-history protection. `AnthropicModelGateway` maps one complete Messages API response into the neutral loop protocol.

## HTTP service

Build the executable JAR with `mvn package` and run `java -jar target/mojie-agent-core-0.1.0-SNAPSHOT-exec.jar`. The API listens on port 8080. Configure `MOJIE_DB_URL`, `MOJIE_DB_USER`, `MOJIE_DB_PASSWORD`, `MOJIE_PORT`, `MOJIE_OWNER_ID`, `MOJIE_MODEL_ID` and `MOJIE_CURSOR_KEY` as needed. The single-author local default is `MOJIE_OWNER_ID=local-author`; this release does not implement multi-user authentication. Set `ANTHROPIC_API_KEY` to enable task submission. `MOJIE_ANTHROPIC_BASE_URL` can target a local compatible test server; leave it unset for the real provider. `MOJIE_DEV_TRACE=true` enables the controlled trace endpoint. Spring's Flyway auto initialization is disabled because `RunScheduler.start()` owns migration and restart cleanup before readiness.

`/api/v1` exposes project/chapter/setting CRUD, immutable chapter revisions, run submission/list/detail/cancellation, committed SSE event replay, proposal detail/accept/reject, health and capabilities. The web frontend lives in `../web`. Vite proxies `/api` to port 8080 by default; use `MOJIE_API_URL` to target another local port. Search database statements use a local PostgreSQL timeout bounded by five seconds and the run's remaining time.

Project, chapter, setting, revision and run lists return `data: {items, nextCursor}`. Pass the opaque `nextCursor` back as `cursor` with the same resource and filters to continue; `null` marks the final page. The default page size is 20 and the maximum is 50. Set a stable, private `MOJIE_CURSOR_KEY` (at least 16 bytes) across server restarts and instances; changing it invalidates outstanding list cursors.

Remaining release gates: browser end-to-end recovery coverage, real-model analysis and 6,000-code-point proposal evaluation, and model-specific context-window calibration. The current Java context estimate is conservative but has not been validated against an actual model tokenizer.
