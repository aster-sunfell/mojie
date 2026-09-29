# AC-22：旧执行实例晚清理与新任务隔离

环境：Java 21、PostgreSQL 18.6 固定镜像；`RunSchedulerIT.lateCancelledWorkerCannotClearNextRunForSameProject` 在独立临时 schema 执行。

测试让同作品任务 A 的模型调用在取消后继续阻塞，写入已到期截止时间，使独立扫描将 A 收为 `CANCELLED`，释放数据库活动槽，但 A 的本地 worker 仍占用容量。随后提交任务 B 并等待其模型调用开始；此时 A 的再次取消和晚到 worker 清理均不能清除 B 的 `active_run_id`、改变 B 的 `RUNNING` 状态或释放 B 的容量。最后 B 成功结束，活动槽和容量归零。两个任务的模型响应使用相同的提供方调用 ID，测试中的重点是活动槽与执行句柄隔离。

测试夹具通过 SQL 建立第一章时也将 `next_chapter_order` 设为 2，保持与内容服务创建下一章的编号规则一致。

验证命令（Java 21 环境）：`MOJIE_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/mojie MOJIE_TEST_DB_USER=mojie MOJIE_TEST_DB_PASSWORD=... MOJIE_TEST_LOCAL_HTTP=1 mvn -o verify -q`。完整 Maven 验证通过。此证据覆盖确定性并发状态；不代表长期资源泄漏测试或真实模型服务的取消响应速度。
