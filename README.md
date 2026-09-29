# 墨界 Mojie

AI 小说写作工作台。管理作品、章节和人物 / 世界观设定，让 Agent 按需查阅前文、检查设定一致性并提出修改建议，由作者确认后保存。

## 当前实现

`backend/` 包含 Java 21 显式 Agent loop、七个工具、PostgreSQL 持久化、有界调度、Messages 与 Chat Completions 模型适配器、内容管理和 HTTP/SSE 接口。`web/` 包含作者工作台、任务面板、历史来源和建议采纳界面。运行方式与已验证范围见 [backend/README.md](backend/README.md)、[联调记录](docs/verification/agent-loop-http-and-web-2026-09-29.md)、[真实模型核查](docs/verification/agent-loop-real-model-2026-09-30.md)、[浏览器核查](docs/verification/browser-agent-loop-2026-09-30.md) 和 [数据库恢复核查](docs/verification/agent-loop-db-recovery-2026-09-30.md)。核心链路可用；正式发布验收仍有部分浏览器竞争与故障组合待签收。

## 本地运行

本地需要 Java 21、Maven、Node 20+ 和 Docker。PostgreSQL 与 Nginx 在容器中按需运行；Java 后端和 Vite 在本机按需启动。从仓库根目录执行，后端与前端分别在自己的终端运行：

```bash
docker compose -f deploy/compose.yaml -p deploy up -d postgres nginx
```

新检出的仓库先将 `deploy/.env.example` 复制为 `deploy/.env`，填写 `MOJIE_MODEL_API_KEY` 即可启用模型；通过 `MOJIE_MODEL_API_PATH` 在 `/v1/messages` 与 `/v1/chat/completions` 间切换。后端终端运行以下脚本。它会构建 JAR，并从 `deploy/.env` 导出配置后启动 Java：

```bash
sh scripts/start-local-backend.sh
```

前端终端运行 `(cd web && npm ci && npm run dev)`。浏览器打开 `http://127.0.0.1:8088`，通过容器中的 Nginx 访问。只测后端 API 时可只启动 `postgres`；结束后按需执行 `docker compose -f deploy/compose.yaml -p deploy stop nginx postgres`，数据卷会保留。`deploy` 是原有本地 Compose 项目名，沿用它可继续使用现有数据库卷。本地 Nginx 使用 Linux 主机网络，Java 与 Vite 可继续只监听 `127.0.0.1`。在 `deploy/.env` 中留空 `MOJIE_MODEL_API_KEY` 时可编辑内容，任务提交入口会说明模型未配置。`MOJIE_MODEL_BASE_URL` 填域名根地址，不包含 `/v1`；切换接口后重启后端生效。不支持的接口路径会使后端在启动时明确报错。

固定演示数据可在服务启动后用 `node scripts/seed-demo.mjs` 创建；加 `--scale` 会创建 100 章与 200 条设定，并打印作品及目标章节 ID。对扩展数据执行 `node scripts/check-m4-load.mjs PROJECT_ID TARGET_CHAPTER_ID`，检查分页无重复和 20 次非模型请求延迟。两脚本使用 `MOJIE_API_URL` 指定 API 根地址，默认为 `http://127.0.0.1:8080/api/v1`；要经 Nginx 检查时设为 `http://127.0.0.1:8088/api/v1`。

浏览器端到端回归使用独立、可丢弃的 PostgreSQL 数据库和本地模拟模型，不读取 `deploy/.env` 的真实密钥。先创建测试库并安装 Chromium Headless Shell，然后执行；Playwright 启动脚本会自动构建 Java 后端：

```bash
(cd web && npm ci && npx playwright install chromium --only-shell)
docker exec deploy-postgres-1 psql -U mojie -d postgres -c 'CREATE DATABASE mojie_browser_e2e OWNER mojie'
MOJIE_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/mojie_browser_e2e \
  MOJIE_TEST_DB_USER=mojie MOJIE_TEST_DB_PASSWORD=mojie_dev_only npm --prefix web run test:e2e
```

首次创建测试库后，后续直接重跑最后一条命令即可。Playwright 会启动真实 Java API、Vite 和本地模拟模型，测试浏览器刷新、SSE 中断后的恢复、来源/草稿保护、采纳与运行中取消。结果见 [浏览器核查记录](docs/verification/browser-agent-loop-2026-09-30.md)。

## 远程部署

远程主机只需 Docker 和 Compose。首次拉取仓库后，复制同一份 `deploy/.env.example` 为 `deploy/.env.prod`，取消注释并填写数据库密码与稳定的 `MOJIE_CURSOR_KEY`，按需填写 `MOJIE_MODEL_API_KEY`。从仓库根目录启动：

```bash
cp deploy/.env.example deploy/.env.prod
# 编辑 deploy/.env.prod，填写真实配置
docker compose --env-file deploy/.env.prod -f deploy/compose.prod.yaml -p mojie-prod up -d --build
```

生产配置让 PostgreSQL、Java 后端和 Nginx 持续运行，并默认只把 Nginx 发布到服务器的 `127.0.0.1:8088`。公开访问前须配置受保护的 HTTPS 入口；当前 API 没有完整鉴权。推送新提交后的更新命令、数据库备份及单实例发布约束见 [环境与部署方案](docs/design/development-deployment-environment.md)。

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
| [环境与部署方案](docs/design/development-deployment-environment.md) | 本地容器按需启动、远程持续运行与代码更新步骤 |
| [项目亮点与简历素材 v0.1](docs/resume/Project_Highlights_v0.1.md) | 设计亮点、面试素材与实现前置条件 |

历史文档：[PRD v0.1](docs/prd/MVP_PRD_v0.1.md)、[PRD v0.2](docs/prd/MVP_PRD_v0.2.md)。它们保留用于追溯，不与 v0.3 并行作为开发依据。

## 工程参考

参考 OpenFic `v0.11.1`，固定提交 `fd62cb4942f8f6a080a8421de1ea121ef9fee7fb`。本地参考副本位于 `reference/OpenFic/`，由 `.gitignore` 排除，保留上游名称与内容；获取和核验方法见 PRD 第 0 节。

实施路线是基于固定上游源码裁剪重实现：Java 后端独立运行，React 前端选择性迁移。上游参考代码不代表本项目功能已经完成。参考环境、源码映射与复用范围分别记录在 [基线](docs/reference/openfic-baseline.json)、[工程映射](docs/reference/engineering-map.md)、[复用清单](docs/reference/reuse-manifest.md)；实际观察与未验证边界见 [M0 记录](docs/verification/openfic-m0-2026-09-29.md)。
