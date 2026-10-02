# 后端三层重构验证 · 2026-10-02

按 [Backend Three Layer Refactoring v0.1](../design/Backend_Three_Layer_Refactoring_v0.1.md) 完成现有业务链路迁移。实施前源码为 `ecfe2bb`；设计文档原核查基线 `8d3119a` 保留为设计背景。本次交付按职责创建本地提交：相互依赖的后端迁移与原有测试适配保持原子提交，新增架构与回归检查、重构文档与验证记录分别提交。这些提交不表示 R0–R6 各阶段曾独立迁移。

## 实施结果

- 接口按 content、agent、proposal、system 拆分；作者由 ActorProvider 注入。28 条原有路由的方法和路径逐项对照，无新增或删除。
- 业务判断、权限、版本、幂等、状态转换和短事务进入 Service。Repository 接口返回有类型的 record 或明确的标量结果；SQL、行映射、条件更新和数据库超时留在 JDBC 实现。
- TransactionRunner 用 Spring TransactionTemplate 实现 REQUIRED、REQUIRES_NEW 与独立只读 REPEATABLE READ。Repository 通过 DataSourceUtils 加入当前事务，不自行 commit、rollback 或修改 auto-commit。连接及提交异常统一转换为 StorageException，业务错误不再依赖 JDBC 类。
- ChapterSaveCoordinator 联合保存正文、历史、逐项审核及全部版本关联，统一锁序为作品、按 ID 排序的建议、章节。内容相同不增版本；非法审核整笔回滚。整稿冲突提交 CONFLICTED 后返回 HTTP 409。
- RunService、AgentRunner、受理/生命周期/完成/轨迹/查询 Service 与 BoundedRunExecutor、RunRegistry、RunHandle、TimeoutSweeper 分离。保留 2+8 执行容量、一次释放、运行取消等工作线程退出、迟到终态保护和存储恢复补偿；调度拒绝的待补偿身份也保留。
- 工具经 ContentReadService 读取内容，ToolContentService 在独立写事务中固定章节视图，提交后才供正文扫描使用。共享 QueryCancellation 契约避免内容模块反向依赖 Agent。
- AgentLoop 与工具算法保留；ModelGateway 移至业务能力接口，提供方实现移至 infrastructure。启动与装配归 config；SSE 适配归 agent.api.sse，作为 Spring 管理的 Bean 持有有界发送资源。
- 清除旧 Jdbc*Service、RunScheduler、旧 Controller 和旧装配入口。ArchUnit 无遗留豁免。同步根 README、backend README、原框架分层说明、运行事务、工程映射、复用清单和失效源码链接。

没有增加 Flyway 迁移、数据库表、HTTP 功能、连续对话或 Skill 执行能力。数据库仍使用既有 V1–V5。

## 环境与结果

Java 21.0.12.1、Maven 3.9.14、真实 PostgreSQL 18.6。专用可丢弃测试库为 `mojie_refactor_test`；集成测试各自创建并删除随机 schema。HTTP 对照的两个应用使用不同的随机 schema；浏览器测试也使用该专用测试库。未连接作者工作数据库，未请求真实模型；两种提供方协议均使用本地 HTTP fixture。

| 验证 | 实际执行 | 失败 / 错误 | 跳过 |
| --- | ---: | ---: | ---: |
| 原有基线单元测试 | 42 | 0 | 0 |
| 原有基线 PostgreSQL / 模型集成测试 | 40 | 0 | 0 |
| 重构后单元与架构测试 | 55 | 0 | 0 |
| 重构后 PostgreSQL / 模型集成测试 | 44 | 0 | 0 |
| 单独执行两种模型协议测试（已包含于上述集成数） | 12 | 0 | 0 |
| 最终版本浏览器 E2E | 33 | 0 | 0 |
| 旧版与新版 HTTP / SSE 响应对照 | 31 | 0 个差异 | 0 |

保留全部原有 AgentLoop、ProposalDiff、模型配置与协议、内容、工具读取和运行并发/故障断言。旧测试改名为 ContentServicesIT、ToolContentServiceIT、RunServicesIT；support 中的 fixture 只负责接入新服务，不保留旧生产实现。新增 10 项架构检查、3 项业务规则单测、2 项事务传播/隔离测试、2 项多建议原子性/并发测试。

多建议测试证明：两份建议以相反顺序并发提交，只有一个保存成功，另一请求返回版本冲突，不产生重复历史；成功版本关联两份建议。第二份审核非法时，第一份已执行的决策写入、正文、历史和关联全部回滚。事务测试证明 REQUIRED 参与者共同回滚，REQUIRES_NEW 可独立提交，快照在外层 READ COMMITTED 中仍保持独立只读 REPEATABLE READ。

原有取消、十个名额、同作品竞争、超时、搜索取消/数据库超时、迟到旧任务、工具/终态故障、丢失提交确认和连接恢复补偿用例继续通过。最终存储回归使用与 Spring Boot 相同的 JdbcTransactionManager。

[机器可读结果与 28 条路由清单](backend-three-layer-results-2026-10-02.json)记录每个测试类的执行数。初始 target 中残留的历史单独测试报告没有重复计入基线。

## HTTP / SSE 核对

[31 组规范化响应样例](backend-three-layer-http-contract-2026-10-02.json)来自旧源码和新源码编译出的两个真实 Spring Boot 应用，而非 mock Controller。替换动态 UUID、requestId 和时间值后，状态码、字段、空值、正文、预算、事件与错误响应均无差异。

对照覆盖 health/capabilities、作品和章节创建/查询、Unicode 与 CRLF 保存、同文保存、重命名、分页、历史、设定读写/删除、版本冲突、非法游标、资源不存在、受理、任务列表/详情/轨迹、幂等重试与冲突、终态取消、非法事件游标、完整事件回放和 Last-Event-ID 优先级。建议编辑、采纳、拒绝及并发状态由 PostgreSQL 和浏览器测试补充覆盖；SSE 第 21 条连接拒绝、终态后名额恢复由浏览器回归覆盖。

这些对照证明已执行样例的兼容性，不宣称穷举所有输入或所有故障组合。初次 HTTP 对照的本地模型 fixture 缺少 Connection: close，导致旧版第二次请求失败；修正测试 fixture 后，旧、新两版均完成相同的两轮工具调用，此测试工具问题不计为应用回归。

## 复现命令与边界

数据库凭据通过环境注入，不写入文档或代码。后端目录实际执行：

```bash
mvn test
mvn verify
MOJIE_TEST_LOCAL_HTTP=1 mvn -Dtest=AnthropicModelGatewayIT,ChatCompletionsModelGatewayIT test
# 最后一次事务管理器适配后再次执行完整单元和集成套件：
mvn test failsafe:integration-test failsafe:verify
mvn -Dmaven.test.skip=true package
# 同步全部测试包路径后，最终完整构建与回归：
mvn clean verify
```

上述数据库套件执行时均设置 MOJIE_TEST_JDBC_URL、MOJIE_TEST_DB_USER、MOJIE_TEST_DB_PASSWORD；模型协议套件设置 MOJIE_TEST_LOCAL_HTTP=1。未配置数据库时出现的跳过不能作为集成验收。

在独立临时目录从 `ecfe2bb` 源码构建旧版 executable JAR；新版 JAR 构建完成后先复制到独立路径，防止与浏览器测试的构建覆盖相互影响。从仓库根目录运行：

```bash
python3 scripts/check-backend-refactor-contract.py \
  --baseline-jar /path/to/baseline-exec.jar \
  --candidate-jar /path/to/refactored-exec.jar \
  --output /tmp/mojie-http-contract-new.json
```

此脚本要求专用数据库环境变量和本地 PostgreSQL 容器，默认容器为 deploy-postgres-1；可通过 --postgres-container 指定。脚本运行两个本地应用和一个模拟模型，保存对照结果后停止应用、删除自己的 schema。输出文件已存在时拒绝覆盖。

web 目录执行 `npm run test:e2e`，同样注入专用数据库变量；33 项最终回归全部通过。源码路由清单、SQL 边界扫描、失效后端源码链接检查及 `git diff --check` 均通过。

本次未改模型输入内容或协议算法，未重新进行真实模型文学质量与提供方稳定性验收。已有 V3.2 超时、文学输出质量和正式发布剩余签收项继续以先前的验证记录为准，三层重构通过不替代这些验收。
