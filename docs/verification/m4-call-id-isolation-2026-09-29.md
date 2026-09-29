# AC-23：并发任务复用提供方调用 ID

`RunSchedulerIT.concurrentRunsMayReuseProviderAndToolCallIdsWithoutMixingResults` 在 PostgreSQL 18.6 的独立临时 schema 中同时运行两个作品的任务。模型网关用屏障保证两次首轮请求重叠，向两个 run 返回相同的 `providerRequestId=shared-provider-request` 和 `toolCallId=shared-tool-call`，然后要求各自读取本作品目标章。

每个模型的第二轮输入必须包含本作品独有正文，不能包含另一作品正文。两个任务均成功后，按 runId 查询持久 trace，确认模型请求 ID 可重复、工具调用 ID 可重复，但工具结果和 `FULL_CHAPTER` 来源分别指向正确正文与章节。`AgentLoopTest` 另覆盖同一响应内多个工具按顺序执行、参数错误回填，以及批内重复 ID 整批拒绝。

验证命令：Java 21，设置本地 `MOJIE_TEST_JDBC_URL`、`MOJIE_TEST_DB_USER`、`MOJIE_TEST_DB_PASSWORD` 后执行 `mvn -o -Dtest=RunSchedulerIT test -q`，6 个 PostgreSQL 集成用例通过。此测试验证确定性响应的隔离，不评估真实模型的工具选择质量。
