# AC-16：活动任务实际进程重启

环境：Java 21、PostgreSQL 18.6；为本次检查创建独立 `mojie_restart_test` 数据库，检查完成后删除。Java 可执行 JAR 由项目构建产生；本地假 Anthropic Messages 端点只提供确定性响应，没有真实模型密钥。

`node scripts/check-active-run-restart.mjs` 自行启动 Java API 进程和假模型。第一个 run 调用 `read_chapter`、`propose_edit` 后成功，产生 `PENDING` 建议，正文仍为版本 2。第二个 run 已进入 `RUNNING` 并发出模型请求时，脚本只向自己启动的 Java 进程发送 SIGKILL，然后重新启动同一个 JAR。重启后，第一个 run 仍是 `SUCCEEDED`，建议仍是 `PENDING`；第二个 run 为 `FAILED`，错误码 `SERVICE_RESTARTED`，没有建议，正文与版本未改变。SSE 从游标 0 重放包含 `run.failed` 和 `SERVICE_RESTARTED` 的事件。假模型共收到 3 次请求（成功 run 两次、被中断 run 一次），重启没有续跑旧模型请求。

运行前必须设置 `MOJIE_TEST_JDBC_URL` 指向专用可丢弃数据库；脚本没有生产数据库默认值。另可设置 `MOJIE_TEST_DB_USER`、`MOJIE_TEST_DB_PASSWORD`、`MOJIE_JAVA_BIN` 和 `MOJIE_EXEC_JAR`。本次命令中的 JDBC URL 为 `jdbc:postgresql://127.0.0.1:5432/mojie_restart_test`。脚本结束时拒绝测试建议并软删除测试作品，然后停止其 Java 与假模型进程；专用数据库随后已删除。

这项检查覆盖真实进程中断与启动恢复，不覆盖浏览器端刷新/断线竞争，也不证明真实模型的输出质量。
