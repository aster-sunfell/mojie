# Skill 执行器

主服务通过内网 HTTP 提交固定包及输入。可信协调器仅构造固定 Docker 参数，上传代码在单独的 Python / Node 容器执行。脚本容器无网络、只读根及包、UID 10001、能力清空、禁止新增权限、1 CPU / 512 MiB / 64 PID、30 秒和输出限额；不挂载 Docker socket、数据库、模型密钥或其他执行目录。

协调器需要 Docker CLI 和访问 Docker daemon；它不是上传代码的执行环境。生产仅放在 Compose 的 `execution` 内网，不能公开端口。协调器工作路径在容器内与 Docker 主机保持一致，供 daemon 创建限于单次执行的挂载。

首次在 Docker 主机预先拉取 `deploy/.env.example` 中两个摘要固定的运行时镜像。默认镜像实测解释器为 Python 3.11.15 和 Node.js 22.23.3。协调器使用 `--pull=never`，上传不会安装依赖。支持 Python/Node 标准库，其他依赖须由运营制作镜像并固定摘要；更换镜像时同步公布实际解释器及依赖。每次执行保存实际镜像摘要，工具返回该执行环境。

本地已有 Docker CLI 与 Python 3 时，设置 `MOJIE_PYTHON_IMAGE`、`MOJIE_NODE_IMAGE`，执行 `python3 executor/server.py`，Java 配置 `MOJIE_SKILL_EXECUTOR_URL=http://127.0.0.1:8091` 后重启。进程退出及启动恢复都会终止遗留容器。

`python3 -m unittest discover -s executor -p test_executor.py -v` 在真实 Docker 中验证两个解释器、输入与产物、完整资源目录、错误分类、超时、输出及产物数量超限、包只读、无网络、链接拒绝、取消子进程、轮询失联及重启实例隔离。测试使用固定镜像摘要，不访问生产数据库或模型。
