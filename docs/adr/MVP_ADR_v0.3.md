# 墨界 Mojie · 架构决策记录

**版本：** 0.3  
**日期：** 2026-09-22  
**命名修订：** 2026-09-27；产品名统一为“墨界 Mojie”，架构决策与版本不变。  
**工具修订：** 2026-09-27；工具统一为七个原子工具并按“动词_资源”命名，见 ADR-004 5.2 与 PRD 5.4。  
**检索候选修订：** 2026-09-27；新增 ADR-004 5.5，记录 PRD 5.10 所列 P1 检索候选的依据，补充源码索引 OF-21、OF-22；P0 决策不变。  
**配套需求：** [PRD v0.3](../prd/MVP_PRD_v0.3.md)  
**固定参考：** OpenFic `v0.11.1`，提交 `fd62cb4942f8f6a080a8421de1ea121ef9fee7fb`。  
**决策状态：** 本轮文档采用；实现和验收待执行。  
**证据边界：** 已在固定源码副本核对下述入口与调用关系；没有启动上游、运行 Java 代码或完成真实模型/性能测试。上游选型动机若非作者明示，均标为推断。

## 0. 使用方式与决策索引

PRD 定义功能、边界和验收；本文记录原因、替代方案、源码依据、代价和重评条件。二者发生冲突时，以 PRD v0.3 的可观察行为为准，并修正文档；不得拿 ADR 中的后续候选作为已批准的 P0。本文不是 DDL、OpenAPI 或完整运行时详细设计。

| 决策 | 本版选择 | 主要验收 |
| --- | --- | --- |
| ADR-001 | PostgreSQL 主库；pgvector 后置 | AC-06–10、AC-27、AC-28、AC-41 |
| ADR-002 | P0 不引入 Redis / ZeroMQ；单实例有界执行 | AC-14、AC-16、AC-17、AC-22、AC-32、AC-40 |
| ADR-003 | HTTP 命令 + SSE 进度；数据库事件游标恢复 | AC-15、AC-28、AC-30 |
| ADR-004 | 增加字面正文搜索；混合检索后置且独立保留字面搜索；P1 候选依据见 5.5 | AC-34–36、AC-38–40 |
| ADR-005 | 搜索固定章节版本视图；命中后按同版本读原文 | AC-25、AC-37、AC-39 |

PRD v0.2 已有的显式串行循环、任务后采纳、有效消息持久化、纯文本编辑与草稿保护继续有效；本轮没有改变这些决策。

## 1. 上游事实与本项目边界

| 方面 | 固定基线实际实现 | Java MVP 的处置 |
| --- | --- | --- |
| 业务存储 | SQLite + SQLAlchemy/aiosqlite；连接配置 WAL 等 | PostgreSQL；重新验证事务/并发，不照搬数据库方言 |
| 检索存储 | LanceDB；关系库保留索引元数据，检索服务封装引擎 | P0 字面搜索；P1 可采用 pgvector，保持业务/检索职责分离 |
| Agent 运行 | asyncio.Task，运行注册表、启动门与身份校验 | Java 有界执行器、RunHandle、数据库活动槽 |
| 其他后台作业 | 数据库作业记录、Worker、租约、心跳、恢复、ZeroMQ 通知 | P0 不移植；批量索引到 P1 单独设计 |
| 交互命令 | 发消息、审批、恢复、取消主要是 HTTP | 保留 HTTP 命令，审批改为任务成功后的建议采纳 |
| 进度订阅 | Socket.IO 房间、重连重入、进程内事件缓冲 | SSE 按 runId 订阅，数据库游标补发 |
| 普通正文搜索 | UI 调用 HTTP；数据库筛选后按行找词并返回行号 | 将思路封装为 Agent 工具，新增范围/分页/版本约束 |
| Agent 章节检索 | search_chapters 实际调用 hybrid：向量 + BM25 | P1；本项目 P0 字面工具沿用 search_chapters 名称，但不能与上游同名混合检索混为一条链路 |

依据见第 7 节源码索引。上游支持本地数据与桌面/单容器部署，因此 SQLite + 本地 LanceDB 有利于降低安装依赖，这是根据部署形态作出的工程推断，不能冒充作者已发表的选型结论。

## 2. ADR-001：使用 PostgreSQL 主库，向量检索后置

### 2.1 问题与约束

项目需要章节版本、采纳事务、幂等键、运行状态、工具消息和事件记录；这些能力需要关系型事务与约束，并不强制要求 MySQL。v0.2 的 MySQL 是本项目选型，不是继承上游。用户明确考虑后续 RAG，当前仍处于需求阶段。

### 2.2 决策

P0 采用 PostgreSQL，保存 PRD 的 10 类逻辑实体；不安装 pgvector 也必须可以运行全部 P0 功能。后续向量化可在同库增加片段、来源版本、向量及索引元数据，通过独立 RetrievalService 访问，不让业务 Controller 和 AgentLoop 直接拼向量查询。

pgvector 能在 PostgreSQL 中存储向量，提供精确及近似检索；因此它是一条可行的后续路线，而非已验证的最终配置。具体数据库、扩展、驱动和 SDK 版本在实际环境中锁定。[pgvector 官方文档](https://github.com/pgvector/pgvector)

### 2.3 上游依据与取舍

`settings.database_url` 指向 SQLite；`storage/database.py` 含 SQLite PRAGMA。`OpenFicRetrievalService` 管理索引契约并调用 `LanceDBRetrievalEngine`，而非将业务正文与向量索引混为一张权威数据表。[OF-01] [OF-02] [OF-03] [OF-04]

| 方案 | 收益 | 代价 / 处置 |
| --- | --- | --- |
| SQLite + LanceDB | 更接近上游的本地部署 | 不作为当前 Java Web 的主路线 |
| MySQL + 后续独立检索存储 | 仍可满足需求，不是错误方案 | 后续可能增加存储部署与数据同步；若已有大量实现应重算迁移成本 |
| PostgreSQL + 后续 pgvector | 主库现在确定，未来可减少独立存储系统 | 需要学习 PostgreSQL 方言与并发行为；pgvector 不自动实现完整 RAG |

本项目继承“正式正文是权威、索引是派生数据”的分层，替换存储技术。即使同库，异步嵌入仍可能过期，必须跟踪来源版本、内容哈希、模型/维度和构建状态。上游已有 stale、needs_rebuild 以及中文 ngram 配置，后续可参考。[OF-07]

### 2.4 实现影响与验证

- 修改迁移、驱动、SQL 和集成测试；不通过全局替换数据库名字假装完成迁移。
- 唯一约束冲突后先回滚失败事务，再重新读取幂等记录；不能继续使用已失败事务。
- 正文更新使用版本条件更新；历史、建议状态与正文在同一短事务提交。
- 多次查询组成一致任务快照时，明确采用单条查询或短只读 REPEATABLE READ 事务。PostgreSQL 默认 READ COMMITTED 的不同语句可以看到不同已提交快照。[官方隔离级别文档](https://www.postgresql.org/docs/current/transaction-iso.html)
- 不在数据库锁/事务内请求模型、执行网络嵌入或扫描全书。
- 在锁定 PostgreSQL 环境验证 AC-41，并复跑原事务/幂等用例；H2/SQLite 通过不能替代。

### 2.5 重评条件

已经存在大量 MySQL 实现；部署环境不允许 pgvector；向量规模或索引负载需要独立伸缩；中文混合检索质量不满足要求。触发时以实际数据和迁移成本决定，不因“统一数据库”强行保留方案。

## 3. ADR-002：P0 不使用 Redis，也不移植上游 ZeroMQ

### 3.1 问题与约束

当前是单用户、单实例；全局 2 个运行、8 个排队；服务重启不续跑。没有测量证明需要缓存，也没有独立 Worker、多实例共享调度或分布式限流需求。

### 3.2 上游依据

Agent 的 `_launch_task` 创建 asyncio.Task，通过 start_gate 保证登记后进入运行器；运行注册表持有任务对象，取消/清理核对执行身份。[OF-08] [OF-09]

上游还有独立后台作业链路：BackgroundSupervisor 管理 Worker、watchdog 与事件桥接；ZeroMQ 用于作业通知和事件传输；数据库记录作业及租约。[OF-10] [OF-11] 因此“不使用 Redis”不等于“上游没有可靠后台作业”，也不等于“所有作业都只有内存队列”。

### 3.3 决策与职责

| 职责 | P0 承担者 |
| --- | --- |
| 持久状态、幂等、作品活动槽 | PostgreSQL |
| 排队、运行容量与执行 | Java 有界执行器 |
| 定位本次执行、取消信号、身份清理 | 进程内 RunRegistry / RunHandle |
| 模型上下文工作副本与首读缓存 | run 私有内存；有效消息仍落库 |
| 步骤恢复与前端进度 | 数据库事件表 + SSE |
| 重启处理 | 开放服务前将旧进程未结束任务记为 SERVICE_RESTARTED |

不增加 Redis 缓存、分布式锁或队列；不照搬 ZeroMQ。继续实现活动槽、执行身份、取消竞争与有界资源，不能以“不上中间件”为由省略。

### 3.4 代价、替代方案与验证

内存排队不可跨进程恢复，已明确接受；单实例是部署约束，不支持多副本滚动部署。正文读取直查数据库，若以后成为瓶颈再测量缓存收益。数据库故障时停止新任务，不能假成功。

引入 Redis 队列会增加“数据库已创建任务、队列未入队”等提交边界，需要补偿/事务发件箱及重复执行防护；一个 Redis 实例本身不提供完整作业协议。若只用 Pub/Sub，断线订阅者不能依赖它补齐历史。[Redis Pub/Sub 文档](https://redis.io/docs/latest/develop/pubsub/)

以 AC-14/16/17/22/32 验证取消、重启、容量与清理；以 AC-40 验证运行环境无需相关中间件。

### 3.5 重评条件

独立 Agent Worker、多应用实例、持久排队、批量向量化/摘要、共享限流或明确的读取瓶颈。先定义持久任务、领取、租约、重试和幂等需求，再评估数据库队列、Redis Streams 等方案；不默认 Redis 是唯一答案。

## 4. ADR-003：HTTP + SSE，恢复依据为数据库事件

### 4.1 上游事实

`api-client.ts` 中消息、审批、问题回答、恢复和取消使用 HTTP POST。Socket.IO 主要处理订阅与事件；handlers 建立会话/子 Agent/后台作业房间，`agent-socket.ts` 在重连后重新加入。[OF-12] [OF-13] [OF-14]

客户端配置 websocket 与 polling 传输。Socket.IO 有自己的协议，不是普通 WebSocket 的同义词，不能用 Java 原生 WebSocket 服务直接接上游客户端。[OF-15] [Socket.IO 官方说明](https://socket.io/docs/v4/)

`AgentEventReplayBuffer` 是进程内字典，默认每个会话最多保留 1,000 个所选事件。该缓冲非持久化，不代表上游业务消息也不落库。[OF-16]

### 4.2 决策

创建/取消 run、接受/拒绝建议继续走 HTTP；每个 run 的进度通过 SSE 订阅。P0 没有子 Agent 和复杂后台房间，不保留 Socket.IO 协议兼容层。

业务状态和对应事件在同一短事务提交；RunEventStream 每 500ms 跟读已提交的 seq>cursor 事件。先快照后订阅，断线按最后序号补发，前端按 runId+seq 去重。SSE 与运行线程分别使用受限资源。

SSE 支持服务器到浏览器的事件流，EventSource 有自动重连和事件 ID 机制，但不会替应用保存历史；项目的恢复能力来自持久事件和游标协议。[MDN SSE 文档](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events)

### 4.3 成本与约束

- 迁移时重写会话订阅适配和相关 hooks，替换 join/leave、重连重入房间与事件名；不能只改连接地址。
- 快照状态、步骤、结果和 lastEventSeq 必须来自一致读取；前端防止旧快照覆盖新事件。
- 终态后前端 close EventSource，再查最终详情；采纳/拒绝通过建议接口刷新，不等待已关闭的 run 流。
- 断线不取消任务；发送失败只释放该连接；不承诺恰好一次传输。
- 代理关闭响应缓冲，设置长连接超时与心跳；慢客户端不阻塞 Agent；HTTP/1.1 多标签页连接限制需联调。
- P0 为同源单用户；未来 token 鉴权需考虑原生 EventSource 不能自由添加自定义头，不把密钥放 URL。
- 数据库跟读有查询成本；本版 20 条连接上限下测量成本，不预先添加 Redis 通知系统。

### 4.4 替代与重评条件

沿用 Socket.IO 可以减少原前端订阅层重写，但需承担 Java 服务端协议接入和兼容测试；原生 WebSocket 仍需自行实现订阅、重连及补发。

大量复用原有多会话/子 Agent/后台房间、实时语音、协同编辑或高频双向输入时重评 Socket.IO/WebSocket。逐字输出本身不强制更换协议；未来 token 事件是否持久化/聚合应单独设计，不能直接套用 P0 全事件落库频率。以 AC-15/28/30 验证本次选择。

## 5. ADR-004：先做字面正文搜索，后续组合混合检索

### 5.1 上游两条搜索链路

普通 UI 搜索：章节 HTTP 路由 → chapter_service.search_chapters → chapter_repo.search_by_content。仓库按作品限制并以 ILIKE 筛章，服务按行匹配，返回行号和命中行。[OF-17] [OF-18] [OF-19]

上游 Agent 工具：search_chapters 检查嵌入模型与索引状态后，使用 hybrid，向量/BM25 各取最多 40 候选，加权 RRF 融合、可选重排序，最终最多 5 片段并应用分数阈值。缺失模型/索引并没有直接降级为上述普通正文搜索。[OF-05] [OF-06]

这两个同名入口并非同一功能。将普通正文搜索包装为 Agent 工具，是本项目的改编，而不是宣称上游已经提供同语义 grep 工具。

### 5.2 决策

新增字面正文搜索工具 search_chapters。工具按“动词_资源”统一：章节与设定各有 list/read/search 三个只读工具，另加建议工具 propose_edit，共七个原子工具；search 只定位并返回摘录，完整证据由 read 获取，设定与章节遵循同一划分。工具名与上游 Agent 混合检索工具同名但不同义，文档引用上游时必须标注。采用 PRD 5.9 的单关键词字面匹配、默认前文范围、稳定分页、版本和摘录协议；不执行 Shell，不要求用户导出文件。为了使跨数据库/语言结果确定，P0 明确区分大小写；与上游 UI 行为的差异必须记录。

| 问题 | 适合的证据获取方式 | 不能承诺的结论 |
| --- | --- | --- |
| 特定人名、物品名、原句出现在哪 | 字面搜索 + 原文定位 | 未命中不代表没有代词、别名或同义表达 |
| 所有出现 / 最早出现 | 固定范围、章节顺序、完整分页 | 只看第一页或相关性 top-k 不能说已穷尽 |
| 恐惧、背叛伏笔等改写表达 | 后续语义/混合检索 | 相似度分数不是事实正确概率 |
| 目标章是否与前文矛盾 | 读目标章、查设定、搜索并读来源章 | 检索片段不能替代上下文判断 |

P0 不做新的全书搜索页面；在 Agent 步骤中显示片段、版本和继续分页标识，支持打开历史版本只读定位。完整目标章必须由 read_chapter 读取，不能用命中摘要替代成功条件。

### 5.3 实现代价与性能边界

首先实现有界历史正文扫描，SQL 候选筛选只是可选优化；查询必须与字面语义和版本视图等价。参数绑定不能替代通配符转义。检索输出只含有限摘录，扫描原文不进入模型上下文；每页仍计入一次工具调用。8 次模型/12 次工具预算保持，实际演示后再决定是否修订。

上游 SQL 筛选/服务逐行遍历可以作为阅读起点，但不能无界返回整部作品，也不能对历史快照只搜索最新 content。后续 pg_trgm 可评估 LIKE 加速，短模式可能无可提取 trigram 而退化扫描，必须用双字中文词等真实数据验证。[PostgreSQL pg_trgm 文档](https://www.postgresql.org/docs/current/pgtrgm.html)

### 5.4 验证与重评

AC-34–40 验证语义、范围、分页、快照、长行、预算与无嵌入依赖。演示应含未知证据章节、相关词多次出现、无字面命中但存在同义描述三类情况；最后一类必须如实说明字面检索边界，不能声称系统已经能解决语义问题。

后续出现长篇资料、词面匹配不足或扫描性能压力，再评估全文索引、别名扩展与混合检索。接入 pgvector 只解决向量部分；上游中文 ngram/BM25、RRF、rerank 需要独立实现与评估。字面工具保持可用，即使嵌入服务不可用也不退化为完全不能查前文。

### 5.5 P1 检索候选的依据

本节记录 PRD 5.10 所列 P1-R1 至 P1-R5 的理由。它们是 P0 验收后的候选，不改变 5.2 的 P0 决策；实施前按 PRD 5.10 另行修订并补充验收。

**字面增强用参数，不另设工具（P1-R1）。** P0 每次只搜一个关键词、按章节顺序升序返回，在长篇中代价高：每个别名多耗 1 次工具调用（每次运行共 12 次）；检查第 80 章时，常见词的前几页全在开头章节，每页 20 条难以翻到紧邻目标章的前文。`keywords` 与 `order` 仍是字面、确定、可穷尽翻页的语义，版本视图与游标机制直接复用。另设 grep 类工具需要重复实现这些机制，模型也难以在两个近义工具之间选择。正则继续不做：Java 内置正则引擎回溯时不响应线程中断，5 秒截止时间无法直接打断病态模式；确需正则时只考虑线性时间引擎（如 RE2/J），并单独验收。

**语义召回另设工具（P1-R2）。** 相似度检索返回 top-k 与分数，不能穷尽翻页，空结果也不说明内容不存在，与字面搜索契约不同；PRD 5.10 已禁止借用 search_chapters 的名称或参数。候选名避开 search，因为本项目的 search 专指字面定位且可以翻完。未配置嵌入模型时不注册该工具：上游在调用时才检查嵌入配置，未配置即报错，白白消耗一次调用。[OF-05]

**索引由后端维护（P1-R3）。** 上游在索引过期时返回提示文本，在“由 Agent 决定”策略下建议模型调用 update_index，并允许以 force=true 基于过期索引检索。[OF-05] [OF-07] 本项目的 Agent 工具保持只读，索引是否最新应由系统保证；索引构建是异步的，本次运行通常等不到结果，调用还会占用工具预算。

**按不可变版本建索引。** 上游以章节当前正文的 sha256 判断索引是否过期，索引对象是可变的章节。[OF-07] 本项目的 tb_chapter_revision 不可变，检索又按 run 版本视图进行（ADR-005）：分块以 (chapterId, version) 为键，召回命中与 read_chapter 读取的必然是同一份正文，“索引过期”退化为“该版本尚未向量化”，按覆盖率如实返回。代价是每个新版本都要分块，靠内容哈希复用向量、清理无引用旧版本来控制。复用率取决于分块边界是否稳定：若按长度贪心打包，一处修改会使其后所有分块的边界移动，所以边界以段落为锚。向量输入若拼接章节标题，由于改标题不增加正文版本（PRD 5.2），标题也要纳入复用键。

**分块必须保留原文位置。** 上游分块会去掉全文首尾空白、按空行拆段后重新拼接，并在部分分块前叠加上一块的尾部 [OF-22]；入库行保存块 ID、块序号、带前缀文本、原文文本、向量、元数据与过滤字段，没有原文偏移 [OF-04]。这满足不了 PRD 5.9 与 4.5 要求的行号/码点定位和历史版本跳转，因此每个分块必须是该版本原文的连续区间，并记录起止位置。

**上游混合排序推算（P1-R4）。** 以下由固定源码中的参数与公式推算，未运行上游检索：

| 项目 | 上游取值 / 推算结果 |
| --- | --- |
| 参数 | 向量与 BM25 各取 40 个候选；RRF 得分为“权重 /（60 + 名次）”，向量权重 0.7、BM25 权重 0.3；最终 5 段；阈值 0.3 作用于按名次归一化的 RRF 分数 [OF-05] [OF-06] [OF-21] |
| 只被 BM25 命中 | 纯向量第 40 名得分 0.7/100 = 0.0070，高于纯 BM25 第 1 名的 0.3/61 ≈ 0.0049。向量候选不少于 5 个时，只被 BM25 命中的块进不了最终结果；rerank 也只重排 RRF 前 5 段 |
| 阈值 | 最终 5 段的归一化分数至少为 0.7×61/65 ≈ 0.657，阈值 0.3 过滤不到任何最终结果，不相关的查询同样返回 5 段 |

因此上游的“混合”实际是向量召回加 BM25 加分。这支持 5.2 独立保留字面工具，也说明 P1-R4 不应照搬这组权重与阈值。在评估证明需要之前，由模型组合 search_chapters（精确词）与 recall_passages（改写表达）；代价是多一次工具调用，由 P1-R5 复核预算。实施混合检索时还要单独解决中文词法一路：PostgreSQL 原生全文检索不做中文分词，排序也不是 BM25。

**规模与检索方式。** 1,000 章、每章不超过 6,000 码点，按约 800 码点分块，最新版本的分块上限在万块量级。在视图过滤后做精确距离计算即可，避免近似索引先取近邻再过滤造成的召回损失；pgvector 在未建近似索引时执行精确检索。[pgvector 官方文档](https://github.com/pgvector/pgvector) 性能以实测为准，规模或延迟不满足时再评估 HNSW 等近似索引。

**设定暂不向量化。** 单条设定不超过 2,000 码点，list_settings 每页可列 50 个标题，模型可以按标题挑选，再用 read_settings 读取；评估显示遗漏时再纳入。

**重评条件。** 评估显示字面与语义工具组合后仍有系统性遗漏；作品规模使精确检索超出单次时限；出现多语言或跨作品检索需求。

## 6. ADR-005：搜索与读取共享 run 版本视图

### 6.1 问题

v0.2 允许运行中手动保存：目标章有启动基准，其他章重复读取用首读缓存。新增跨章搜索后，如果搜索查询最新正文而 read_chapter 返回缓存，就可能“搜到一句话，读取时却没有”；分页过程中正文变化还会导致遗漏与重复。

### 6.2 决策

第一次正文搜索获取当前作品章节版本清单；用目标章基准与已读章首读版本覆盖，保存为 run 的 chapterViewManifest。之后的搜索、分页和完整章节读取使用这些不可变 tb_chapter_revision。manifest 只存版本指针，不把全书正文塞进模型消息。

| 情况 | 使用版本 |
| --- | --- |
| 目标章 | run 创建时的 baseVersion |
| 第一次搜索前已经完整读过的其他章 | 首次读取版本 |
| 第一次搜索前未读的其他章 | 第一次搜索捕获的版本 |
| 同 run 后续搜索/分页/读取 | 已固定版本，不随手动保存移动 |

目录、标题和 chapterOrder 在活动任务期间冻结；搜索视图获取是短一致性读，正文扫描在事务外进行。视图落库失败即停止，不先返回结果后补写。

这是明确的“每章节版本固定”规则，不是整书单一时刻快照。没有搜索的任务仍按原首读规则；有搜索后，即便某章尚未返回给模型，其后续读取也使用视图版本。

### 6.3 模型证据与后台扫描的区分

chapterViewManifest 记录搜索范围的版本依据；contextManifest 只记录实际送入模型的片段/完整章/设定。生成视图、扫描某章或命中片段都不等于完整阅读。位置按 LF 行号和 Unicode 码点定义，避免 Java UTF-16 下表情导致偏移错误。

PRD 初始上限为 1,000 章视图、单次搜索 5 秒、每页最多 20 命中。超过范围返回明确错误，不静默扫描部分后声称完成。历史版本 P0 不物理删除；UI 使用同版本只读历史页面定位，不覆盖当前草稿。

### 6.4 代价与重评条件

增加版本清单记录与历史正文读取，单次搜索可能扫描较多文本；优点是无需全文副本、嵌入索引或长事务，就能解释分页和读取的一致性。模型上下文仍受 30,000 码点工具结果预算约束，内部扫描量另行受限。

如果后续允许任务期间目录变动、历史清理、超大作品、多用户协作或跨任务恢复，需要重新设计视图生命周期、历史保留与索引版本对齐。采纳仍只强制验证目标章当前版本；其他来源之后变化可能影响建议，UI 要展示分析基准，不能宣称采纳时所有证据最新。验证对应 AC-25/37/39。

## 7. 固定源码索引

以下链接均指向相同完整 SHA；符号与路径是源码阅读依据，不是已运行验证的证明。

| 编号 | 文件 / 阅读点 |
| --- | --- |
| OF-01 | [settings.py][OF-01]：database_url |
| OF-02 | [storage/database.py][OF-02]：SQLite 连接与事务 |
| OF-03 | [retrieval/service.py][OF-03]：OpenFicRetrievalService |
| OF-04 | [retrieval/engine.py][OF-04]：LanceDBRetrievalEngine |
| OF-05 | [tools/impls/chapter/search_chapters.py][OF-05]：SearchChaptersTool |
| OF-06 | [retrieval/internal/query/builder.py][OF-06]：混合查询与排序 |
| OF-07 | [retrieval/chapter_index.py][OF-07]：来源哈希、索引过期与中文分词参数 |
| OF-08 | [api/routers/agent_runtime.py][OF-08]：_launch_task |
| OF-09 | [runner/run_registry.py][OF-09]：任务身份、取消和注销 |
| OF-10 | [background/runtime/supervisor.py][OF-10]：后台 Worker 与恢复 |
| OF-11 | [background/transport/zmq.py][OF-11]：通知和事件 |
| OF-12 | [frontend/src/lib/api-client.ts][OF-12]：HTTP 业务命令 |
| OF-13 | [socket/handlers.py][OF-13]：房间订阅和回放 |
| OF-14 | [assistant/lib/agent-socket.ts][OF-14]：前端订阅生命周期 |
| OF-15 | [frontend/src/lib/socket-client.ts][OF-15]：传输和连接状态 |
| OF-16 | [streaming/replay_buffer.py][OF-16]：进程内事件缓冲 |
| OF-17 | [api/routers/chapters.py][OF-17]：普通章节搜索入口 |
| OF-18 | [storage/services/chapter_service.py][OF-18]：命中行与行号 |
| OF-19 | [storage/repos/chapter_repo.py][OF-19]：作品范围内的正文筛选 |
| OF-20 | [tools/impls/chapter/read_chapter.py][OF-20]：带行号读取完整正文 |
| OF-21 | [retrieval/internal/query/ranking.py][OF-21]：加权 RRF 与置信度归一化 |
| OF-22 | [retrieval/internal/indexing/chunking.py][OF-22]：分块、段落拼接与重叠 |

## 8. 实施顺序与证据待办

1. **M0：** 固定上游 SHA，分别跑普通正文搜索与 Agent 检索；观察 HTTP 命令与 Socket.IO 事件。未配置嵌入模型时的行为如实记录；另用少量查询核对 5.5 的排序推算（只被 BM25 命中的块能否进入结果、阈值是否生效）。确认一个支持工具调用的文本模型，不能用源码阅读代替运行证明。
2. **M1：** 锁定 PostgreSQL 环境，完成正文版本、历史、chapterOrder、事务与迁移；修正旧 SQL，验证 AC-41。
3. **M2：** 六个只读工具（章节、设定各 list/read/search）、搜索版本视图、消息/调用记录、预算和取消；用至少两种工具路径证明循环不是固定脚本。
4. **M3：** 建议工具 propose_edit 及任务成功后发布、用户采纳事务；失败候选不成为待确认建议。
5. **M4：** SSE 与前端 hooks 联调；完成 AC-01–41；补齐真实模型与性能证据。
6. **P1（P0 验收后）：** 按 PRD 5.10 逐项修订并补充验收后实施：先 P1-R1；P1-R2、P1-R3 与评估集 P1-R5 同批；P1-R4 视评估结果决定。

文档验收仅能确认需求与源码引用一致。下列事项仍待实现证明：运行时可靠性、检索效果、数据库性能、完整输出预算、取消时延、SSE 代理部署与依赖版本兼容。

[OF-01]: https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/settings.py
[OF-02]: https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/storage/database.py
[OF-03]: https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/retrieval/service.py
[OF-04]: https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/retrieval/engine.py
[OF-05]: https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/agent_runtime/tools/impls/chapter/search_chapters.py
[OF-06]: https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/retrieval/internal/query/builder.py
[OF-07]: https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/retrieval/chapter_index.py
[OF-08]: https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/api/routers/agent_runtime.py
[OF-09]: https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/agent_runtime/runner/run_registry.py
[OF-10]: https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/background/runtime/supervisor.py
[OF-11]: https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/background/transport/zmq.py
[OF-12]: https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/frontend/src/lib/api-client.ts
[OF-13]: https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/socket/handlers.py
[OF-14]: https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/frontend/src/features/assistant/lib/agent-socket.ts
[OF-15]: https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/frontend/src/lib/socket-client.ts
[OF-16]: https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/agent_runtime/streaming/replay_buffer.py
[OF-17]: https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/api/routers/chapters.py
[OF-18]: https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/storage/services/chapter_service.py
[OF-19]: https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/storage/repos/chapter_repo.py
[OF-20]: https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/agent_runtime/tools/impls/chapter/read_chapter.py
[OF-21]: https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/retrieval/internal/query/ranking.py
[OF-22]: https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/retrieval/internal/indexing/chunking.py

## 连续对话与 Skill 扩展（2026-10-02）

新 Conversation 入口由 [连续对话与 Skill 设计](../design/conversation-skills.md) 承接。CHAT 无目标章，CHAPTER_REVIEW 不提供 propose_edit，CHAPTER_EDIT 保留当前基准全文与作者采纳边界；旧 Run API 保持原预算和独立任务语义。新任务 16 次模型/32 次工具、120000 新增工具码点和 300 秒运行预算，存入配置快照；请求保存历史来源 ID 与完整逻辑输入。终态展示消息和对话活动槽在数据库事务内收尾。

Skill 目录固定于 Run，版本包在文件卷不可变保存，PG 保存状态及引用；Skill 指导不授予权限。新增 context.compacted、skill.loaded、script.started、script.finished、artifact.created 持久化事件，继续使用按 Run 序号恢复的 SSE。文件下载 attachment/nosniff。独立脚本沙箱及协调备份见 [运维说明](../design/conversation-skills-operations.md)。
