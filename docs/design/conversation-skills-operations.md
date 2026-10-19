# 连续对话、Skill 部署与恢复

Java 配置 `MOJIE_DATA_DIR` 保存不可变包、staging 和产物；PG 保存状态、清单、引用及请求快照。生产 `mojie_files` 卷由 files-init 初始化 UID 10001 写权限。不要把数据目录发布为静态网站。

Skill 仅加载上下文，不执行脚本。生产无需执行器、Docker socket、Python/Node 运行时镜像或专用执行网络。完整包仍保存在文件卷，脚本仅可预览和导出。能力兼容性按当前策略重新计算，不修改旧包。

备份：执行 `python3 scripts/backup-conversation-skills.py --output /absolute/path/backup-YYYYMMDD`。脚本停止正在运行的 backend；对历史部署也会停止存在的旧执行器；等写进程退出后生成 PG custom dump、版本/产物文件归档和清单，再启动服务。服务恢复时旧活动 Run 明确失败，不能继续运行。备份不包含密钥、历史可再生成的执行暂存；配置与密钥须另行管理。

恢复必须使用独立 Compose project、独立空 PG 与文件卷，不能覆盖运行环境：

1. 启动恢复环境 postgres 和 files-init，保持 backend 停止；历史环境同时停止旧执行器。
2. 将 postgres.dump 经 `docker exec -i <restore-postgres> pg_restore -U mojie -d mojie --exit-on-error` 恢复至空数据库。
3. 使用可信 tar 工具把 files.tar 恢复至 `<restore-project>_mojie_files`。恢复前检查归档来源；运行 `scripts/verify-skill-files.py --manifest ... --data-dir ...` 对路径、大小及 SHA-256 逐项验证。
4. 核对 PG 外键和版本引用，启动恢复环境 backend。启动扫描把缺失/损坏的 READY 包标记 UNAVAILABLE，未完成上传与执行明确失败。
5. 查询旧对话继续一轮；读取固定旧版指导、导出包并下载有效产物，查询请求快照来源。仅确认卷文件仍在不算恢复验收。

服务启动时执行恢复检查和一次文件维护，此后每小时维护。失败 staging 和没有 PG 记录的孤立目录超过 24 小时后清理；UPLOADING/VALIDATING 上传及其待发布版本目录受到保护。检查发现 READY 包损坏时标记 UNAVAILABLE，当前版本停用，不能用其他版本替代。

原始消息、压缩摘要、执行记录及已发布包默认永久保留；逻辑删除不减少历史包占用。此版本采用保守保留策略，已发布包不自动物理回收。提交结果未知须先回读 PG，不能直接删版本目录。需要释放历史包空间时应先设计并验证引用审计与保留策略。

产物默认保留 30 天，API 到期返回 410。维护在 PG 锁定并复查执行输入引用后登记 DELETING，删除文件，再标记 EXPIRED 并释放配额；失败可重试且不重复释放。已被执行输入引用的产物文件继续保留用于审计，即使下载已到期。旧执行输入引用继续保留，不产生新脚本执行或新产物。运维不得直接删除被对话、Run 或执行输入引用的文件。

自动部署清单改为 `deploymentContract: 2`，只包含 backend/web 两个镜像。部署程序仍识别 contract 1 的历史三镜像记录，冻结、备份、恢复旧环境时按其已保存的服务配置处理；新候选环境不再创建执行器。恢复文件校验使用宿主机 Python 检查临时文件副本，不要求额外 Python 运行时镜像。

已有服务器首次升级前，应先更新经过审阅的宿主机 `scripts/` 与 `deploy/compose.prod.yaml`；旧部署程序只接受 contract 1，不能自行接受新清单。升级不会删除旧环境或历史执行记录，旧执行器在冻结时停止。不要直接删除旧工作目录或数据卷。
