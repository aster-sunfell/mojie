# Agent run transaction boundaries

This describes the implemented database primitives for [Agent Tool Loop PRD v0.1](../prd/Agent_Tool_Loop_PRD_v0.1.md). Run admission and scheduling are still pending.

1. `JdbcContentRepository.captureChapterView` reads chapter ID, order and version in a short repeatable-read, read-only transaction. It then locks the run and writes all pinned versions in a separate short transaction. Search scans immutable revisions after that commit.
2. `JdbcLoopTrace.modelStarted` locks the run, checks input message references, reserves one model call, writes the call and `model.started` event in one transaction. The model request occurs after commit.
3. `modelFinished` writes the complete ordered assistant content blocks, call result and `model.finished` event atomically. `toolStarted` reserves one tool call and writes a step plus event. `toolFinished` writes the actual result, optional reply message, context evidence, text budget and event atomically. A result not returned to the model has no reply message; it cannot appear in a later model call's `input_message_seqs`.
4. `JdbcRunTerminalStore.finish` locks `tb_project` then `tb_agent_run`, checks cancellation/deadline, validates the final model call and that a full target-chapter reply appeared in its input references, and commits status, optional `PENDING` proposal, terminal event and conditional slot release together. A repeated finish returns the committed terminal result.

The lock order for future writers is `tb_project → tb_agent_run / tb_edit_proposal → tb_chapter`. `tb_agent_event.seq` is allocated by incrementing `tb_agent_run.last_event_seq` under the run row lock. `tb_agent_message.message_seq` is allocated under the same lock. Network model calls and chapter text scans occur outside database transactions.
