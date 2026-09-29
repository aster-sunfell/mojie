# 开发与部署环境方案

**状态：配置已实现并完成本地容器验证。** 本文说明当前的本地与远程配置及操作方式。快速启动方式见 [项目 README](../../README.md)。

## 目标与生命周期

| 场景 | PostgreSQL | Nginx | Java 后端 | 前端构建 / 开发 | 生命周期 |
| --- | --- | --- | --- | --- | --- |
| 本地开发 | Docker 容器 | Docker 容器，按需启动 | 本机 Java 21，按需启动 | 本机 Node 20+ / Vite，按需启动 | PostgreSQL 与 Nginx 均由开发者手动启动、停止；本机进程各自按需启动 |
| 远程部署 | Docker 容器 | Docker 容器，持续运行 | Docker 容器中的 Java 21 | Docker 中用 Node 构建静态页面 | 首次部署后持续运行；容器退出或宿主机重启后自动恢复 |

`docker compose up -d` 只让容器在后台运行，不决定重启策略。本地 PostgreSQL 与 Nginx 均明确设置 `restart: "no"`；远程 PostgreSQL、后端与 Nginx 设置 `restart: always`，并启用宿主机上的 Docker 开机启动。远程前端是静态构建产物，Node 只需要存在于构建阶段，无需常驻 Node 服务。远程宿主机无需安装 Java、Node、PostgreSQL 或 Nginx。

## 当前实现与调整范围

- [本地 Compose](../../deploy/compose.yaml) 定义按需启动的 PostgreSQL 和 Nginx；PostgreSQL 端口绑定 `127.0.0.1:5432`。
- [后端配置](../../backend/src/main/resources/application.properties) 默认连接 `localhost:5432`，监听 `127.0.0.1:8080`；生产 Compose 为容器覆盖这两个地址。
- [Vite 配置](../../web/vite.config.js) 将 `/api` 代理到本机 8080；[前端 API](../../web/src/api.js) 使用相对路径，适合让本地与远程浏览器统一从 Nginx 入口访问。
- [Nginx 本地配置](../../deploy/nginx.conf) 代理本机 Vite 和 Java；[生产配置](../../deploy/nginx.prod.conf) 提供静态页面并代理容器后端。
- [生产 Compose](../../deploy/compose.prod.yaml) 定义 PostgreSQL、后端和 Nginx 三个持续运行的服务。

镜像与环境文件的职责如下：

| 文件 | 作用 |
| --- | --- |
| `backend/Dockerfile` | 多阶段构建：Maven/JDK 21 编译可执行 JAR；最终镜像只保留 Java 21 运行环境和 JAR |
| `backend/.dockerignore` | 排除 `target` 等本地产物 |
| `web/Dockerfile` | 用锁文件执行 `npm ci`、`npm run build`；将 `dist` 复制到 Nginx 镜像 |
| `.dockerignore` | 前端构建以仓库根目录为 context，排除无关目录及本地产物 |
| `deploy/compose.prod.yaml` | 独立定义 PostgreSQL、后端、Nginx 三个常驻服务、网络、数据卷、健康检查和重启策略 |
| `deploy/.env.example` | 本地与生产共用的环境变量示例；分别复制为不入库的 `deploy/.env` 和 `deploy/.env.prod` |
| `deploy/nginx.conf` | 本地 Nginx：`/` 代理到本机 Vite，`/api` 代理到本机 Java；支持 Vite 热更新和 SSE |
| `deploy/nginx.prod.conf` | 远程 Nginx：提供前端静态文件，并将 `/api` 代理到后端；SSE 路径关闭代理缓冲 |

本地 Compose 的 `nginx` 服务使用 Nginx 镜像，只读挂载 `nginx.conf`，并与 PostgreSQL 一样设置 `restart: "no"`；它不依赖 PostgreSQL 的健康状态，以便单独测试前端。`.gitignore` 排除了实际使用的 `deploy/.env` 和 `deploy/.env.prod`。生产 Compose 独立定义服务，不与本地 Compose 叠加，以免本地 PostgreSQL 主机端口映射进入生产。本地沿用原有的 `deploy` 项目名及数据库卷，生产使用 `mojie-prod` 项目名，使两套容器、网络和数据卷彼此隔离。

## 网络、配置与启动顺序

本地后端继续使用 `jdbc:postgresql://localhost:5432/mojie`。在当前 Linux 开发环境中，本地 Nginx 容器使用 `network_mode: host`，配置为仅监听 `127.0.0.1:8088`，将 `/api` 转发到本机 Java 的 `127.0.0.1:8080`，将其余前端请求转发到本机 Vite 的 `127.0.0.1:5173`。这样 Java 与 Vite 可继续只监听本机回环地址。本地 Nginx 不设置 Compose `ports`，浏览器统一打开 `http://127.0.0.1:8088`。开发配置还需转发 WebSocket Upgrade，并将 Vite HMR 的浏览器端端口设为 8088；SSE 路径关闭代理缓冲。直接访问 Vite 的 5173 端口仅用于调试，不作为本地集成测试入口。

本地 Nginx 与 PostgreSQL 可分别按需启动：只测后端 API 时无需启动 Nginx；通过浏览器测试工作台时启动 Nginx 和 Vite，相关 API 功能还需要本机 Java 与 PostgreSQL。Nginx 可先于本机进程启动，此时对应上游尚未启动的请求会暂时失败。现有 Vite `/api` 代理可供直接访问 5173 的调试场景使用，浏览器通过 8088 访问时由 Nginx 代理 `/api`。

远程容器使用如下内部地址：

| 配置 | 远程部署值 |
| --- | --- |
| `MOJIE_DB_URL` | `jdbc:postgresql://postgres:5432/mojie` |
| `MOJIE_BIND_HOST` | `0.0.0.0` |
| Nginx `/api` 上游 | `http://backend:8080` |

远程 PostgreSQL 不发布主机端口，后端也只在 Compose 网络内供 Nginx 访问；默认只有 Nginx 绑定服务器的 `127.0.0.1:8088`。要从其他机器访问，应在其前面配置受保护的 HTTPS 入口；需要调整绑定地址与端口时，可在 `deploy/.env.prod` 中设置可选的 `MOJIE_HTTP_BIND` 和 `MOJIE_HTTP_PORT`，生产 Compose 未设置时使用默认值。Nginx 代理保留原始 `/api` 路径，并为 SSE 关闭缓冲、设置较长的读取超时。

生产环境必须在 `deploy/.env.prod` 中显式提供数据库密码，以及稳定且私密的 `MOJIE_CURSOR_KEY`；启用模型任务时再提供 `ANTHROPIC_API_KEY`。可从 [共用示例](../../deploy/.env.example) 复制，取消注释并填入真实值后保留在服务器，不提交到 Git。前后端构建产物不得包含模型密钥。`MOJIE_OWNER_ID`、`MOJIE_MODEL_ID` 等按部署需要配置。更改已有 PostgreSQL 数据卷对应的 `POSTGRES_PASSWORD` 环境变量不会自动修改数据库中已有用户的密码，轮换密码需要单独操作。

生产配置在 PostgreSQL 健康后启动后端，后端健康后再开放 Nginx。后端启动时由 `RunScheduler.start()` 执行 Flyway 迁移和旧进程任务清理。[健康接口](../../backend/src/main/java/com/mojie/api/AgentController.java) 在调度器为 `DEGRADED` 时仍返回 HTTP 200，因此容器健康检查解析响应体，只接受 `UP`。Compose 的 `depends_on` 处理初次启动顺序，不能代替运行期健康监控；`restart: always` 只会重启已退出的容器。

## 操作方式

以下命令均从仓库根目录执行，后端与前端命令各占用一个终端。首次生产部署先将 `deploy/.env.example` 复制为 `deploy/.env.prod`，取消注释并填写数据库密码和 `MOJIE_CURSOR_KEY`。

```bash
# 本地：按需分别启动、停止容器；stop 保留数据库卷
docker compose -f deploy/compose.yaml -p deploy up -d postgres
docker compose -f deploy/compose.yaml -p deploy up -d nginx
docker compose -f deploy/compose.yaml -p deploy stop nginx
docker compose -f deploy/compose.yaml -p deploy stop postgres

# 本地：后端与前端分别按需启动
sh scripts/start-local-backend.sh
(cd web && npm ci && npm run dev)

# 远程：首次部署；实际密钥保存在服务器的 deploy/.env.prod
docker compose --env-file deploy/.env.prod -f deploy/compose.prod.yaml -p mojie-prod up -d --build
```

远程宿主机还需启用 Docker 服务的开机启动。`down` 会移除容器，因此不作为日常暂停命令；删除数据卷（例如 `down -v`）会删除数据库数据。生产数据卷需要定期备份并验证恢复。

## 推送代码后的远程更新

`git push origin main` 只更新 Git 远端，不会让生产服务器自动拉取代码或重建容器。首次部署时，在服务器克隆仓库、创建不入库的 `deploy/.env.prod`，并完成上一节的首次启动。后续发布在服务器的仓库根目录执行：

```bash
# 先确认服务器工作区没有需要保留的未提交修改
git status --short
git pull --ff-only origin main
git rev-parse --short HEAD

# 使用新代码构建镜像，并让 Compose 重建需要更新的服务
docker compose --env-file deploy/.env.prod -f deploy/compose.prod.yaml -p mojie-prod up -d --build
docker compose --env-file deploy/.env.prod -f deploy/compose.prod.yaml -p mojie-prod ps
```

`main` 是当前仓库的分支名；如将来改用发布分支，应同步修改拉取命令。`deploy/.env.prod` 保留在服务器，不随 Git 更新；生产 Compose 会把其中的数据库密码、模型密钥等变量传给相应服务。发布后检查容器健康状态、前端页面、`/api/v1/health` 的响应体以及 SSE；该接口在 `DEGRADED` 时也返回 HTTP 200，不能只看状态码。

正常应用更新不执行 `docker compose down`，并保留 PostgreSQL 数据卷。若本次提交包含数据库迁移，发布前备份数据库并确认迁移与当前数据兼容；若更换 PostgreSQL 大版本或镜像配置，单独制定数据库升级步骤。后端重建会造成短暂服务中断；在旧进程中未完成的 Agent 任务会在新进程启动时标记为 `SERVICE_RESTARTED`，因此安排在任务空闲时发布。当前单实例设计不支持多副本滚动更新。

后续如需 push 后自动发布，可用 CI 构建、校验并发布镜像，再由受控部署任务在服务器拉取镜像并执行 Compose 更新；自动化仍需保留数据库备份、健康检查和单实例发布约束。`restart: always` 只负责容器退出或宿主机重启后的恢复，不会自动获取 Git 新提交。

## 配置验证记录（2026-09-29）

- 本地与生产 Compose 均通过 `docker compose config --quiet`；两份 Nginx 配置均通过目标 Nginx 镜像的 `nginx -t`。
- 本地 Nginx 容器经 `127.0.0.1:8088` 返回 Vite 页面和 `/@vite/client`，Vite 热更新 WebSocket 握手成功；测试后已停止该容器和 Vite。
- 后端与前端生产镜像均构建成功。独立的临时生产 Compose 项目中，PostgreSQL、后端、Nginx 均为 healthy；页面、`/api/v1/health` 与 `/api/v1/system/capabilities` 经 Nginx 返回成功。临时项目及其专用数据库卷已清理，原有本地 PostgreSQL 未改动。
- 前端 `npm run build` 与 `npm test -- --run` 通过，合计 16 个测试。

## 验收与边界

1. 本地 PostgreSQL 与 Nginx 均可单独按需启动、停止；手动停止后，重启 Docker 或宿主机不会自动启动它们，数据库卷仍在。本机 Java 与 Vite 不随 Compose 启动。
2. 本地浏览器通过 `127.0.0.1:8088` 访问工作台；普通 API、SSE 与 Vite 热更新都经过容器中的 Nginx。
3. 远程主机不安装 Java、Node、PostgreSQL 或 Nginx 也能构建并启动服务；浏览器同源访问前端、普通 API 和 SSE。
4. 单个生产容器意外退出及宿主机重启后，三个常驻服务恢复；数据库未向主机发布端口。验证服务健康状态，而不只验证容器进程存在。
5. 部署更新与重启后，数据库内容及 `MOJIE_CURSOR_KEY` 保持稳定；备份可恢复。

当前调度器按单后端实例设计，启动时会清理旧进程任务；远程部署保持一个后端副本，不采用多副本滚动更新。当前 API 没有完整鉴权，若入口可从公网访问，须先增加受保护的访问入口，不能直接公开现有写接口。
