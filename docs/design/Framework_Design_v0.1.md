# 墨界 Mojie · 工程框架设计

**版本：** 0.1  
**日期：** 2026-09-27  
**依据：** [PRD v0.3](../prd/MVP_PRD_v0.3.md)、[ADR v0.3](../adr/MVP_ADR_v0.3.md)  
**状态：** 原框架设计已用于 MVP 实现；2026-10-02 按[三层重构方案](Backend_Three_Layer_Refactoring_v0.1.md)更新分层与持久化说明。后文接口草案与里程碑保留原设计背景，实际实现与验证以源码和[重构记录](../verification/backend-three-layer-refactoring-2026-10-02.md)为准。
**与其他文档的关系：** PRD 定义行为与验收，ADR 解释选型；本文把二者落到仓库结构、模块边界、核心接口、线程/事务骨架、数据表和测试框架。冲突时以 PRD 为准并回改本文。PRD 11.1 要求的 `docs/design/runtime.md`（时序、SQL、锁细节）在 M2 以本文为骨架补齐。

## 0. 框架设计原则

从 PRD/ADR 中提炼出下面几条原则，后文的结构决策都由它们推出：

1. **单体、单进程、单 Maven 模块。** 模块边界靠包结构 + ArchUnit 测试守住，而不是拆多个服务或多个 Maven 模块（PRD 5.1、2.3）。
2. **核心循环不依赖框架。** `AgentLoop`、工具、上下文、预算是纯 Java，不依赖 Spring、HTTP 或数据库驱动；通过接口注入，可用脚本化假模型在毫秒级单测中跑通（PRD 5.5、9.4）。
3. **事务边界显式可见。** Agent 运行路径使用 `TransactionTemplate` 写出每一个短事务，不依赖跨方法的 `@Transactional` 传播；模型调用和正文扫描永远在事务外（PRD 6.3、6.8）。
4. **数据库是唯一真相。** 状态、事件、消息全部落 PostgreSQL；内存只放 `RunHandle`、run 私有工作副本和首读缓存（ADR-002）。
5. **每种有限资源一个显式池。** Agent 工作线程、SSE 跟读、超时扫描、模型 HTTP 连接分别有界，互不借用（PRD 7.4、9.2）。
6. **契约先行。** OpenAPI 与事件 schema 放在 `contracts/`，前端类型由其生成，错误码两端共享一张表（PRD 7）。

## 1. 技术栈与版本候选

| 层 | 选择 | 候选版本 | 说明 / 待验证 |
| --- | --- | --- | --- |
| JDK | Temurin | 21 LTS（已决定） | 用途：sealed 结果类型（`LoopOutcome`、`ToolResult`、`ModelCallException`、`ContentPart`）的 switch 模式匹配与 record 解构，由编译器检查分支覆盖。不使用虚拟线程，见第 5 节 |
| 框架 | Spring Boot（MVC，非 WebFlux） | 3.5.0（当前） | SSE 使用 `SseEmitter`，Jackson 2；不引入 Reactor |
| 持久层 | Spring JDBC、pgJDBC、TransactionTemplate | Boot BOM / pgJDBC 42.7.1 | Repository 接口与 JDBC 实现分离；事务由 Service 管理，无 MyBatis 或 ORM |
| 数据库 | PostgreSQL | 18.x（Docker 镜像按 digest 锁定） | 18 内置 `uuidv7()`，主键无需 Java 端生成库；不安装 pgvector |
| 驱动 / 迁移 | pgJDBC / Flyway | 42.7.13 / 13.8.0（含 `flyway-database-postgresql`） | 迁移只写 PostgreSQL 方言 |
| 模型 SDK | Anthropic Java SDK | `com.anthropic:anthropic-java` 2.65.0 | 仅在 `infrastructure.model.anthropic` 包内出现；见第 9 节 |
| API 文档 | springdoc-openapi | 3.1.1 | Boot 4.1 兼容性待 M1 确认；不兼容时改为手写 `contracts/openapi.yaml` + 契约测试 |
| 测试 | JUnit 5、AssertJ、Awaitility、Testcontainers、ArchUnit | Boot BOM / 2.0.5 / 1.5.1 | 集成测试只用真实 PostgreSQL 容器（AC-41） |
| 前端 | React、TypeScript、React Router、TanStack Query、Zustand | 19 / 5.9 / 7 / 5 / 5 | **跟随上游主版本**，便于选择性迁移（上游为 React 19、RR 7、TS ~5.9）。npm 上已有 RR 8、TS 7，迁移完成、P0 验收后再评估升级 |
| 前端构建 / 测试 | Vite、Vitest、Testing Library、MSW、Playwright | 8 / 5 / – / 2 / 1.63 | 上游使用 `vite-plus` 工具链；本项目改用标准 Vite，减少非必要工具依赖 |
| 前端 UI | Radix UI Themes、`diff` | 3.3 / 上游同主版本 | 与上游保持一致，便于迁移基础组件和差异视图 |
| 包管理 | Maven Wrapper、pnpm | 3.9.x、10.x | 两端锁文件都提交；不借用上游锁文件 |

P0 的运行依赖只有 **Java 应用 + PostgreSQL**（PRD 9.1、AC-40）。

## 2. 仓库结构

```text
mojie/
├── backend/                     # Spring Boot 单模块
│   ├── pom.xml
│   ├── mvnw, .mvn/
│   └── src/
│       ├── main/java/com/mojie/...        # 见第 3 节
│       ├── main/resources/
│       │   ├── application.yaml
│       │   ├── db/migration/V*.sql        # Flyway
│       │   └── prompts/system-v1.md       # 系统提示词（进版本库，按哈希快照）
│       └── test/
│           ├── java/com/mojie/...         # 单测 / 集成 / 架构测试
│           └── resources/scenarios/*.json # 脚本化模型场景（AC-04 等）
├── web/                         # React + Vite
│   ├── package.json, pnpm-lock.yaml
│   └── src/...                            # 见第 7 节
├── contracts/
│   ├── openapi.yaml             # REST 契约（生成或手写，二选一见第 1 节）
│   ├── run-events.schema.json   # SSE 事件 payload，schemaVersion=1
│   └── error-codes.yaml         # 统一错误码表，前后端各自生成常量
├── deploy/
│   ├── compose.yaml             # postgres:18@sha256:… + 可选 app
│   └── nginx.prod.conf          # 同源反代；/api/v1/agent/runs/*/events 关闭缓冲
├── docs/                        # prd / adr / design / reference / verification
├── reference/                   # 上游 OpenFic 副本（.gitignore 已排除）
└── README.md
```

前后端在同一仓库，但各自构建。生产形态为 Nginx 同源服务 `web/dist` 并反代 `/api`；开发期由 Vite `server.proxy` 转发 `/api` 到 `localhost:8080`，保持同源语义（PRD 9.1、9.3）。

## 3. 后端模块与包结构

### 3.1 包布局

根包 `com.mojie`。内容、Agent、建议和系统模块内部使用 `api / service / repository` 三层；`model` 存放 record 与纯规则，JDBC 实现在 `repository.jdbc`，技术支撑集中在 `infrastructure`，构造与启动由 `config` 完成。

```text
com.mojie
├── MojieApplication             # Spring Boot 入口
├── config/                      # 内容/Agent/事务装配、StartupLifecycle
├── common/                      # error、web、pagination、transaction、cancellation、SQL 支撑
├── content/
│   ├── api/                     # ProjectController、ChapterController、SettingController
│   ├── service/                 # Project/Chapter/Setting/Access/ContentReadService
│   ├── model/                   # 内容、历史、分页投影、纯规则
│   └── repository/jdbc/         # 接口在 repository；SQL 与行映射在 jdbc
├── agent/
│   ├── api/sse/                 # 任务/事件路由、RunEventStream
│   ├── service/                 # 受理、生命周期、完成、轨迹、查询、AgentRunner
│   │   ├── loop/                # 纯 Java AgentLoop 及契约
│   │   ├── tool/                # 七工具、ToolContext、ToolContentReader、ToolContentService
│   │   └── port/                # ModelGateway、RunExecutor、RuntimeStatus
│   ├── model/                   # 运行命令、快照、轨迹投影、固定章节视图
│   └── repository/jdbc/         # 任务、消息/调用/步骤、事件、章节视图
├── proposal/                    # api、service、model、repository/jdbc
├── system/                      # api、service、model、repository/jdbc
└── infrastructure/
    ├── transaction/             # SpringTransactionRunner
    ├── runtime/                 # BoundedRunExecutor、RunRegistry、RunHandle、TimeoutSweeper
    └── model/                   # anthropic、openai 协议适配器
```

### 3.2 依赖方向（ArchUnit 强制）

`api → service → repository 接口`。Controller 不接触 SQL、事务设施或运行资源实现；Service 不导入 JDBC 实现或 infrastructure 实现。Repository 查询返回有类型的 record，SQL、行映射、条件更新和数据库超时由 JDBC 实现处理。

`proposal.service → content.service`；Agent 工具通过 `ToolContentService` 和 `ContentReadService` 读取内容。`content.service` 不反向依赖 proposal 或 Agent。章节路由明确允许调用 `proposal.service.ChapterSaveCoordinator` 来协调一次保存。模型、执行器与运行状态通过业务能力接口注入；基础设施可以回调业务流程。

`architecture.LayerRulesTest` 检查接口层、Service、Repository、纯算法和 SDK 边界，`architecture.ModuleRulesTest` 检查业务模块无环、内容无反向依赖，以及正式正文与历史仅由 `ChapterService` 发起写入。两者没有遗留豁免。事务传播、锁序和释放次数由真实 PostgreSQL 与并发测试补充验证。

### 3.3 模块内部约定

- Controller 负责参数绑定、格式校验、DTO 转换与 HTTP/SSE；作者由 `ActorProvider` 注入，业务归属和状态在 Service 校验。
- Service 管理业务规则及短事务。章节、历史、审核和版本关联加入同一 REQUIRED 事务；一致快照使用独立只读 REPEATABLE READ。模型请求、正文扫描和 SSE 发送不在事务内。
- Repository 接口不暴露 JDBC 对象；实现使用事务绑定连接，不自行 commit、rollback 或 setAutoCommit。存储异常与稳定业务错误分别由 `StorageException`、`BusinessException` 表达。
- `model`、循环与工具契约保持纯 Java；提供方 SDK 限于对应适配器。配置、线程池与启动资源经 `config` 装配，不在 Service 中创建实现。
- 时间通过 `Clock` 注入；对外保留既有字符串时间和 ID 表示。分页游标继续绑定作者、资源与筛选条件。

## 4. 核心接口草案

以下是职责契约，不是最终签名；命名可以调整，但边界和测试点不能消失（PRD 5.1）。

### 4.1 模型边界（BE-10）

```java
public interface ModelGateway {
    /** 一次且仅一次的提供方尝试；不执行工具、不重试。 */
    ModelResponse call(ModelRequest request, CancelSignal cancel) throws ModelCallException;
}

public record ModelRequest(
        String systemPrompt,            // 已快照
        List<ConversationMessage> messages,
        List<ToolSpec> tools,           // 已快照，按名称排序保证确定性
        ModelCallConfig config) {}      // modelId、maxTokens、effort、requestTimeout

public record ModelResponse(
        List<ContentPart> content,      // TEXT / TOOL_CALL / OPAQUE（例如 thinking，原样保存并回传）
        FinishReason finishReason,      // FINAL, TOOL_CALLS, TRUNCATED, REFUSED, OTHER
        Usage usage,                    // 可空字段，不估算
        String providerRequestId,
        String rawFinishReason) {}

public sealed interface ModelCallException
        permits AuthError, RateLimited, NetworkError, Timeout, ContextTooLong, ProviderError, EmptyResponse {}
```

### 4.2 工具（BE-03）

```java
public interface AgentTool {
    ToolSpec spec();                                    // name、description、JSON Schema（strict）
    ToolResult execute(JsonNode args, ToolContext ctx); // 无实例状态
}

public record ToolContext(
        RunScope scope,                 // ownerId、projectId、runId、targetChapterId、baseVersion —— 后端注入
        ReadSnapshotCache reads,        // run 私有：目标章基准、首读缓存、版本视图
        ContextManifestRecorder manifest,
        CandidateHolder candidate,      // propose_edit 写入；成功提交时才发布
        Deadline deadline,
        CancelSignal cancel) {}

public sealed interface ToolResult {
    record Success(JsonNode data, List<ManifestEntry> evidence) implements ToolResult {}
    record Failure(String code, String message, boolean retryableByModel) implements ToolResult {}
}
```

`ToolRegistry` 在启动时注册 7 个工具，计算 schema 集合哈希，写入 `tb_agent_prompt_snapshot`。工具名常量集中定义在 `ToolNames` 中，与 PRD 5.4 一一对应。

### 4.3 循环（BE-04）

```java
public final class AgentLoop {
    LoopOutcome run(LoopInput input, LoopPorts ports);
}
// LoopPorts = ModelGateway + ToolRegistry + RunTraceSink + RunEventSink + Clock
public sealed interface LoopOutcome {
    record Completed(String analysis, Optional<Candidate> candidate) implements LoopOutcome {}
    record Failed(String errorCode, String detail) implements LoopOutcome {}
    record Cancelled() implements LoopOutcome {}
}
```

`AgentLoop` 只返回“结论”，**不提交终态**；终态由 `AgentRunner` 通过 `AgentRunService.complete*` 的条件更新来仲裁（PRD 6.1）。`RunTraceSink.save*` 抛出异常时，循环立即以 `PERSISTENCE_ERROR` 结束，不继续请求模型（PRD 5.6）。

### 4.4 运行时（BE-11）

```java
public final class RunHandle {
    final String runId;
    final UUID executionId;
    final FutureTask<Void> task;
    final CancelSignal cancel;                          // AtomicBoolean + 当前 HTTP 调用的中断钩子
    final AtomicReference<Phase> phase;                 // REGISTERED → STARTED → EXITED
                                                        // REGISTERED → CANCELLED_BEFORE_START
    boolean releaseOnce();                              // 仅 CAS 成功一方释放 RunAdmission 许可
}

public interface RunRegistry {
    void register(RunHandle h);                         // 提交执行器前调用
    Optional<RunHandle> find(String runId);
    boolean unregister(String runId, UUID executionId); // 身份不符则不删除
}
```

### 4.5 检索（BE-14）

```java
public interface ChapterTextSearchService {
    SearchPage search(RunScope scope, ChapterView view, SearchQuery q, Deadline d, CancelSignal c);
}
public interface ChapterViewService {
    ChapterView getOrCreate(RunScope scope, ReadSnapshotCache reads); // 首次搜索时：一致性读 → 结束只读事务 → 写事务落库
}
```

`LiteralMatcher` 是纯函数：输入一行文本和关键词，输出码点偏移。它和 `CodePoints` 工具类（行切分、码点位置到 UTF-16 下标的转换、摘录窗口）一起承担 AC-34 的全部边界测试。

## 5. 运行时资源与线程模型

| 资源 | 实现 | 容量（PRD 9.2） | 说明 |
| --- | --- | --- | --- |
| 受理许可 | `RunAdmission`（`Semaphore(10)`） | 2 运行 + 8 排队 | 在事务**之前** `tryAcquire`，失败返回 429；已持久化的 run 永不因容量返回 429 |
| Agent 工作线程 | `ThreadPoolExecutor(2, 2, ArrayBlockingQueue(8), AbortPolicy)` | 2 / 8 | 许可数与容量一致，正常情况下不会被拒绝；拒绝时仍走 `SCHEDULING_FAILED` 路径 |
| 超时扫描 | 单线程 `ScheduledExecutorService` | 每 2 秒 | 通过条件更新把超时的 QUEUED/RUNNING 置为 FAILED/RUN_TIMEOUT，然后触发对应句柄的 cancel |
| SSE 跟读 | `ScheduledExecutorService(2)` + 每个连接一个周期任务 | 最多 20 条连接，每批 100 条事件 | 与 Agent 线程完全隔离；写失败只关闭该连接 |
| 模型 HTTP | SDK 内部 OkHttp 连接池 | 连接超时 10s，请求超时 60s | 取消时关闭本次调用；不承诺远端停止计算（PRD 6.5） |
| JDBC | HikariCP | 初始 10 | Agent 2 + SSE 跟读 + HTTP 请求；M4 以实测调整 |

**不使用虚拟线程**（JDK 21 可用但刻意不用）：“最多 2 个运行”由固定 2 线程池直接体现，换成虚拟线程还需另加限流；JDK 21 上虚拟线程在 `synchronized` 内阻塞 IO 会钉住载体线程，JDBC 驱动与 HTTP 客户端内部可能触发。Tomcat 也保持平台线程（不开启 `spring.threads.virtual.enabled`）。

**许可与句柄的生命周期**（对应 PRD 6.5 表格）：

```text
tryAcquire 许可 ──失败──▶ 429 RUN_CAPACITY_EXCEEDED（先查幂等，命中则直接返回原 run）
   │
   ▼  TX1：锁 tb_project → 校验幂等/槽/版本 → 插入 run(QUEUED)+输入快照+run.queued 事件 → 占用槽
   │     （唯一键冲突：回滚 → 释放许可 → 新事务回读 → 返回原 run 或 409）
   ▼
new RunHandle(REGISTERED) → registry.register → executor.execute(task)
   │ 被拒绝 → TX：FAILED/SCHEDULING_FAILED + 事件 + 条件清槽 → registry.unregister → releaseOnce
   ▼
worker 入口：CAS REGISTERED→STARTED（失败说明已在启动前被取消，直接返回）
   → TX：条件更新 QUEUED→RUNNING（cancel_requested=false）+ run.started
   → AgentLoop …
   → TX：终态条件更新 + 可选建议发布 + 事件 + 条件清槽
finally：registry.unregister(runId, executionId) → phase=EXITED → releaseOnce
```

排队中取消的路径：TX 中 QUEUED→CANCELLED 并清槽，然后 CAS `REGISTERED→CANCELLED_BEFORE_START`；成功的一方执行 `executor.remove(task)` 并 `releaseOnce`。`Future.cancel` 只用来阻止尚未开始的任务，不作为线程已退出的证据。

**启动顺序**（PRD 6.7）：Flyway 迁移 → `RestartCleanup` 把 `process_instance_id ≠ 本进程` 且未结束的 run 置为 FAILED/SERVICE_RESTARTED，收尾对应的调用和步骤，并清空活动槽 → `ReadinessGate.open()`。在就绪门打开前，`POST /agent/runs` 返回 503；内容接口不受影响。

## 6. 持久化框架

### 6.1 事务写法

```java
// 运行路径：一个方法一个显式短事务；事务内不调用模型、不扫描正文
tx.execute(s -> {
    int n = runMapper.markSucceeded(runId, result, proposalIdOrNull); // WHERE status='RUNNING' AND cancel_requested=false
    if (n == 0) throw new TerminalRaceLost();                          // 被取消/超时抢先：放弃发布
    proposalMapper.insertPending(...);                                 // 可选
    events.append(runId, "run.succeeded", payload);                    // 同事务分配 seq
    projectMapper.releaseSlot(projectId, runId);                       // WHERE active_run_id = runId
    return null;
});
```

- `RunEventAppender.append` 先执行 `UPDATE tb_agent_run SET last_event_seq = last_event_seq + 1 WHERE id=? RETURNING last_event_seq`，再插入事件。行锁天然把同一 run 的 seq 分配串行化（PRD 7.4）。
- 唯一冲突统一由 `PgErrors.isUniqueViolation(e)`（SQLState `23505`）识别，**在事务外**处理：回滚后用新事务回读（ADR-001 2.4）。
- 需要一致快照的读取（任务详情 + lastEventSeq、首次搜索的版本清单）使用单条 SQL，或 `TransactionTemplate` 配置 `REPEATABLE_READ` + `readOnly`（PRD 6.8）。
- 锁顺序固定为 tb_project → tb_agent_run / tb_edit_proposal → tb_chapter，写入 `common.sql.LockOrder` 的注释和代码评审清单。
- JSON 字段使用 `jsonb`，由一个 `JsonbTypeHandler` 负责；密钥和请求头永远不进入任何 JSON 字段。

### 6.2 物理表（Flyway 迁移拆分）

所有数据库物理表统一采用 `tb_` 前缀及小写下划线命名；DDL、Mapper SQL、外键目标和文档引用必须一致。字段、Java 类型、工具/API 名及迁移文件的功能分组名不随表名前缀改变。

PRD 第 8 节的 10 个逻辑实体落为 13 张表，拆表不删除任何约束：

| 迁移 | 表 | 关键约束 / 字段 |
| --- | --- | --- |
| V1__content | `tb_project` | `active_run_id uuid NULL`、`next_chapter_order int`（分配后不复用）、`deleted_at` |
| | `tb_chapter` | `UNIQUE(project_id, chapter_order)`、`version int CHECK (version > 0)`、`content text` |
| | `tb_chapter_revision` | `UNIQUE(chapter_id, version)`、`source` ∈ CREATE/MANUAL/AGENT、`UNIQUE(proposal_id) WHERE proposal_id IS NOT NULL` |
| | `tb_setting` | `type` ∈ CHARACTER/WORLD/RULE、`project_id` 索引 |
| V2__agent_run | `tb_agent_run` | `UNIQUE(owner_id, client_request_id)`、`request_digest`、`status`、`cancel_requested`、`base_version`、`base_content`、`deadline_at`、`process_instance_id`、`last_event_seq`、`prompt_hash`、`tool_schema_hash`、`config_snapshot jsonb` |
| | `tb_agent_prompt_snapshot` | `hash PK`、`kind`、`content`：保存哈希对应的完整内容，满足 PRD 8“仅有哈希不算完整记录” |
| | `tb_agent_run_chapter_view` | `PK(run_id, chapter_id)`、`chapter_order`、`version`、`view_id`：即 chapterViewManifest，用行存储以便和 `tb_chapter_revision` 关联扫描 |
| | `tb_agent_context_item` | contextManifest：`kind` ∈ FULL_CHAPTER/FULL_SETTING/SEARCH_SNIPPET、来源 ID/版本/行号与码点范围、`content_hash`、`tool_execution_id` |
| V3__trace | `tb_agent_model_call` | `UNIQUE(run_id, call_index)`、`input_message_seqs int[]`、`status`、`finish_reason`、`error_code`、`usage jsonb NULL`、`provider_request_id` |
| | `tb_agent_message` | `UNIQUE(run_id, message_seq)`、`role`、`content jsonb`（保留提供方要求回传的块，如 thinking）、`model_call_id`、`tool_call_id` |
| | `tb_agent_step` | `tool_execution_id PK`、`UNIQUE(model_call_id, tool_call_id)`、`args jsonb`、`result jsonb`、`summary`、`status`（含 ABORTED） |
| V4__proposal_event | `tb_edit_proposal` | `UNIQUE(run_id)`、`base_version`、`base_content`、`proposed_content`、`reason`、`status`、`applied_version` |
| | `tb_agent_event` | `PK(run_id, seq)`、`type`、`payload jsonb`、`occurred_at` |

后续迁移 V8 将当前单作者实例的 `owner_id` 字段移除，任务幂等约束变更为 `UNIQUE(client_request_id)`；上述 V1–V4 定义作为历史迁移保留。V9 新增 `tb_model_metadata_cache`，按供应商、服务地址和模型 ID 存储联网获取的窗口、来源与时间。

每次保存正文都会写入 `tb_chapter_revision`，包括当前版本。因此检索和历史查看只需读取 `tb_chapter_revision`，不必区分“最新正文”和“历史正文”两条路径（ADR-005）。

## 7. 前端框架

### 7.1 目录

```text
frontend/src/
├── main.tsx                 # 精简入口：QueryClientProvider + Theme + Router；无 Socket.IO、遥测、桌面桥
├── app/
│   ├── router.tsx           # /projects、/projects/:projectId(/chapters/:chapterId)
│   └── providers.tsx
├── api/
│   ├── http.ts              # fetch 包装：requestId、错误体解析为 ApiError(code)
│   ├── schema.d.ts          # 由 contracts/openapi.yaml 经 openapi-typescript 生成，禁止手改
│   ├── query-keys.ts        # 全部查询键集中定义
│   └── errors.ts            # 由 contracts/error-codes.yaml 生成的常量与文案
├── features/
│   ├── projects/            # FE-01
│   ├── chapters/            # FE-02：ChapterEditor、useChapterDraft、useSaveChapter、离开保护
│   ├── settings/            # FE-03 抽屉
│   ├── agent/               # FE-04：RunPanel、StepList、useCreateRun（幂等键保持）、useRunEvents
│   ├── proposals/           # FE-05：DiffView、accept/reject、编辑器锁
│   └── revisions/           # FE-06：只读历史、按行号定位
└── shared/ui/               # 从上游迁入的基础组件（confirm-dialog、spinner、toast 等）
```

### 7.2 关键 hook 契约

| Hook | 职责 | 对应验收 |
| --- | --- | --- |
| `useChapterDraft(chapterId)` | 本地草稿、基准版本、dirty 标记；保存成功只确认“提交那一刻的快照”；迟到响应只写入对应章节的缓存 | AC-03、AC-09、AC-19、AC-29 |
| `useCreateRun()` | 生成并持有 `clientRequestId`；响应未知时重试沿用同一个键；“重新运行”生成新键 | AC-11 |
| `useRunEvents(runId)` | 快照 → `afterSeq=lastEventSeq` 建立 EventSource；按 `runId+seq` 去重；终态后 close 并重新获取详情；连续失败后降级为 2 秒轮询；卸载时清理 | AC-15、AC-28、AC-30 |
| `useAcceptProposal()` | 请求期间锁定编辑器；成功后让章节和历史查询失效；区分冲突、状态变化与网络错误 | AC-06–10、AC-18、AC-29 |

### 7.3 从上游选择性迁移（M0 输出 `docs/reference/reuse-manifest.md` 细化）

| 上游 | 处置 |
| --- | --- |
| `main.tsx`、`App.tsx`、`app/` | 仅参考 Provider 顺序；重写为精简入口 |
| `features/writing/pages/writing-page.tsx` | 参考三栏布局与组合方式；去掉卷、标签页、笔记、摘要和编辑锁 |
| `features/writing/hooks/use-chapters.ts` | 改编缓存更新思路；接口换成本项目 API，并加入版本冲突处理 |
| `features/writing/components/chapter-sidebar.tsx`、`chapter-list-item.tsx` | 改编为扁平章节列表，不支持拖拽 |
| `features/assistant/lib/agent-socket.ts`、`lib/socket-client.ts` | 仅参考；由 `useRunEvents` + SSE 替代（ADR-003） |
| `components/confirm-dialog.tsx`、`spinner`、`toast` | 直接复用（保留许可声明） |
| Tiptap、dexie、posthog、i18n、nivo、dnd-kit 等 | 不迁入 |

## 8. 配置

所有 PRD 9.2 的限值集中放在一个 `@ConfigurationProperties("mojie")` record 中，并通过 `/system/capabilities` 暴露公开部分，保证前端提示与后端校验同源：

```yaml
mojie:
  owner-id: DEMO_OWNER
  content: { max-chapter-codepoints: 6000, max-setting-codepoints: 2000, max-instruction-codepoints: 2000 }
  run:
    max-running: 2
    max-queued: 8
    queue-timeout: 60s
    run-timeout: 180s
    sweep-interval: 2s
    max-model-calls: 8
    max-tool-calls: 12
    tool-result-budget-codepoints: 30000
  search: { max-view-chapters: 1000, call-timeout: 5s, default-limit: 10, max-limit: 20, snippet-codepoints: 320, page-data-codepoints: 12000 }
  sse: { max-connections: 20, tail-interval: 500ms, heartbeat: 15s, batch-size: 100 }
  model:
    provider: anthropic          # 为空时 Agent 入口不可用，内容编辑照常（AC-30）
    model-id: claude-opus-5
    connect-timeout: 10s
    request-timeout: 60s
    max-tokens: 16000
    effort: high                 # M0/M2 实测后调整
# 密钥只从环境变量 ANTHROPIC_API_KEY 读取，不写入任何 yml
```

Profile 划分：`dev`（开放 `/trace`、本机访问）、`test`（Testcontainers）、`e2e`（使用 `ScriptedModelGateway`，从 `scenarios/*.json` 读取场景，供 Playwright 调用）。

## 9. 模型适配层落地要点（Anthropic）

| 事项 | 设计 |
| --- | --- |
| 模型 | 默认 `claude-opus-5`，由配置指定；一个 run 内固定，写入 `config_snapshot` |
| 重试 | SDK 默认最多重试 2 次，客户端构建时**必须设为 0**；AC-24 用计数的假 HTTP 层验证“一次模型阶段只有一次实际尝试” |
| 超时 | 客户端设 60s 请求超时；每次调用前另取 `min(60s, run 剩余时间)` |
| 响应模式 | 非流式完整响应，`max_tokens≈16000`（PRD 5.7 优先完整响应） |
| 工具定义 | 7 个工具都开启 `strict: true`，schema 设 `additionalProperties: false`；参数仍在工具内按业务规则再校验一次 |
| 并行调用 | 一条助手消息可能包含多个 `tool_use` 块：**串行执行**，但所有 `tool_result` 放进**同一条** user 消息回传；失败的工具返回 `is_error: true` 和结构化错误，不能丢弃 |
| thinking 块 | Opus 5 默认开启自适应思考。响应中的 thinking 块必须**原样保存**到 `tb_agent_message.content` 并在下一轮原样回传；这属于 PRD 5.7 所说的“必要协议元数据”。展示层不显示它们（PRD 5.6 不展示隐藏推理） |
| stop_reason 映射 | `end_turn`→FINAL；`tool_use`→TOOL_CALLS；`max_tokens`→TRUNCATED→任务 FAILED（LIMIT_EXCEEDED，不发布截断稿）；`refusal`→任务 FAILED（MODEL_ERROR，details 记录 refusal 类别）；`pause_turn`/其他→INVALID_MODEL_RESPONSE（P0 不使用服务端工具） |
| 服务端 fallback | **P0 不启用**。它会在同一次调用内换用另一个模型，违反 PRD 5.7“单一模型、无隐藏调用”和 AC-24；如需启用须先修订 PRD |
| 异常映射 | SDK 的类型化异常映射到 `ModelCallException` 的各个子类（鉴权、限流、网络、超时、上下文超限等），不做字符串匹配 |

**需要 M0 实测的风险：** 一次接近 6,000 码点的整章重写，加上思考过程，可能超过 60 秒单次请求上限或 16k 输出上限。M0/M2 用长正文实测后在三个选项中选择并修订 PRD 9.2：降低 effort；改为流式传输并在适配层完整聚合（PRD 5.7 允许）；或调整长度上限。不能静默截短正文。

## 10. 测试框架

| 层 | 工具 | 覆盖 | 运行方式 |
| --- | --- | --- | --- |
| 纯单测 | JUnit 5 + `ScriptedModelGateway` + `FakeClock` | 循环、预算、配对、工具参数、字面匹配/码点、状态机 | `mvn test`，不需要 Docker |
| 架构测试 | ArchUnit | 3.2 节的规则 | 同上 |
| PG 集成测试 | Testcontainers（单例 PG 容器，每个测试 `TRUNCATE`） | 版本条件更新、唯一冲突回读、活动槽、采纳事务、事件 seq、快照读取 | `mvn verify`（Failsafe，`*IT`） |
| 并发测试 | `CyclicBarrier` + 真实 PG | AC-09/10/14/17/22/32 的竞态 | 同上，重复运行 N 次 |
| 运行时测试 | 真实执行器 + 可阻塞的假模型 | 取消竞争、排队取消、线程未退出时的许可、超时扫描 | 同上 |
| 前端单测 | Vitest + Testing Library + MSW | 草稿保护、`useRunEvents` 去重与降级、错误文案 | `pnpm test` |
| 端到端 | Playwright + 后端 `e2e` profile + 真实 PG | 主闭环、刷新恢复、冲突交互 | `pnpm e2e` |
| 真实模型 | 需显式开启（`MOJIE_REAL_MODEL=1`） | 10.2 节的五组固定输入 | 手动执行，结果写入 `docs/verification/` |

`ScriptedModelGateway` 场景格式（示例）：

```json
{ "name": "ac04-settings-then-propose",
  "turns": [
    { "toolCalls": [{ "id": "t1", "name": "read_chapter", "input": { "chapterId": "$target" } }] },
    { "toolCalls": [{ "id": "t2", "name": "list_settings", "input": { "type": "CHARACTER" } }] },
    { "toolCalls": [{ "id": "t3", "name": "read_settings", "input": { "settingIds": ["$setting[0]"] } }] },
    { "toolCalls": [{ "id": "t4", "name": "propose_edit", "input": { "proposedContent": "…", "reason": "…" } }] },
    { "final": "分析……" }
  ] }
```

至少准备两个工具路径不同的场景，证明循环没有写死调用顺序（PRD 10.1）。

## 11. 按里程碑落地的框架工作

| 阶段 | 框架层交付 |
| --- | --- |
| M0 | `docs/reference/openfic-baseline.json`、`engineering-map.md` 骨架；实测模型延迟与输出长度（第 9 节风险） |
| M1 | 搭建后端/前端/compose 脚手架；锁定版本；实现 `common`、`system`、`content`；V1 迁移；ArchUnit 与 Testcontainers 基座；前端入口 + 章节编辑 + 设定抽屉 |
| M2 | `agent.*`、`retrieval`、`event`（先实现 appender + 查询）；V2/V3 迁移；`ScriptedModelGateway`；六个只读工具；补齐 `runtime.md` |
| M3 | `proposal` + `propose_edit`；V4 迁移；差异视图与采纳交互 |
| M4 | `RunEventStream`（SSE）+ `useRunEvents`；Nginx 配置；资源泄漏与重启回归测试；AC-01–41 |

## 12. 待决事项

| 事项 | 默认方案 | 何时决定 |
| --- | --- | --- |
| springdoc 与 Boot 4.1 是否兼容 | 兼容则代码生成 OpenAPI，否则手写 yaml + 契约测试 | M1 首次构建 |
| 模型 effort / 输出上限 / 是否改为流式聚合 | high / 16k / 非流式 | M0 长正文实测后 |
| 根包名 `com.mojie` | 占位 | M1 |
| RR 8 / TS 7 升级 | P0 不升级 | P0 验收后 |

## 连续对话与 Skill 扩展（2026-10-02）

新 Conversation 入口由 [连续对话与 Skill 设计](../design/conversation-skills.md) 承接。CHAT 无目标章，CHAPTER_REVIEW 不提供 propose_edit，CHAPTER_EDIT 保留当前基准全文与作者采纳边界；旧 Run API 保持原预算和独立任务语义。新任务 16 次模型/32 次工具、120000 新增工具码点和 300 秒运行预算，存入配置快照；请求保存历史来源 ID 与完整逻辑输入。终态展示消息和对话活动槽在数据库事务内收尾。

Skill 目录固定于 Run，版本包在文件卷不可变保存，PG 保存状态及引用；Skill 指导不授予权限。新增 context.compacted、skill.loaded、script.started、script.finished、artifact.created 持久化事件，继续使用按 Run 序号恢复的 SSE。文件下载 attachment/nosniff。独立脚本沙箱及协调备份见 [运维说明](../design/conversation-skills-operations.md)。

## 统一助手上下文（2026-10-12）

新 Conversation 消息默认 `ASSISTANT`，客户端不再提交任务模式，可通过 `contextChapterId/contextVersion` 指定优先参考。接收事务固定已保存参考快照，模型按需 list/search/read；参考不要求完整读取，也不自动成为修改目标。`propose_edit` 在统一轮次显式传入实际章节 ID 和已读取版本，正文需在本轮完整读取并进入后续模型请求；完成事务按候选稿绑定的目标发布建议，仍由作者独立采纳。详情区分 `contextChapterId/contextVersion` 与实际建议的 `targetChapterId/baseVersion`。旧任务模式与独立 Run 接口继续兼容。V10 通过新增上下文列及调整约束保留历史，见 [实现设计](unified-assistant-context.md)。
