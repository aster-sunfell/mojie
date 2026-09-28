# M4 内容保存与终态恢复回归

依据 MVP PRD v0.3 的 AC-02、AC-27、AC-29。

- 正文以预期版本锁定后，若归一化内容与当前正文相同，返回当前版本且不新增历史；版本不符仍返回 `VERSION_CONFLICT`。PostgreSQL 集成测试核对版本和历史条数。
- 前端保存请求发出后继续输入，晚到响应仅更新已保存版本，不覆盖较新的草稿；下一次保存使用返回的新版本。切换章节后，旧章节保存响应也不能替换当前编辑器。Vitest 使用延迟 HTTP 响应验证这两条路径。
- PostgreSQL 提交已成功但 JDBC `commit()` 回执丢失时，终态服务首次调用报错；再次以 `PERSISTENCE_ERROR` 收尾会回读并保留已提交的 `SUCCEEDED`，不会改写结果。集成测试通过连接代理注入此故障。

验证：Java 21、PostgreSQL 18.6，设置 `MOJIE_TEST_JDBC_URL`、`MOJIE_TEST_DB_PASSWORD`、`MOJIE_TEST_LOCAL_HTTP=1` 后执行 `mvn -o -f backend/pom.xml verify -q` 通过；在 `web/` 执行 `npm test` 通过 3 个前端测试。数据库测试使用一次性容器和独立 schema。
