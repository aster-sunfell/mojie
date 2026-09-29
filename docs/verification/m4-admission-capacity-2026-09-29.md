# AC-17：受理互斥、满队列与资源删除

环境：Java 21、PostgreSQL 18.6，集成测试每次使用独立临时 schema。

`RunSchedulerIT.concurrentSubmissionsForOneProjectAdmitOnlyOneRun` 使用两个线程同时提交同作品、不同幂等键的请求。数据库只创建一个 run，另一项收到 `RESOURCE_BUSY`；持有容量为 1，任务完成后归零。

`RunSchedulerIT.fullQueueRejectsCleanlyAndQueuedCancellationRestoresCapacity` 让两个模型调用阻塞，占满两个运行线程，再排入八个任务。第十一个请求返回 `RUN_CAPACITY_EXCEEDED`，数据库没有多建 run；活动作品删除返回 `RESOURCE_BUSY`。取消一项排队任务后，容量由 10 降为 9，新任务能够受理。释放模型调用后，其余任务成功，已取消任务保持 `CANCELLED`，容量最终归零。

设置 `MOJIE_TEST_JDBC_URL`、`MOJIE_TEST_DB_USER`、`MOJIE_TEST_DB_PASSWORD` 后执行 `mvn -o -Dtest=RunSchedulerIT test -q`，9 个 PostgreSQL 集成用例通过。此证据覆盖受理与本地执行容量，不代替真实浏览器并发点击或长期资源泄漏检查。
