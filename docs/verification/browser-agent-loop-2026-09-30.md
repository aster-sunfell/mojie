# Agent loop 浏览器端到端核查（2026-09-30）

使用 Java 21 可执行后端、PostgreSQL 18.6 独立数据库、Vite、Chromium Headless Shell，以及只在本机监听的模拟 Messages 模型。Playwright 在浏览器中访问实际 HTTP/SSE 接口；测试不使用真实模型密钥。运行命令和测试库准备见仓库 README。

`MOJIE_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/mojie_browser_e2e MOJIE_TEST_DB_USER=mojie MOJIE_TEST_DB_PASSWORD=mojie_dev_only npm --prefix web run test:e2e`：3 个场景通过。

1. 模型调用保持 7 秒，任务进入 RUNNING 后刷新页面，并阻断浏览器的 SSE 请求。页面从后端快照及轮询恢复到 SUCCEEDED，展示实际保存的分析和完整章节来源。断线或刷新没有取消任务。
2. 模型通过真实工具回填读取目标章并提交候选稿。作者在编辑器加入未保存草稿时，采纳按钮禁用；打开历史来源不覆盖草稿。恢复原文后整体采纳，正文升级，刷新后仍为新稿；数据库历史只有一条 AGENT 修订。
3. 模型请求保持 30 秒时，浏览器提交运行中取消。界面先显示“正在停止”，待该本地调用退出后转为 CANCELLED，没有建议。等待期间仍占用运行容量，符合 PRD 6.5、7.2 的取消语义。

第一次运行时，第三个场景沿用了 20 秒的默认断言超时而失败：此时数据库状态为 RUNNING 且 `cancel_requested=true`，界面显示“正在停止”。按 PRD 核对后将该断言改为允许等待阻塞模型请求结束；重跑全部 3 项通过。此处没有改变产品取消逻辑。

本轮仍未覆盖多标签页同版本冲突、SSE 重连中的旧快照竞争、浏览器在建议采纳后刷新失败，以及真实数据库故障恢复。相关单元与集成测试可提供部分证据，但不能代替这些浏览器场景。
