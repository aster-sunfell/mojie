# Agent Loop AL-AC-01–28 核查（2026-09-30）

依据 [Agent Tool Loop PRD](../prd/Agent_Tool_Loop_PRD_v0.1.md) 第 9 节逐项核对。`自动覆盖`表示该条的主要确定性断言有可重复测试或真实运行记录；`部分`表示已有实现与证据，但所列竞争、边界或人工检查尚未覆盖。此表是 Agent Loop 专项核查，不代替总 PRD 的 AC-01–41 发布验收。

证据简称：Loop = `AgentLoopTest`；Scheduler = `RunSchedulerIT`；DB = `JdbcContentRepositoryIT` / `JdbcContentServiceIT`；Adapter = Messages 和 Chat Completions 适配器测试；Web = `App.test.jsx` / `useRun.test.jsx`；Browser = [Chromium 记录](browser-agent-loop-2026-09-30.md)；Live = [真实模型记录](agent-loop-real-model-2026-09-30.md)；Recovery = [数据库恢复记录](agent-loop-db-recovery-2026-09-30.md)。

| AL-AC | 状态 | 证据与剩余边界 |
| --- | --- | --- |
| 01 | 自动覆盖 | Loop 两种工具路径；Scheduler 完整七工具任务；Live 多种模型路径 |
| 02 | 自动覆盖 | Loop 无候选成功；Live 无需修改和信息不足；Browser 无建议任务 |
| 03 | 自动覆盖 | Scheduler 候选成功后仅发布 PENDING；DB / Browser 正文保持原样直到采纳 |
| 04 | 自动覆盖 | Loop 未完整读取目标时拒绝；DB 成功事务校验目标全文回复进入最终模型输入 |
| 05 | 自动覆盖 | Loop 混合文本、多工具顺序执行和后续模型输入配对 |
| 06 | 自动覆盖 | Loop 缺失/重复调用 ID、截断批次、完成原因矛盾均在执行前拒绝 |
| 07 | 自动覆盖 | Loop 未知工具及非法参数回填；Scheduler 经真实 HTTP/DB 校验 Chat 非法 JSON 参数修正 |
| 08 | 自动覆盖 | Loop 设定混入非法 ID 整批拒绝；DB 跨作品章节读取无内容 |
| 09 | 自动覆盖 | Scheduler 双作品并发复用提供方及工具调用 ID，结果、来源按 run 隔离 |
| 10 | 自动覆盖 | Scheduler 在实际 PostgreSQL 运行中先读目标及前章，再修改二者，创建搜索视图后修改另一前章；续页、重复读取及持久来源均保持基准/首读/视图版本 |
| 11 | 自动覆盖 | Loop 第八次最终分析成功，第八次新工具被拒，未发第九次请求 |
| 12 | 自动覆盖 | Loop 第十二次工具后仍可请求最终分析，后续工具受预算拒绝 |
| 13 | 自动覆盖 | Loop 剩余额度不足时整批 ABORTED，不执行其中任一工具 |
| 14 | 自动覆盖 | Loop 中文与表情按 JSON 码点计数，重复工具结果累计且历史重发不重复计数 |
| 15 | 部分 | Loop 精确文本上限、完整请求字符预算和独立 token 窗口预检查已测；所选模型窗口写入 run 快照；保守 UTF-8 上界尚未与提供方 tokenizer 及推理 token 边界对齐 |
| 16 | 自动覆盖 | Loop 候选后失败或取消不返回候选；Scheduler 不发布建议且拒绝伪造采纳 |
| 17 | 自动覆盖 | Adapter HTTP 测试区分鉴权、限流、网络和单次请求超时；Loop 与 Scheduler/PostgreSQL 验证拒绝、空分析及鉴权/网络/超时分别失败、单次尝试且无建议 |
| 18 | 自动覆盖 | 搜索空命中、锁超时、范围过大和完整结果过大分别测试；无部分结果与静默截断 |
| 19 | 部分 | Scheduler 排队/运行取消、晚到 Worker 和新任务活动槽；Browser 运行取消；成功提交与取消请求的精确并发竞态尚未专门注入 |
| 20 | 自动覆盖 | Scheduler 阻塞模型、排队/运行截止与容量释放；单次适配器请求超时 |
| 21 | 部分 | Loop 落库失败停止请求；Recovery 断库重试与已提交成功回读；真实 PostgreSQL 进程故障尚未注入 |
| 22 | 部分 | Browser 刷新、断 SSE 后恢复；Web 重复事件/旧快照；真实 Java 进程重启回归；浏览器重连中旧快照迟到组合未测 |
| 23 | 自动覆盖 | Loop 工具完成后取消不再发模型请求；Scheduler 在 PostgreSQL 中保存工具执行、TOOL 回复和来源后取消，断言来源 `providedToModel=false` |
| 24 | 自动覆盖 | DB 旧版本采纳冲突；Browser 草稿保护、采纳及双标签页保存冲突 |
| 25 | 部分 | DB 可沿 runId 查询消息、步骤、来源和预算；配置快照与适配器错误脱敏已核对；PostgreSQL 测试确认推理协议块保留供续轮使用，但开发 trace、任务详情和事件均不展示其内容；意外提供方异常的消息中即使含伪密钥，也不进入这些响应；真实密钥与日志的全路径检查仍需复核 |
| 26 | 自动覆盖 | DB 从空 PostgreSQL 顺序执行四次迁移，核对 13 张表、跨表外键和事件表 |
| 27 | 自动覆盖 | Recovery 通过 PostgreSQL trigger 注入工具完成与成功事件事务中途异常，断言无部分提交；DB 另测终态提交回执丢失 |
| 28 | 部分 | DB 持久化链路、候选与采纳历史可关联；未送达来源判定有实现；异常取消下全部关联键组合尚未逐一核对 |

本次 `mvn verify` 在 PostgreSQL 18.6 的独立测试 schema 上通过：Loop 33 项、Scheduler 22 项，DB 集成测试通过。浏览器 4 项、Live 固定样本另见上述记录。当前可以判断显式 Agent Loop 的核心业务链路已完成并可用；AL-AC-15、19、21、22、25、28 仍需补足证据后才可逐项签收，完整 MVP 发布验收还需对照总 PRD。
