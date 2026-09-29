# AC-33：失败候选不可采纳

`RunSchedulerIT.failedOrCancelledCandidateCannotBeAccepted` 在 PostgreSQL 18.6 的独立临时 schema 中运行两项任务。两项均先成功执行 `read_chapter` 和 `propose_edit`，形成可诊断的工具步骤。

第一项的下一次模型响应为 `max_tokens` 截断，任务变为 `FAILED / LIMIT_EXCEEDED`；第二项在下一次模型调用尚未返回时收到取消请求，迟到响应不能推进任务，终态为 `CANCELLED`。两项均无 `proposalId`、数据库无 `tb_edit_proposal` 记录，正文和版本保持原样。将各自的 runId 作为建议 ID 直接调用采纳服务，均返回 `RESOURCE_NOT_FOUND`。成功发布建议的路径另由 `RunSchedulerIT.fullConsistencyReviewPagesEvidenceAndPublishesOnlyAProposal` 覆盖。

设置 `MOJIE_TEST_JDBC_URL`、`MOJIE_TEST_DB_USER`、`MOJIE_TEST_DB_PASSWORD` 后执行 Java 21 的 `mvn -o -Dtest=RunSchedulerIT test -q`，10 个 PostgreSQL 集成用例通过。此证据验证后端采纳边界，不代替浏览器端草稿保护检查。
