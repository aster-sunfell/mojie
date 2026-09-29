# Agent loop 数据库恢复核查（2026-09-30）

PRD 6.7、AL-AC-21 和 AC-27 要求：持久化故障后停止模型执行；数据库恢复后，本进程重试短事务收尾，已提交的成功不得改成失败。此前 `RunScheduler` 在 worker 收尾失败后只重试一次，再失败就丢失本进程的待收尾信息；任务只能等截止时间或进程重启。

现在 worker 两次收尾均失败时登记待收尾 run。独立的两秒扫描在数据库恢复后重试 `JdbcRunTerminalStore.finish`；该事务先检查已有终态，以免把提交回执丢失的成功改成失败。待收尾期间健康状态降级，不受理新任务。重试成功后清除登记、关闭未完成模型调用/步骤、提交 `run.failed` 事件并释放对应作品活动槽。

`RunSchedulerIT.databaseRecoverySettlesAnExitedWorkerWithoutReplayingTheModel` 使用 PostgreSQL 18.6 隔离 schema 和可切换的 DataSource：模型请求开始后切断数据库连接，令 worker 在结果持久化及收尾事务处失败并退出。此时任务仍为 RUNNING、健康状态降级；恢复连接后，扫描将任务置为 FAILED / PERSISTENCE_ERROR，且模型只请求一次、没有建议，作品活动槽为空、失败事件只有一条。测试包含于 `mvn verify`，14 个 RunSchedulerIT 场景通过。

此场景注入的是连接获取故障。事务执行中途故障、终态提交回执丢失以及真实 PostgreSQL 进程重启仍须继续验证；此记录不能签收 AL-AC-21 / AC-27 的全部组合。
