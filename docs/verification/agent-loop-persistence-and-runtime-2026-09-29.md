# Agent loop persistence and runtime verification — 2026-09-29

Environment: Java 21, Maven 3.9.14, PostgreSQL 18.6 in a disposable local Docker container. Database image digest: `sha256:5a5a84b19854a9ffaa54082c166ff4ec27473a361e496e5ea167f298f2da9722`.

Command: `mvn -f backend/pom.xml verify` with `MOJIE_TEST_JDBC_URL`, `MOJIE_TEST_DB_USER`, and `MOJIE_TEST_DB_PASSWORD` pointing at the disposable database. The integration test creates a random schema, migrates it with Flyway, and drops it afterward.

Result: 18 deterministic loop tests and 4 PostgreSQL integration scenarios passed. Flyway applied V1–V4 in order; PostgreSQL reported 13 `tb_` tables and 28 foreign key constraints. The integration tests verified that a chapter view retains the first read version after a newer revision appears, that a target read and two model calls persist their messages, steps, source and six progress events, and that success adds the seventh terminal event. They also verified candidate publication only at successful terminal commit, no正文 mutation during proposal, cancellation precedence over a late completed outcome, idempotent admission, queue/worker capacity, queued and running cancellation, independent timeout sweep and restart cleanup. Proposal decisions additionally covered repeated accept/reject, a persisted version conflict, exactly one linked AGENT revision, and concurrent accept/reject arbitration.

This does not establish an end-to-end UI or real-model result. Content editing, HTTP/SSE and provider adapter remain to be implemented and verified.
