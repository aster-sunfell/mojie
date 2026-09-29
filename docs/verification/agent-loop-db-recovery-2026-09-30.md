# Agent loop 数据库恢复核查（2026-09-30）

PRD 6.7、AL-AC-21 和 AC-27 要求：持久化故障后停止模型执行；数据库恢复后，本进程重试短事务收尾，已提交的成功不得改成失败。此前 `RunScheduler` 在 worker 收尾失败后只重试一次，再失败就丢失本进程的待收尾信息；任务只能等截止时间或进程重启。

现在 worker 两次收尾均失败时登记待收尾 run。独立的两秒扫描在数据库恢复后重试 `JdbcRunTerminalStore.finish`；该事务先检查已有终态，以免把提交回执丢失的成功改成失败。待收尾期间健康状态降级，不受理新任务。重试成功后清除登记、关闭未完成模型调用/步骤、提交 `run.failed` 事件并释放对应作品活动槽。

`RunSchedulerIT.databaseRecoverySettlesAnExitedWorkerWithoutReplayingTheModel` 使用 PostgreSQL 18.6 隔离 schema 和可切换的 DataSource：模型请求开始后切断数据库连接，令 worker 在结果持久化及收尾事务处失败并退出。此时任务仍为 RUNNING、健康状态降级；恢复连接后，扫描将任务置为 FAILED / PERSISTENCE_ERROR，且模型只请求一次、没有建议，作品活动槽为空、失败事件只有一条。

`RunSchedulerIT.toolFinishEventFailureRollsBackStepReplyAndEvidence` 在 `tool.finished` 事件插入前由 PostgreSQL trigger 注入异常。工具步骤从 STARTED 进入终态失败时标为 ABORTED，但未出现成功步骤、TOOL 消息、来源、工具完成事件或工具文本计数的部分提交；任务以 PERSISTENCE_ERROR 结束，活动槽释放。

`RunSchedulerIT.successEventFailureRollsBackProposalStatusAndActivitySlot` 在 `run.succeeded` 事件插入前注入异常。此前已持久化的候选工具步骤保留作诊断，成功事务中的任务状态、建议和成功事件均回滚；调度器随后以 PERSISTENCE_ERROR 提交唯一失败事件并释放活动槽。`JdbcContentRepositoryIT` 另以丢失终态提交回执的 DataSource 证明：成功事务已提交时，重试先回读 SUCCEEDED，不覆盖为失败。

这些测试包含于 `mvn verify`。已覆盖 AL-AC-21/27 的连接获取、事务中途异常、终态提交结果未知三种路径；真实 PostgreSQL 进程故障与跨进程恢复仍未注入验证，不能仅据本记录签收所有故障组合。
