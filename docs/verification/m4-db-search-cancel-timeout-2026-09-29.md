# AL-AC-18/19：数据库搜索超时与取消

在 PostgreSQL 18.6 的独立测试 schema 中，`JdbcContentRepositoryIT` 用排他表锁阻塞历史正文批量读取。100 ms 的数据库 `statement_timeout` 产生 `SearchTimeoutException`，释放锁后同一历史版本仍可读取。`AgentLoopTest` 让第二批读取超时，确认前一批已找到的命中不会作为部分搜索结果或来源返回，工具只返回 `SEARCH_TIMEOUT` 且 `retryableByModel=false`。

初次实测发现，单靠 Worker 线程中断无法在 2 秒内结束被 PostgreSQL 查询阻塞的读取。现在搜索时的章节目录、版本视图、历史正文批次和设定查询会登记当前 JDBC 语句；运行中取消或超时扫描会调用 `Statement.cancel()`，并保留数据库 `statement_timeout` 兜底。取消引发的 SQL 中断按任务取消处理，不显示为普通搜索超时。

`RunSchedulerIT.cancellingAQueryBlockedInPostgresEndsTheRunWithoutPartialEvidence` 在模型发出 `search_settings` 后锁住设定表，确认查询进入 PostgreSQL 的锁等待，再取消任务。Worker 在 2 秒内释放容量，任务终态为 `CANCELLED`；没有搜索来源或章节视图，未完成的工具步骤标为 `ABORTED`。Java 21 的 `mvn -o verify -q` 在本地 PostgreSQL 上通过。

`RunSchedulerIT.cancellingBlockedViewTransactionDoesNotWaitForTheDatabaseLock` 另锁住章节视图表，让搜索在持有 run 行锁的视图事务中等待数据库。取消先向本地任务发信号并中止当前语句，再写持久取消请求，避免请求本身等待该 run 行锁。测试确认 2 秒内终态和容量恢复，视图与来源没有部分写入。取消 API 可能读到处理中 `RUNNING`，也可能读到已完成的 `CANCELLED`；两种回执都由最终终态与数据一致性校验。

这些测试覆盖数据库锁等待中的超时、取消与版本视图事务仲裁。实际 HTTP 取消往返时延、1,000 章扫描中途取消，以及数据库服务整体不可用时的恢复仍需另测。
