# 自动更新与回退操作说明

**状态：2026-10-10 已完成生产接入，真实发布验签、完整更新和提交后恢复验证通过；自动更新定时器已启用。** 设计边界见 [安全更新与回退方案](safe-update-and-rollback.md)，实际结果见 [生产验收记录](../verification/safe-update-and-maintenance-2026-10-10.md#生产接入验收)。本文件适用于 Linux、Docker Engine、Compose v2 和 systemd。

## 上下文 Skill 版本的首次升级

新发布清单使用 `deploymentContract: 2`，包含 backend/web 两个 digest。旧服务器先更新经过审阅的 `scripts/` 与 `deploy/compose.prod.yaml`，再运行更新；自动下载镜像不会更新宿主机部署程序。新程序兼容 contract 1 旧记录，按旧 Compose 停止和恢复执行器，新候选只启动前后端与数据服务。不要覆盖私有配置或重建已有状态目录。

## 用户在维护期间看到什么

已打开的工作台定期检查维护状态，正常连接下约 5 秒显示“墨界正在维护”，停用背景操作、保存快捷键及写入请求；写入被服务器拒绝时会立即触发提示。界面保持挂载，当前草稿不会因为遮罩而被清空；提示用户不要刷新或关闭页面。服务恢复后自动解除遮罩，也可以点击“检查恢复状态”。网络或维护状态查询失败时，界面保持停用。

新打开或刷新的页面由独立 Nginx 入口提供维护页，返回 HTTP 503 和 `Retry-After: 5`。即使应用后端停止，页面仍能显示并检查恢复状态；恢复后自动重新加载工作台。API 在维护期间返回 `MAINTENANCE_MODE` 和 HTTP 503。

维护页源码位于 [web/public/maintenance.html](../../web/public/maintenance.html)，样式和脚本内嵌，不依赖 React 或应用构建资源。Vite 构建会将它原样复制到 `web/dist/maintenance.html`；独立入口直接只读挂载源码文件，因此无需构建前端也能提供维护页。

维护状态保存在宿主机的 `control/maintenance` 文件中，后端只读挂载该目录。没有公开切换维护状态的 HTTP 管理接口。直连内部应用端口的写入也会由后端拦截。健康检查和内部只读状态查询仍可用于排空及验收。

## 首次安装条件

- 安装 Docker、支持 `up --wait` 的 Compose v2、Python 3.9 或更新版本，以及支持 `gh attestation verify` 的 GitHub CLI。
- 将经过审阅的本仓库文件安装到 `/opt/mojie`。它包含固定的部署程序、Compose 和入口资源；自动更新只下载发布清单和镜像，不执行下载的脚本，也不自动更新宿主机部署配置。
- 保留 `/opt/mojie/web/public/maintenance.html`，独立入口通过 `deploy/compose.gateway.yaml` 中的相对路径挂载该文件；仅安装 `deploy/` 和 `scripts/` 不足以启动入口。
- 当前镜像为 `linux/amd64`。确认服务器架构及足够的备份、候选数据和保留版本空间。
- 仓库需要能够使用 GitHub artifact attestations。CI 的发布 job 必须成功生成并上传 `deployment-release`；若仓库套餐或权限不支持 attestations，先解决该能力，不能关闭验签来启用自动更新。
- 生产入口继续放在受保护的 HTTPS 代理之后。当前应用没有完整用户鉴权，默认部署端口仅监听回环地址。

以下安装命令由服务器管理员首次执行。既有生产环境切换前先按 [Skill 备份恢复说明](conversation-skills-operations.md) 备份，并确认稳定的数据库密码、`MOJIE_CURSOR_KEY` 和模型配置。不要用示例配置覆盖现有生产密钥。

## 安装并登记初始环境

创建私有状态目录和可供容器只读访问的维护目录：

```bash
sudo install -d -m 700 /etc/mojie /var/lib/mojie-deploy
sudo install -d -m 755 /var/lib/mojie-deploy/control
sudo install -m 600 /opt/mojie/deploy/deploy-config.example.json /etc/mojie/deploy.json
```

配置 `/etc/mojie/deploy.json` 中的仓库、文件路径、两个应用端口和入口端口。将现有生产环境文件保存在 `/etc/mojie/production.env`，权限设为 `600`；在原配置上补充：

```dotenv
MOJIE_CONTROL_DIR=/var/lib/mojie-deploy/control
MOJIE_HTTP_BIND=127.0.0.1
MOJIE_HTTP_PORT=18088
MOJIE_IMAGE_TAG=包含本次维护支持且CI成功的完整提交SHA
```

`18088` 和 `18089` 是两个内部应用端口，`8088` 留给稳定入口。如果旧 Web 当前占用 `8088`，这一步重建 Nginx 会把它移到 `18088`，有一次首次接入的维护中断。

首次应用部署仍按现有手动流程进行。已有数据库的迁移与备份约束仍适用，不因使用新脚本而自动消失：

```bash
cd /opt/mojie
sudo docker compose --env-file /etc/mojie/production.env -f deploy/compose.prod.yaml -p mojie-prod pull
sudo docker compose --env-file /etc/mojie/production.env -f deploy/compose.prod.yaml -p mojie-prod up -d --no-build --wait --wait-timeout 180
sudo python3 scripts/mojie_deploy.py register --project mojie-prod --port 18088
sudo docker compose --env-file /etc/mojie/production.env -f deploy/compose.gateway.yaml -p mojie-gateway up -d
sudo python3 scripts/mojie_deploy.py recover
```

登记要求已有后端支持维护状态接口、挂载指定维护目录，并且运行中的前后端镜像（旧环境可含执行器）有本仓库对应的 GHCR digest。初始 Compose 项目应使用默认的独立命名卷，不支持导入共享外部卷或共享外部网络。登记保存实际运行镜像和配置，并让写服务由部署程序负责开机恢复。

入口使用 Linux 主机网络访问回环应用端口。SELinux 主机上的维护目录使用共享标签；当前应用不挂载 Docker socket，也不执行 Skill 脚本。

已安装旧路径 `deploy/maintenance.html` 的服务器，需要同步新的 `web/public/maintenance.html` 和 `deploy/compose.gateway.yaml`。确认新文件存在后执行以下命令，重建入口以应用新挂载；重建期间入口会短暂中断。单独发布应用镜像不会完成这次路径迁移。

```bash
cd /opt/mojie
test -f web/public/maintenance.html
sudo docker compose --env-file /etc/mojie/production.env -f deploy/compose.gateway.yaml -p mojie-gateway up -d --force-recreate gateway
```

## 配置只读凭据和定时更新

私有 GHCR 镜像需要为实际执行服务的 root 用户配置镜像读取凭据。CI 继续用仓库的 `GITHUB_TOKEN` 发布，服务器不需要镜像写入权限。

将服务器读取 Actions 和 attestations 所需的 GitHub 凭据存入 `/etc/mojie/github.env`，权限 `600`：

```dotenv
GH_TOKEN=仅用于读取指定仓库发布信息的凭据
```

令牌须能读取该仓库 Actions artifacts 和 attestations，授权范围按仓库类型选择最小可用权限。私有镜像登录凭据单独配置，不能把发布或服务器 SSH 权限加到这个令牌上。

```bash
sudo install -m 644 /opt/mojie/deploy/systemd/mojie-recover.service /etc/systemd/system/
sudo install -m 644 /opt/mojie/deploy/systemd/mojie-update.service /etc/systemd/system/
sudo install -m 644 /opt/mojie/deploy/systemd/mojie-update.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now mojie-recover.service
sudo systemctl enable --now mojie-update.timer
```

`mojie-recover` 在开机时恢复已确认的环境。定时器约每 5 分钟检查一次，只选择 `main` 上成功的镜像工作流，并验证清单的仓库、工作流、分支、源码提交及清单契约要求的镜像 digest。部署锁防止并发执行，失败版本不会被定时器反复部署。

## 部署阶段与失败处理

每次更新都会冻结旧环境，协调备份数据库和文件，在独立项目及数据卷中恢复，启动候选版本执行迁移。文件哈希在恢复后及启动后分别检查，后者允许清理已过期的临时产物；READY Skill 文件及未过期产物仍须完整。检查健康状态及核心用户内容摘要，旧环境额外检查其执行器，再通过稳定入口确认上游及维护页。

提交发布记录之前失败：停止候选环境，启动旧容器和旧数据，验收成功后解除维护。数据库和文件不需要反向迁移。若无法停止候选环境或无法恢复旧环境，保持维护，等待排查。

提交发布记录之后：重启恢复只能继续使用新版本的数据，不能自动接回旧数据库。这个保守边界避免丢失用户在新版本中保存的内容。本版不提供开放写入后自动降级应用的兼容快速路径。

宿主机断电恢复依赖 `mojie-recover.service`。如果部署进程被中断，下次轮询也会先恢复中断阶段。保留环境的服务使用 `restart: "no"`，避免两套写服务随 Docker 同时启动；不要绕过部署程序手动启动旧环境。

当前内容摘要检查限定了自动迁移范围：已有作品名称、章节正文及版本、正文历史、设定、对话标题与消息、请求快照引用必须保持一致。计划改变这些内容格式的迁移需更新并审阅部署契约和验收逻辑，不能忽略检查失败继续发布。

## 状态、恢复与显式重试

```bash
sudo python3 /opt/mojie/scripts/mojie_deploy.py status
sudo journalctl -u mojie-update.service -u mojie-recover.service --since today
sudo python3 /opt/mojie/scripts/mojie_deploy.py recover
```

显式部署或重试某次成功的 Actions run，仍执行验签和完整副本流程：

```bash
sudo bash -c 'set -a; . /etc/mojie/github.env; set +a; python3 /opt/mojie/scripts/mojie_deploy.py deploy --run-id 替换为成功的ActionsRunID --retry'
```

`recover` 恢复本地已记录的阶段；它不是任意历史版本回退指令。重复或倒退的 run number 会被拒绝。

状态记录在 `/var/lib/mojie-deploy/state.json`，每个环境的固定 Compose 配置位于 `releases/`，其中可能包含生产密钥，必须保持私有。共享 `control/` 仅包含维护标记、入口配置和公开维护状态。

旧环境、失败候选和备份目前不自动删除。定期检查空间，只有确认当前项目及回退保留期后才清理非当前环境；不要对当前或待恢复项目执行 `down -v`。同机副本不能代替异地备份。

## 本地验证

部署故障注入测试：

```bash
python3 -m unittest discover -s scripts -p test_mojie_deploy.py -v
```

维护浏览器回归需要专用可丢弃数据库，沿用 README 的浏览器测试环境，并给测试后端与测试进程传入同一个独立标记路径：

```bash
MOJIE_MAINTENANCE_FILE=/tmp/mojie-maintenance-browser-marker \
MOJIE_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/替换为专用测试数据库 \
MOJIE_TEST_DB_USER=mojie MOJIE_TEST_DB_PASSWORD=替换为测试密码 \
npm --prefix web run test:e2e -- maintenance.spec.js
```

真实容器演练入口为 [check-safe-deployment.py](../../scripts/check-safe-deployment.py)，需要本地旧版、新版及故意迁移失败的后端夹具镜像。它只创建临时 Compose 项目和独立数据卷，检查真实迁移失败、切换后失败、成功迁移及保留新写入的恢复；不会验证 GitHub attestations，也不会连接生产环境。验证证据见 [实施验证记录](../verification/safe-update-and-maintenance-2026-10-10.md)。
