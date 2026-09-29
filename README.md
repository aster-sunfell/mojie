# 墨界 Mojie

AI 小说写作工作台。管理作品、章节和人物 / 世界观设定，让 Agent 按需查阅前文、检查设定一致性并提出修改建议，由作者确认后保存。

## 当前实现

`backend/` 包含 Java 21 显式 Agent loop、七个工具、PostgreSQL 持久化、有界调度、Anthropic 适配器、内容管理和 HTTP/SSE 接口。`web/` 包含作者工作台、任务面板、历史来源和建议采纳界面。运行方式与已验证范围见 [backend/README.md](backend/README.md) 和 [本次联调记录](docs/verification/agent-loop-http-and-web-2026-09-29.md)。真实模型评估仍需在配置 API key 后执行。

## 本地运行

需要 Java 21、Maven、Node 20+、PostgreSQL 18。先执行 `docker compose -f deploy/compose.yaml up -d`，再在 `backend/` 运行 `mvn package && java -jar target/mojie-agent-core-0.1.0-SNAPSHOT-exec.jar`，在 `web/` 运行 `npm install && npm run dev`，打开 `http://127.0.0.1:5173`。未设置 `ANTHROPIC_API_KEY` 时可编辑内容，任务提交入口会说明模型未配置。

固定演示数据可在服务启动后用 `node scripts/seed-demo.mjs` 创建；加 `--scale` 会创建 100 章与 200 条设定，并打印作品及目标章节 ID。对扩展数据执行 `node scripts/check-m4-load.mjs PROJECT_ID TARGET_CHAPTER_ID`，检查分页无重复和 20 次非模型请求延迟。两脚本使用 `MOJIE_API_URL` 指定 API 根地址，默认为 `http://127.0.0.1:8080/api/v1`。同源静态部署可参考 [Nginx 示例](deploy/nginx.conf.example)，需按实际路径调整根目录与后端地址。

实际进程重启回归可运行 `node scripts/check-active-run-restart.mjs`：先构建 Java 可执行 JAR，并将 `MOJIE_TEST_JDBC_URL` 指向专用可丢弃的 PostgreSQL 数据库；使用 Java 21。脚本会启动和强制中断它自己的 Java 进程，验证活动任务标为 `SERVICE_RESTARTED`，并核对已成功任务、建议及 SSE 事件；不会调用真实模型。

HTTP 取消回归可运行 `node scripts/check-http-cancel.mjs`：同样先构建 Java 可执行 JAR，并将 `MOJIE_TEST_JDBC_URL` 指向专用可丢弃的 PostgreSQL 数据库；使用 Java 21。脚本验证运行中和排队任务的取消、SSE 回放与正文不变；不会调用真实模型。
正文换行与 Unicode 的 HTTP 往返可运行 `node scripts/check-text-roundtrip.mjs`；它会创建一个独立测试作品并检查当前正文、历史版本和重复保存。

## 文档入口

| 文档 | 用途 |
| --- | --- |
| [MVP PRD v0.3](docs/prd/MVP_PRD_v0.3.md) | 当前开发与验收依据 |
| [显式 Agent 工具循环 PRD v0.1](docs/prd/Agent_Tool_Loop_PRD_v0.1.md) | 章节检查的工具循环、预算边界、异常规则与专项验收 |
| [架构决策 v0.3](docs/adr/MVP_ADR_v0.3.md) | 选型、取舍与重评条件 |
| [工程框架设计 v0.1](docs/design/Framework_Design_v0.1.md) | 仓库结构、模块边界、核心接口、线程/事务骨架与测试框架 |
| [项目亮点与简历素材 v0.1](docs/resume/Project_Highlights_v0.1.md) | 设计亮点、面试素材与实现前置条件 |

历史文档：[PRD v0.1](docs/prd/MVP_PRD_v0.1.md)、[PRD v0.2](docs/prd/MVP_PRD_v0.2.md)。它们保留用于追溯，不与 v0.3 并行作为开发依据。

## 工程参考

参考 OpenFic `v0.11.1`，固定提交 `fd62cb4942f8f6a080a8421de1ea121ef9fee7fb`。本地参考副本位于 `reference/OpenFic/`，由 `.gitignore` 排除，保留上游名称与内容；获取和核验方法见 PRD 第 0 节。

实施路线是基于固定上游源码裁剪重实现：Java 后端独立运行，React 前端选择性迁移。上游参考代码不代表本项目功能已经完成。参考环境、源码映射与复用范围分别记录在 [基线](docs/reference/openfic-baseline.json)、[工程映射](docs/reference/engineering-map.md)、[复用清单](docs/reference/reuse-manifest.md)；实际观察与未验证边界见 [M0 记录](docs/verification/openfic-m0-2026-09-29.md)。
