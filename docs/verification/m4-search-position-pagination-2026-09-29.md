# AC-36/38：搜索位置游标与有界结果

`search_chapters` 现在逐章、逐行扫描，只保留本页最多 20 条命中及其来源。找到本页之后的第一条真实命中即设置 `hasMore=true`，不需要把全作品命中放进内存。签名游标记录最后返回的章节 ID、行号和码点起点，并绑定 runId、视图 ID、关键词和范围；续页可以调整 `limit`。

`AgentLoopTest` 验证 6,000 个重复单字命中的首两页位置分别为 0–19、20–26，游标可重放；另验证跨章节续页到末页无重复，以及已有的跨行、表情码点位置和非法游标场景。Java 21 的 `mvn -o -Dtest=AgentLoopTest test -q` 通过。使用当前本地 PostgreSQL 18.6 容器运行 `mvn -o verify -q` 通过，集成测试均在独立随机 schema 中运行。

`search_settings` 也只保留当前页命中及来源，用最后返回的设定 ID 续页。签名游标继续绑定 runId、关键词和类型；单元测试验证标题优先、跨设定续页时调整 `limit`、末页、重放和改词拒绝。改动后再次运行 Java 21 的 `mvn -o verify -q`，PostgreSQL 集成测试通过。

章节目录仓库现在只查询 `id/title/chapter_order/version`；只有 `chapterRevision` 才读取历史正文。这样 `list_chapters` 和搜索初始化不会先把当前作品全部正式正文加载到 JVM。`JdbcContentRepositoryIT` 检查元数据顺序和版本变化，Java 21 的完整 PostgreSQL 验证再次通过。

章节搜索按最多 32 章一批读取视图固定的历史正文。JDBC 使用绑定的 ID/版本参数一次取出该批，提交短事务后由 Java 扫描，避免每章新建一个数据库连接。`AgentLoopTest` 验证 40 章无命中时按 32/8 两批读取；`JdbcContentRepositoryIT` 验证批量返回旧版正文且跨作品请求不返回内容。完整 PostgreSQL 验证通过。

`RunSchedulerIT.thousandLongChaptersCanBeSearchedWithoutPartialResultsOrTimeout` 在 PostgreSQL 18.6 中创建 1,000 章，其中 999 章正文为 5,998 码点；模拟模型搜索 `ALL_CHAPTERS` 的无命中关键词，再完整读取目标章。任务成功，搜索返回空命中、`hasMore=false`，run 视图包含 1,000 个版本指针，来源只有目标章全文。测试断言搜索步骤低于 5.5 秒；本机 JUnit 报告显示整个用例约 1.004 秒。Java 21 的完整 `mvn -o verify -q` 通过。

此项验证了分页正确性、章节目录与历史正文批次的内存边界，以及一组 1,000 章无命中压力输入。常见查询的 20 次延迟分布、数据库扫描中取消响应和故意触发的 5 秒查询超时仍需专门量测。
