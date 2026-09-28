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

The integration tests create and drop their own random schemas. They apply V1–V4 through Flyway and verify chapter view pinning, trace atomicity, terminal success, proposal publication, idempotent admission, bounded scheduling, cancellation and timeout precedence.

`AgentLoop` owns model turn progression, whole-batch protocol validation, sequential tool execution, reply pairing, budgets, cancellation and deadline checks. `ContentTools` supplies the seven PRD tools. `ToolContext` is created per run and must never be shared between runs. `ModelGateway` makes one provider attempt and must preserve the provider's complete response; it must not execute tools or retry. `JdbcLoopTrace` implements **durable transaction boundaries**, and its message sequence numbers refer to committed messages. `JdbcRunTerminalStore` commits a `Completed` outcome and an optional proposal in one conditional transaction; a candidate in `ToolContext` is never a published proposal.

The repository now includes 13 PostgreSQL tables, JDBC adapters and `RunScheduler`. The scheduler applies migrations, fails old-process tasks before readiness, admits runs with idempotency and a 2+8 capacity limit, and scans timeouts every two seconds. It still needs content management services, HTTP/SSE endpoints, UI and a real provider adapter before this is a deployable Mojie feature. The provider adapter must apply an actual model context limit in addition to the core's conservative code point check. Search query and statement timeouts must be enforced by the content service or database connection when integrated.
