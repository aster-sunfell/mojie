# 墨界 Mojie

AI 小说写作工作台。管理作品、章节和人物 / 世界观设定，让 Agent 按需查阅前文、检查设定一致性并提出修改建议，由作者确认后保存。

## 当前实现

`backend/` 包含 Java 21 显式 Agent loop、七个工具、PostgreSQL 持久化、有界调度、Messages 与 Chat Completions 模型适配器、内容管理和 HTTP/SSE 接口。后端现已按业务模块采用 `api → service → repository` 三层结构，事务、SQL 与运行资源分别管理；验证结果见 [三层重构记录](docs/verification/backend-three-layer-refactoring-2026-10-02.md)。`web/` 包含作者工作台、任务面板、历史来源和建议采纳界面。运行方式与已验证范围见 [backend/README.md](backend/README.md)、[联调记录](docs/verification/agent-loop-http-and-web-2026-09-29.md)、[真实模型核查](docs/verification/agent-loop-real-model-2026-09-30.md)、[浏览器核查](docs/verification/browser-agent-loop-2026-09-30.md) 和 [数据库恢复核查](docs/verification/agent-loop-db-recovery-2026-09-30.md)。核心链路可用；正式发布验收仍有部分浏览器竞争与故障组合待签收。

## 连续对话与 Skill

工作台助手面板的“连续对话”支持新建、切换、重命名及连续消息；历史对话统一展示。普通聊天不需要章节；章节审阅与修改须选择并保存目标章。Skill 库支持 Markdown、ZIP 和目录文件上传、文件树预览、启停、不可变版本、文本编辑、回滚及完整导出；显式选择或固定版本与每轮自动发现共存。

Skill 脚本使用独立执行器，需要预拉固定 Python/Node 镜像并配置 `MOJIE_SKILL_EXECUTOR_URL`；见 [执行器说明](executor/README.md)。未配置执行器时仍能读取指导及参考。部署新增 `mojie_files` 文件卷；备份须同时包含 PG 和文件，见 [恢复说明](docs/design/conversation-skills-operations.md)。验收证据与尚未签收的故障组合见 [验证记录](docs/verification/conversation-skills-2026-10-02.md)。

新对话采用统一助手：可选章节提供优先上下文，助手按需检索、读取并生成建议；见 [上下文设计](docs/design/unified-assistant-context.md)。

## 本地运行

本地需要 Java 21、Maven、Node 20+ 和 Docker。PostgreSQL 与 Nginx 在容器中按需运行；Java 后端和 Vite 在本机按需启动。从仓库根目录执行，后端与前端分别在自己的终端运行：

```bash
docker compose -f deploy/compose.yaml -p deploy up -d postgres nginx
```

新检出的仓库先将 `deploy/.env.example` 复制为 `deploy/.env`，填写 `MOJIE_MODEL_API_KEY` 即可启用示例模型；通过 `MOJIE_MODEL_API_PATH` 在 `/v1/messages` 与 `/v1/chat/completions` 间切换。模型上下文窗口默认联网获取并缓存；自定义服务无法提供元数据时，可设置 `MOJIE_MODEL_CONTEXT_TOKENS` 覆盖。后端终端运行以下脚本。它会构建 JAR，并从 `deploy/.env` 导出配置后启动 Java：

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

采用 GitHub Actions 构建镜像 → GHCR 发布 → 服务器拉取运行。推送 `main` 后，[镜像工作流](.github/workflows/images.yml) 执行后端单元 / PostgreSQL 集成 / 本地模拟模型测试、部署故障测试、前端测试与静态构建，通过后发布 backend、executor、web 三个镜像。全部构建成功后生成包含三个 digest 的 `deployment-release`，并用 GitHub artifact attestation 证明来源。PR 只测试，不发布镜像；手动运行工作流时也只发布 `main`。当前镜像平台是 `linux/amd64`。

首次手动部署需要 Docker 和 Compose，无需在服务器安装 Maven、Java、Node 或构建镜像。接入自动更新还需要 Python 3、GitHub CLI 和 systemd。首次部署选择 Actions 全部成功的提交，服务器检出同一提交后，复制 `deploy/.env.example` 为 `deploy/.env.prod`，填写数据库密码、稳定的 `MOJIE_CURSOR_KEY` 和 `MOJIE_IMAGE_TAG`（该提交的完整 SHA），按需填写 `MOJIE_MODEL_API_KEY`。GHCR 包默认可能为私有；私有包先执行 `docker login ghcr.io -u aster-sunfell`，在密码提示中输入具有 `read:packages` 权限的 PAT。公开包无需登录。从仓库根目录启动：

```bash
cp deploy/.env.example deploy/.env.prod
# 编辑 deploy/.env.prod，填写真实配置
docker compose --env-file deploy/.env.prod -f deploy/compose.prod.yaml -p mojie-prod pull
docker compose --env-file deploy/.env.prod -f deploy/compose.prod.yaml -p mojie-prod up -d --no-build --wait --wait-timeout 180
```

CI 使用仓库自带的 `GITHUB_TOKEN` 发布镜像，不需要配置服务器 SSH 或生产模型密钥。按 [自动更新操作说明](docs/design/safe-update-operations.md) 完成服务器的一次性接入后，定时器会验签并拉取成功发布，在维护期间用独立数据库和文件卷迁移，失败则恢复旧环境。维护页面阻止操作并保留已打开页面的草稿；开放新版本写入后，恢复会保留新数据。未安装定时器的服务器仍需手动更新。生产入口默认只监听回环地址；公开访问前须配置受保护的 HTTPS 入口，当前 API 没有完整鉴权。保证边界见 [更新与回退方案](docs/design/safe-update-and-rollback.md)，实际验证范围见 [验证记录](docs/verification/safe-update-and-maintenance-2026-10-10.md)。

实际进程重启回归可运行 `node scripts/check-active-run-restart.mjs`：先构建 Java 可执行 JAR，并将 `MOJIE_TEST_JDBC_URL` 指向专用可丢弃的 PostgreSQL 数据库；使用 Java 21。脚本会启动和强制中断它自己的 Java 进程，验证活动任务标为 `SERVICE_RESTARTED`，并核对已成功任务、建议及 SSE 事件；不会调用真实模型。

HTTP 取消回归可运行 `node scripts/check-http-cancel.mjs`：同样先构建 Java 可执行 JAR，并将 `MOJIE_TEST_JDBC_URL` 指向专用可丢弃的 PostgreSQL 数据库；使用 Java 21。脚本验证运行中和排队任务的取消、SSE 回放与正文不变；不会调用真实模型。
正文换行与 Unicode 的 HTTP 往返可运行 `node scripts/check-text-roundtrip.mjs`；它会创建一个独立测试作品并检查当前正文、历史版本和重复保存。

## 文档入口

| 文档 | 用途 |
| --- | --- |
| [MVP PRD v0.3](docs/prd/MVP_PRD_v0.3.md) | 当前开发与验收依据 |
| [连续对话与完整 Skill PRD v0.1](docs/prd/Conversation_and_Skills_PRD_v0.1.md) | 新功能需求与验收门槛 |
| [连续对话与 Skill 设计](docs/design/conversation-skills.md) | 隔离、输入快照、版本与执行器边界 |
| [Skill 部署及恢复](docs/design/conversation-skills-operations.md) | 文件卷、执行器、协调备份与独立恢复 |
| [显式 Agent 工具循环 PRD v0.1](docs/prd/Agent_Tool_Loop_PRD_v0.1.md) | 章节检查的工具循环、预算边界、异常规则与专项验收 |
| [架构决策 v0.3](docs/adr/MVP_ADR_v0.3.md) | 选型、取舍与重评条件 |
| [工程框架设计 v0.1](docs/design/Framework_Design_v0.1.md) | 仓库结构、模块边界、核心接口、线程/事务骨架与测试框架 |
| [后端三层架构与重构方案 v0.1](docs/design/Backend_Three_Layer_Refactoring_v0.1.md) | 已实施的三层职责、包结构、事务约束与验收标准 |
| [环境与部署方案](docs/design/development-deployment-environment.md) | 本地容器按需启动、远程持续运行与代码更新步骤 |
| [安全自动更新与失败回退方案](docs/design/safe-update-and-rollback.md) | 自动拉取发布版本、隔离迁移、数据保护与失败回退的保证边界 |
| [自动更新与回退操作说明](docs/design/safe-update-operations.md) | 维护界面、服务器首次接入、定时更新与故障恢复 |
| [项目亮点与简历素材 v0.1](docs/resume/Project_Highlights_v0.1.md) | 设计亮点、面试素材与实现前置条件 |

历史文档：[PRD v0.1](docs/prd/MVP_PRD_v0.1.md)、[PRD v0.2](docs/prd/MVP_PRD_v0.2.md)。它们保留用于追溯，不与 v0.3 并行作为开发依据。

## 工程参考

参考 OpenFic `v0.11.1`，固定提交 `fd62cb4942f8f6a080a8421de1ea121ef9fee7fb`。本地参考副本位于 `reference/OpenFic/`，由 `.gitignore` 排除，保留上游名称与内容；获取和核验方法见 PRD 第 0 节。

实施路线是基于固定上游源码裁剪重实现：Java 后端独立运行，React 前端选择性迁移。上游参考代码不代表本项目功能已经完成。参考环境、源码映射与复用范围分别记录在 [基线](docs/reference/openfic-baseline.json)、[工程映射](docs/reference/engineering-map.md)、[复用清单](docs/reference/reuse-manifest.md)；实际观察与未验证边界见 [M0 记录](docs/verification/openfic-m0-2026-09-29.md)。
