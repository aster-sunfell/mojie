# Agent loop persistence verification — 2026-09-28

Environment: Java 21, Maven 3.9.14, PostgreSQL 18.6 in a disposable local Docker container. Database image digest: `sha256:5a5a84b19854a9ffaa54082c166ff4ec27473a361e496e5ea167f298f2da9722`.

Command: `mvn -f backend/pom.xml verify` with `MOJIE_TEST_JDBC_URL`, `MOJIE_TEST_DB_USER`, and `MOJIE_TEST_DB_PASSWORD` pointing at the disposable database. The integration test creates a random schema, migrates it with Flyway, and drops it afterward.

Result: 18 deterministic loop tests and 1 PostgreSQL integration test passed. Flyway applied V1–V4 in order; PostgreSQL reported 13 `tb_` tables and 28 foreign key constraints. The integration test verified that a chapter view retains the first read version after a newer revision appears, that a target read and two model calls persist their messages, steps, source and six progress events, and that success adds the seventh terminal event. It also verified candidate publication only at successful terminal commit, no正文 mutation during proposal, and cancellation precedence over a late completed outcome.

This does not establish an end-to-end UI or real-model result. Run admission, bounded scheduling, independent timeout sweep, content editing, proposal acceptance, HTTP/SSE and provider adapter remain to be implemented and verified.
