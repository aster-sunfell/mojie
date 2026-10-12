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

The integration tests create and drop their own random schemas. They apply V1–V9 through Flyway and verify chapter view pinning, trace atomicity, terminal success, proposal publication and adoption, idempotent admission, bounded scheduling, cancellation and timeout precedence.

Both model adapters have opt-in local HTTP tests:

```bash
MOJIE_TEST_LOCAL_HTTP=1 mvn -Dtest=AnthropicModelGatewayIT,ChatCompletionsModelGatewayIT test
```

Both adapters use `MOJIE_MODEL_API_KEY` and `MOJIE_MODEL_BASE_URL`; the latter is an HTTP(S) origin without `/v1`. `MOJIE_MODEL_API_PATH` selects `/v1/messages` or `/v1/chat/completions` and rejects every other value at startup. The old `ANTHROPIC_API_KEY` and `MOJIE_ANTHROPIC_BASE_URL` are accepted only for Messages when the corresponding generic value is absent. Requests are non-streaming, use the run's remaining timeout, and make one HTTP attempt. The local tests do not evaluate a real model.

Set `MOJIE_MODEL_ID` explicitly when using the generic API key. Model settings bind through `ModelProperties` from `src/main/resources/application.yaml`. Context windows are resolved in the background from the configured provider's `/v1/models` when it supplies an explicit total-context field, otherwise from the matching provider and exact model ID in [models.dev](https://models.dev/api.json). SiliconFlow China and global endpoints are separate providers; `Pro/` IDs are never treated as aliases. Custom origins require provider metadata or the optional `MOJIE_MODEL_CONTEXT_TOKENS` override. API credentials are sent only to the configured model service, never to the public catalog, and redirects are not followed.

`ModelMetadataResolver` persists the window, source and retrieval time in `tb_model_metadata_cache`, keyed by provider, origin and model ID. Refresh defaults to 24 hours with 5-second request timeouts; failed refreshes retain cache entries no older than 7 days and retry at most once per minute. These settings live under `mojie.model.metadata` in YAML and can be overridden through Spring configuration. Without credentials, no metadata request is made. Content editing and health remain available without metadata; new task admission returns HTTP 503 / `MODEL_METADATA_UNAVAILABLE`. Capabilities expose `modelMetadataAvailable`, and the client checks for recovery. Each accepted run snapshots its resolved window, source and retrieval time alongside its API path, so a later refresh does not change an active or historical task. Idempotent retries of an existing run remain available when metadata is temporarily missing.

Catalog entries are reference metadata rather than a provider guarantee: the SiliconFlow V3.2 entry currently reports 164000, whereas the historical provider listing used 163840. The optional override can supply an exact provider-specific limit. Before a model call, the loop separately checks the 200,000-code-point application cap and a conservative UTF-8-byte input bound plus output allowance and protocol reserve against the configured token window. This is a preflight bound, not an exact provider tokenizer count; model-side context errors still need classification and real-model validation. Both paths share admission, capabilities, the agent loop and persistence. Chat Completions preserves `reasoning_content` across tool turns as an opaque protocol field, separate from the final analysis (see the [SiliconFlow protocol](https://docs.siliconflow.cn/docs/api/chat-completions-post)). Both clients close with the application. Local HTTP coverage includes tool/result round trips, malformed responses, finish reasons, refusal, authentication, rate limits, timeout, network failure and interrupted requests.

`AgentLoop` owns model turn progression, whole-batch protocol validation, sequential tool execution, reply pairing, budgets, cancellation and deadline checks. `ContentTools` supplies the seven PRD tools. `ToolContext` is created per run and must never be shared between runs. `ModelGateway` makes one provider attempt and must preserve the provider's complete response; it must not execute tools or retry. `RunTraceService` implements **durable transaction boundaries**, and its message sequence numbers refer to committed messages. `RunCompletionService` commits a `Completed` outcome and an optional proposal in one conditional transaction; a candidate in `ToolContext` is never a published proposal.

The system prompt tells the model the default request/tool budgets, asks it to reserve a final response after any candidate submission, and allows independent calls with known arguments in one response. Tool execution remains sequential and the runtime enforces the same limits. This addresses the [V3.2 recovery audit](../docs/verification/agent-loop-deepseek-v3.2-recovered-2026-10-01.json), where three of five runs requested more tools on the last allowed model turn; it does not guarantee completion or remove the separate long-output timeout risk.

Each actual tool reply also contains a `budget` snapshot with used/remaining model and tool calls and `finalResponseRequired`. The last reply of a batch has the latest counts; failed tools consume budget too. This metadata is stored with the identical step result and tool message and counts toward the tool-text cap. The model still chooses its tools; requesting more tools on the last allowed response remains a run failure.

The repository includes the existing PostgreSQL schema and JDBC repositories. `RunService` owns idempotent submission and cancellation; `AgentRunner` coordinates one execution; `BoundedRunExecutor` retains two workers, eight queue positions and ten permits. `StartupLifecycle` applies migrations and fails old-process tasks before readiness. `TimeoutSweeper` scans every two seconds and retries pending terminal commits. `ProposalService` accepts or rejects a published proposal in a separate transaction, with version conflict and single-history protection. The Messages and Chat Completions gateways map complete provider responses into the same loop protocol. Opaque provider blocks remain in the stored assistant message for accurate continuation, while the local developer trace redacts their contents before returning them over HTTP.


## Backend layers

Business modules use `api`, `service`, `model` and `repository`; SQL implementations live in `repository.jdbc`. Controllers receive concrete services. One instance manages all content for a single author; no deployment identity or owner parameter is required. Service rules and short transactions depend on repository interfaces and `TransactionRunner`; repositories use `DataSourceUtils` through `JdbcAccess` and never commit or roll back on their own. Queries cross the repository boundary as typed records; service response maps preserve the existing JSON fields, nulls and time strings.

`ChapterSaveCoordinator` joins chapter content, immutable history, proposal decisions and revision links in one REQUIRED transaction. It locks project, proposals in stable ID order, then chapter. `ProposalService` uses the same content writer for adoption and returns a committed conflict result for HTTP 409. `ContentReadService` provides tool reads; `ToolContentService` pins and commits a chapter view before scanning history.

`agent.service.loop` and the tool contracts remain portable Java. Model ports live in `agent.service.port`; protocol implementations and local worker resources live in `infrastructure`. `config` constructs these components, and the Spring-managed SSE adapter lives in `agent.api.sse`.

ArchUnit's `LayerRulesTest` and `ModuleRulesTest` have no legacy exclusions. PostgreSQL tests cover transaction propagation, independent read-only repeatable-read snapshots, multiple-proposal rollback and concurrent saves, as well as all migrated cancellation, timeout, capacity and fault assertions. The repeatable baseline/candidate HTTP comparison is `scripts/check-backend-refactor-contract.py`. See [the refactoring verification](../docs/verification/backend-three-layer-refactoring-2026-10-02.md) for commands and actual counts.

## HTTP service

For local development, run `sh scripts/start-local-backend.sh` from the repository root. The script builds the JAR and exports `deploy/.env` to Java. The API listens on port 8080. Configure `MOJIE_DB_URL`, `MOJIE_DB_USER`, `MOJIE_DB_PASSWORD`, `MOJIE_PORT`, `MOJIE_MODEL_ID` and `MOJIE_CURSOR_KEY` as needed. This release does not implement multi-user authentication. Set `MOJIE_MODEL_API_KEY` to enable task submission and set `MOJIE_MODEL_API_PATH` to choose the protocol. `MOJIE_DEV_TRACE=true` enables the controlled trace endpoint. `MOJIE_DB_CONNECTION_TIMEOUT_MS` defaults to 5000 so database outages return a bounded error. Spring's Flyway auto initialization is disabled because `StartupLifecycle.start()` owns migration and restart cleanup before readiness.

`/api/v1` exposes project/chapter/setting CRUD, immutable chapter revisions, run submission/list/detail/cancellation, committed SSE event replay, proposal detail/accept/reject, health and capabilities. The web frontend lives in `../web`. Vite proxies `/api` to port 8080 by default; use `MOJIE_API_URL` to target another local port. Search database statements use a local PostgreSQL timeout bounded by five seconds and the run's remaining time.

Project, chapter, setting, revision and run lists return `data: {items, nextCursor}`. Pass the opaque `nextCursor` back as `cursor` with the same resource and filters to continue; `null` marks the final page. The default page size is 20 and the maximum is 50. Set a stable, private `MOJIE_CURSOR_KEY` (at least 16 bytes) across server restarts and instances; changing it invalidates outstanding list cursors.
The route inventory is in `../docs/api/openapi.yaml`; event replay details are in `../docs/api/HTTP_SSE_contract_v0.1.md`.

SSE admits at most 20 active subscriptions. An additional connection receives HTTP 429 / `STREAM_CAPACITY_EXCEEDED`; run queries remain available. Each connection has at most one in-flight batch, with bounded delivery workers separate from the 500 ms timer and the Agent workers. Terminal completion and detected disconnects release the slot once. The HTTP regression in `web/e2e/agent-loop.spec.js` opens 20 streams, verifies rejection of the 21st, and replays terminal events 25 times to check capacity recovery.

The [2026-09-30 real-model verification](../docs/verification/agent-loop-real-model-2026-09-30.md) covers analysis, searches, and a complete 5,910-code-point proposal. [Browser tests](../docs/verification/browser-agent-loop-2026-09-30.md) cover basic refresh, disconnected SSE recovery, draft protection, adoption, and cancellation through the real Java API. [Database recovery verification](../docs/verification/agent-loop-db-recovery-2026-09-30.md) covers in-process terminal retry after connection loss and atomic rollback during tool and success commits. Remaining release gates include unverified browser timing races, further failure combinations, full PRD acceptance sign-off, and provider-tokenizer validation of the context preflight.

## Repeatable live-model verification

The previous successful live report used DeepSeek V4 Flash. For SiliconFlow V3.2, the exact model ID is `deepseek-ai/DeepSeek-V3.2`, and the historical explicit override `MOJIE_MODEL_CONTEXT_TOKENS=163840` matched the provider's [model listing](https://siliconflow.cn/models?page=1&q=DeepSeek-V3.2). The optional `Pro/` variant is a different provider ID and must be recorded separately. Configuration validity and a successful single request do not prove that a multi-turn run can finish. The [initial V3.2 audit](../docs/verification/agent-loop-deepseek-v3.2-2026-10-01.json) records the original configuration failure, timeouts and provider rate limits.

After billing recovered, three rounds of the five fixed materials used standard V3.2 only. The [latest review](../docs/verification/agent-loop-deepseek-v3.2-review-2026-10-01.json) records 3/5 technical passes after adding budget feedback: short proposal/adoption, synonymous evidence, and insufficient evidence. The latest readonly run hit the run deadline and the 5,900-code-point case hit a request timeout; neither had a published proposal. Output quality also remains incomplete: one successful analysis exposed an internal budget field and a process preface. Earlier successes, all failures, source inspections and unchanged limits are preserved in the [acceptance audit](../docs/verification/agent-loop-al-ac-audit-2026-09-30.md). This establishes real V3.2 tool-loop capability on short cases, not stable completion or release acceptance.

Start the Java API against a dedicated test database, with a real model configured and `MOJIE_DEV_TRACE=true`. Do not point the verification at an author's working database. From the repository root:

```bash
python3 scripts/check-agent-loop-live.py \
  --base-url http://127.0.0.1:18082/api/v1 \
  --model deepseek-ai/DeepSeek-V3.2 \
  --output /tmp/mojie-live-new-attempt.json
```

The script uses Python's standard library only as an HTTP test client; the application still runs without Python. It creates separate three-chapter/three-setting projects for the five PRD materials: no change, contradiction with adoption, a 5,900-code-point whole-chapter edit, an empty literal search with synonymous evidence, and insufficient evidence. It checks full target delivery, unchanged official text before adoption, exact whole-chapter preservation, one-time adoption and terminal SSE replay. It exits nonzero on failed technical assertions and saves failed runs as well as successful ones. Existing output files are never overwritten; `--cases readonly edit long synonym insufficient` can select a subset for a separately recorded attempt. Before creating any fixtures or tasks, `--model` must match the server's `/system/capabilities` model ID. Each result also checks the persisted model ID and records the allowlisted configuration from the run's trace. This option never changes server configuration. Inspect the saved final analyses and evidence manually for literary correctness and honest coverage.

## Single-author schema migration

V8 removes `owner_id` from projects and runs without deleting content or history. All previously separated owners' resources become visible to this instance. Task idempotency is now instance-wide via `UNIQUE(client_request_id)`. Migration reports conflicting historical request IDs and rolls back if different owners reused a key; resolve those records explicitly before retrying. Existing upstream V1–V7 migrations remain unchanged. V9 adds the metadata cache. Stop the old backend before deploying the new schema and application together; an older backend cannot use the schema after V8.

The merged migration sequence keeps upstream V6/V7 for conversations and Skills, with single-author conversion in V8 and model metadata in V9. V8 also removes owner columns from conversations, Skills, uploads and artifacts, and aggregates storage quotas into one instance-wide row. Duplicate enabled Skill names stop migration for explicit resolution. Databases initialized from the earlier local branch using V6 `single_author` / V7 `model_metadata_cache` have a different Flyway history; do not run `repair` or rename history entries to bypass validation. Back up that database and migrate it separately before using this merged build.

## Unified conversation history migration

V11 removes `tb_conversation.status` and its check constraint. Active and previously archived conversations appear together, ordered by creation time; IDs, messages, run references, timestamps and original model configurations remain intact. The list endpoint no longer filters by status, and PATCH accepts title, discoveryEnabled and expectedVersion. Conversation responses no longer contain status; message and run statuses are unchanged. Previously issued conversation-list cursors must be discarded and the list reloaded.

Back up the database before release and stop the old backend before applying V11. Deploy the frontend, backend and schema together. An older backend cannot use the schema after V11; rolling back requires the matching pre-migration database backup. Removing archive restrictions does not bypass model configuration compatibility checks on legacy conversations.
