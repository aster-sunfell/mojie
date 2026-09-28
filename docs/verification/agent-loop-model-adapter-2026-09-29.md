# Anthropic adapter verification — 2026-09-29

Environment: Java 21, Maven 3.9.14, `com.anthropic:anthropic-java:2.65.0`. The adapter uses `maxRetries(0)` and a complete non-streaming Messages response.

Command: `MOJIE_TEST_LOCAL_HTTP=1 mvn -f backend/pom.xml -Dtest=AnthropicModelGatewayIT test`.

Result: 2 tests passed against a local fake HTTP server. The test inspected the actual outgoing JSON for ordered thinking and tool-use replay, `strict: true` tool schema, `additionalProperties: false`, and `is_error: true` tool result. A synthetic response with thinking, text and tool use was mapped to ordered neutral content. An HTTP 429 response produced `MODEL_RATE_LIMITED` after exactly one request.

No live Anthropic key was used. The PRD's real-model checks for a near-6,000-code-point chapter, complete candidate output, source quality and context budget remain open.
