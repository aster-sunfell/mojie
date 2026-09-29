# OpenFic v0.11.1 工程映射

固定基线为 `fd62cb4942f8f6a080a8421de1ea121ef9fee7fb`。下文的上游路径均相对于 `reference/OpenFic/`；本地 Java 简写路径以 `backend/src/main/java/com/mojie/` 为根，测试类位于 `backend/src/test/java/com/mojie/`，`db/migration/` 位于 `backend/src/main/resources/`，`web/` 和 `docs/` 路径相对于项目根目录。上游的运行观察限于 [基线记录](openfic-baseline.json) 和 [M0 记录](../verification/openfic-m0-2026-09-29.md)；其余结论来自该 SHA 的源码阅读。本地验证指自动测试，不代表真实模型或浏览器验收。

## REF-01 · 运行入口

- **基线文件/符号：** `backend/app/agent_runtime/runner/session_runner.py`，`SessionRunner.run`、`materialize_state`。
- **上游问题：** 管理会话状态、图执行及消息/事件输出。
- **本项目保留：** 任务入口与实际执行分层。
- **裁剪/替换/新增：** 以单章 run 和 `RunScheduler` 替换长期会话；不提供检查点续跑。
- **本地实现：** `backend/src/main/java/com/mojie/agent/runtime/RunScheduler.java`、`agent/loop/AgentLoop.java`。
- **测试：** `RunSchedulerIT` 的提交、完成、取消和超时；`AgentLoopTest` 的多轮调用。
- **观察/验证：** 上游运行入口只做静态阅读；本地模拟模型任务和 PostgreSQL 记录已验证。
- **未确认：** 无上游真实模型 run 的事件对照。

## REF-02 · 活动任务登记

- **基线文件/符号：** `backend/app/agent_runtime/runner/run_registry.py`，`AgentRunRegistry.register`、`unregister`、`cancel_task`。
- **上游问题：** 按会话跟踪 asyncio 任务，取消及清理时检查任务身份。
- **本项目保留：** 登记、定向取消、旧任务不得清理新任务占用。
- **裁剪/替换/新增：** Java 有界 worker 与数据库活动槽替换 asyncio registry。
- **本地实现：** `RunScheduler.java`、`agent/run/infra/JdbcRunAdmission.java`、`JdbcRunLifecycle.java`。
- **测试：** `RunSchedulerIT` 排队/运行取消及容量；旧实例清理竞争尚缺专测。
- **观察/验证：** 上游只做源码阅读；本地容量和取消有自动证据。
- **未确认：** 上游参考环境取消时序；本地旧实例晚清理竞态。

## REF-03 · 图式模型/工具循环

- **基线文件/符号：** `backend/app/agent_runtime/graph/react_agent.py`，`create_react_agent`、`llm_call`、`tool_exec`、`dispatch_tools`。
- **上游问题：** LangGraph 模型节点、工具分发、汇合和下一轮路由。
- **本项目保留：** 模型请求后校验，再执行工具并回填结果。
- **裁剪/替换/新增：** 显式串行 `AgentLoop`；固定 8 次模型和 12 次工具预算，批次协议校验由后端控制。
- **本地实现：** `agent/loop/AgentLoop.java`、`agent/model/ModelGateway.java`。
- **测试：** `AgentLoopTest` 多工具顺序、重复 ID 整批拒绝、预算、最终分析。
- **观察/验证：** 上游图行为为静态阅读；本地模拟响应已验证。
- **未确认：** 真实模型在五组固定输入下的工具调用质量。

## REF-04 · 模型调用

- **基线文件/符号：** `backend/app/agent_runtime/graph/llm_invoke.py`，`classify_error`、`invoke_model_with_retry`。
- **上游问题：** 分类提供方错误、流超时及重试。
- **本项目保留：** 独立模型网关、错误分类和截止时间。
- **裁剪/替换/新增：** 每次请求仅一次尝试；禁 SDK 自动重试，聚合完整响应后执行工具。
- **本地实现：** `agent/model/anthropic/AnthropicModelGateway.java`、`AgentLoop.java`。
- **测试：** `AnthropicModelGatewayIT` 的协议、429 与无重试；`AgentLoopTest` 无效响应。
- **观察/验证：** 参考环境无 LLM 配置，未实跑；本地假提供方验证。
- **未确认：** 真实提供方的鉴权、网络超时和接近 6000 码点输出。

## REF-05 · 工具基类与注册

- **基线文件/符号：** `backend/app/agent_runtime/tools/base.py`，`AgentTool._arun`；`tools/registry.py`，`ToolRegistry.get_tools`。
- **上游问题：** schema、运行状态、执行钩子、权限和工具注册。
- **本项目保留：** 固定 schema、结构化错误和受控分发。
- **裁剪/替换/新增：** 工具实例无 run 状态；`ToolContext` 逐次传入作品、目标章、快照和预算，不移植通用钩子平台。
- **本地实现：** `agent/tool/AgentTool.java`、`ToolContext.java`、`ContentTools.java`。
- **测试：** `AgentLoopTest` 参数错误与越权；`RunSchedulerIT` 七工具路径。
- **观察/验证：** 上游 `GET /api/v1/agent/tools` 返回注册元数据；本地七工具集成任务通过。
- **未确认：** 上游各工具在真实会话的权限事件。

## REF-06 · 上下文构造

- **基线文件/符号：** `backend/app/agent_runtime/context/build_context.py`，`build_context_parts`。
- **上游问题：** 把历史和上下文片段组织成模型输入。
- **本项目保留：** 模型输入组装与业务工具执行分离。
- **裁剪/替换/新增：** 每 run 独立消息副本、来源清单和完整输入预算；不移植长期历史压缩。
- **本地实现：** `agent/loop/AgentLoop.java`、`agent/loop/LoopInput.java`、`agent/loop/LoopLimits.java`。
- **测试：** `AgentLoopTest` 上下文和结果预算；`RunSchedulerIT` 来源记录。
- **观察/验证：** 上游静态阅读；本地工具结果已关联后续模型请求。
- **未确认：** 真实模型的大正文上下文余量。

## REF-07 · 有效消息持久化

- **基线文件/符号：** `backend/app/agent_runtime/persistence/loader.py`，`load_history`；`persister.py`，`MessagePersister.handle/finalize`。
- **上游问题：** 保存消息并匹配助手工具调用与工具结果，供展示及历史加载。
- **本项目保留：** 消息与 UI 步骤分层、调用 ID 配对和已送达来源判断。
- **裁剪/替换/新增：** PostgreSQL `agent_message`、`model_call`、`tool_execution` 等表；旧 run 只读，不续跑。
- **本地实现：** `agent/trace/infra/JdbcLoopTrace.java`、`agent/run/infra/JdbcRunReader.java`、`db/migration/V3__trace.sql`。
- **测试：** `RunSchedulerIT` 核对消息、步骤、来源；`AgentLoopTest` 跟踪失败。
- **观察/验证：** 上游静态阅读；本地持久记录可按 runId 查询。
- **未确认：** 真实提供方 ID 跨 run 复用的端到端回归。

## REF-08 · 产品事件转换

- **基线文件/符号：** `backend/app/agent_runtime/runner/event_translator.py`，`EventTranslator.translate`。
- **上游问题：** 把内部图事件转换为前端可消费的事件。
- **本项目保留：** 内部执行与产品事件之间有独立边界。
- **裁剪/替换/新增：** 自定义 `run.*`、`model.*`、`tool.*` 事件，不透出 LangGraph 事件。
- **本地实现：** `agent/trace/infra/JdbcLoopTrace.java`、`agent/run/infra/JdbcRunTerminalStore.java`、`api/AgentController.java`。
- **测试：** `RunSchedulerIT` 事件顺序；HTTP/SSE 证据见 `docs/verification/agent-loop-http-and-web-2026-09-29.md`。
- **观察/验证：** 上游静态阅读；本地 SSE 重放已验证。
- **未确认：** 真实浏览器断线重连全过程。

## REF-09 · 事件缓存与连接

- **基线文件/符号：** `backend/app/agent_runtime/streaming/replay_buffer.py`，`AgentEventReplayBuffer`；`frontend/src/lib/socket-client.ts`，`connectSocket`。
- **上游问题：** 内存事件重放和 Socket.IO 连接管理。
- **本项目保留：** 订阅后可恢复过程展示。
- **裁剪/替换/新增：** PostgreSQL 事件表、递增游标与 SSE，断线后按游标补取。
- **本地实现：** `agent/trace/infra/JdbcLoopTrace.java`、`api/AgentController.java`、`web/src/useRun.js`。
- **测试：** HTTP SSE 重放检查、`web/src/useRun.test.jsx` 去重与降级查询。
- **观察/验证：** 上游连接行为未实跑；本地 HTTP 与前端单测已验证。
- **未确认：** Nginx 代理下真实浏览器多标签页连接上限。

## REF-10 · 受控章节修改

- **基线文件/符号：** `backend/app/agent_runtime/tools/impls/chapter/edit_chapter.py`，`EditChapterTool.build_interrupt_preview/_execute`。
- **上游问题：** 编辑预览、审批、正文修改和 revision 记录。
- **本项目保留：** 建议可预览、作者决定、正文变更可追踪。
- **裁剪/替换/新增：** `propose_edit` 仅缓存整章候选，run 成功后发布建议，运行后单独采纳并校验版本。
- **本地实现：** `agent/tool/ContentTools.java`、`proposal/infra/JdbcEditProposalService.java`、`db/migration/V4__proposal_event.sql`。
- **测试：** `JdbcContentServiceIT` 采纳、拒绝和冲突；`RunSchedulerIT` 失败候选不发布。
- **观察/验证：** 上游编辑链路仅静态阅读；本地 PostgreSQL 事务已验证。
- **未确认：** 上游审批和页面更新实测；本地浏览器采纳交互。

## REF-11 · 章节目录/全文读取

- **基线文件/符号：** `backend/app/agent_runtime/tools/impls/chapter/read_chapter.py`，`ReadChapterTool._execute`；`list_chapters.py`，`ListChaptersTool._execute`。
- **上游问题：** 按卷与章节引用列举、读取文本。
- **本项目保留：** 目录和完整正文读取区分。
- **裁剪/替换/新增：** 扁平 `chapterId`；目标章基准快照和其他章首读版本钉住；新增设定 list/read/search。
- **本地实现：** `agent/tool/ContentTools.java`、`agent/tool/infra/JdbcContentRepository.java`。
- **测试：** `JdbcContentRepositoryIT` 版本视图；`RunSchedulerIT` 七工具场景。
- **观察/验证：** 上游工具仅静态阅读；本地分页和全文来源已验证。
- **未确认：** 真实模型是否合理规划目录和完整读取次数。

## REF-12 · 写作页与数据 hooks

- **基线文件/符号：** `frontend/src/features/writing/pages/writing-page.tsx`，`WritingPage`；`hooks/use-chapters.ts`，`useChapter/useUpdateChapter`；`frontend/src/main.tsx`。
- **上游问题：** 写作页组合卷树、标签、草稿、缓存、Agent 侧栏和启动依赖。
- **本项目保留：** 编辑器草稿、服务器数据和任务展示分开管理。
- **裁剪/替换/新增：** `web/src/App.jsx` 的单作品纯文本工作台及 `api.js`；入口仅挂载所需 UI，不移植上游 Provider 树。
- **本地实现：** `web/src/App.jsx`、`web/src/api.js`、`web/src/main.jsx`。
- **测试：** `web/src/App.test.jsx` 保存中输入、切章、冲突、采纳刷新失败。
- **观察/验证：** 上游依赖链已静态核对；本地 UI 单测和构建通过。
- **未确认：** 上游真实页面刷新与本地真实浏览器回归。

## REF-13 · 数据库

- **基线文件/符号：** `backend/app/settings.py`，`database_url`；`backend/app/storage/database.py`，连接初始化。
- **上游问题：** SQLite 业务库及连接生命周期。
- **本项目保留：** 数据访问、事务和业务服务分层。
- **裁剪/替换/新增：** PostgreSQL/Flyway/JDBC；不复制 SQLite SQL 或 WAL 设置。
- **本地实现：** `backend/src/main/resources/db/migration/V1__content.sql` 至 `V4__proposal_event.sql`、`content/JdbcContentService.java`。
- **测试：** `JdbcContentServiceIT`、`JdbcContentRepositoryIT`、`RunSchedulerIT`。
- **观察/验证：** 参考镜像 SQLite 迁移启动；本地 PostgreSQL 18.6 迁移和事务测试通过。
- **未确认：** 所有故障注入和数据恢复组合。

## REF-14 · 普通正文搜索

- **基线文件/符号：** `backend/app/api/routers/chapters.py`，`search_chapters`；`storage/services/chapter_service.py`，`search_chapters`；`storage/repos/chapter_repo.py`，`search_by_content`。
- **上游问题：** 项目内筛选当前章节正文，按章节/行返回命中。
- **本项目保留：** 字面定位并关联章节来源。
- **裁剪/替换/新增：** 加入 Agent 工具、历史版本视图、页游标、范围、码点位置与结果预算；P0 明确区分大小写。
- **本地实现：** `agent/tool/ContentTools.java`、`agent/tool/infra/JdbcContentRepository.java`。
- **测试：** `AgentLoopTest` LF、Unicode、符号和游标；`JdbcContentRepositoryIT` 快照。
- **观察/验证：** 上游无嵌入配置时 `游泳` 有命中，`swim` 命中 `Swim`；本地实现遵循不同的大小写规则。
- **未确认：** 上游搜索大规模章节的延迟。

## REF-15 · Agent 混合检索

- **基线文件/符号：** `agent_runtime/tools/impls/chapter/search_chapters.py`，`SearchChaptersTool._execute`；`retrieval/engine.py`、`retrieval/internal/query/builder.py`、`retrieval/chapter_index.py`。
- **上游问题：** 依靠嵌入模型及索引进行向量/BM25 融合查询，可选 rerank，检测索引新鲜度。
- **本项目保留：** P1 检索设计参考；P0 字面搜索独立可用。
- **裁剪/替换/新增：** 不加载嵌入服务，不把混合工具当作当前七工具的同义实现。
- **本地实现：** P0 `agent/tool/ContentTools.java`；P1 尚未实现。
- **测试：** `RunSchedulerIT` 无嵌入依赖七工具任务；P1 无测试。
- **观察/验证：** 上游 `default_embedding_model` 实际为空；工具源码在缺少该配置时抛错，未通过真实 Agent 调用实测。
- **未确认：** 上游混合检索运行结果、P1 方案与质量。

## REF-16 · 后台工作

- **基线文件/符号：** `backend/app/background/runtime/supervisor.py`，`BackgroundSupervisor.start/_recover_stale_jobs`。
- **上游问题：** 持久后台作业、worker 恢复与通知。
- **本项目保留：** 启动时处理遗留任务，监控执行容量。
- **裁剪/替换/新增：** 单实例有界 `RunScheduler`；服务重启将遗留 run 标为失败，不移植 ZeroMQ 和可恢复作业。
- **本地实现：** `agent/runtime/RunScheduler.java`、`agent/run/infra/JdbcRunLifecycle.java`。
- **测试：** `RunSchedulerIT` 并发容量/取消；`JdbcContentServiceIT` 遗留 run 清理。
- **观察/验证：** 上游后台服务启动日志已见，作业行为未实跑；本地恢复语义有自动证据。
- **未确认：** 真实运行中进程重启及长期资源泄漏测试。

## REF-17 · 命令和订阅

- **基线文件/符号：** `backend/app/socket/handlers.py`，`agent_join/agent_leave`；`frontend/src/features/assistant/lib/agent-socket.ts`；`frontend/src/lib/api-client.ts`。
- **上游问题：** HTTP 发命令，Socket.IO 按会话订阅与重连入房间。
- **本项目保留：** 命令与事件通道分工。
- **裁剪/替换/新增：** HTTP 提交/取消/采纳；按 runId 的 SSE 及游标恢复，不能直接复用 Socket.IO 客户端。
- **本地实现：** `api/AgentController.java`、`web/src/useRun.js`、`web/src/api.js`。
- **测试：** HTTP/SSE 脚本证据、`web/src/useRun.test.jsx`。
- **观察/验证：** 上游连接代码静态阅读；本地 SSE HTTP 测试通过。
- **未确认：** 上游页面断线重连实测和本地真实浏览器/Nginx 验收。
