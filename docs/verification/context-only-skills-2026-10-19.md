# 上下文 Skill 本地验证

日期：2026-10-19。基于 `main` 的 `d286411` 实施。当前能力见 [设计](../design/context-only-skills.md)，用户说明见 [创建指南](../user-guide/skills.md)。

## 本地数据库

- `git pull --ff-only origin main` 从 `36c20b2` 快进到 `d286411`，开始时工作区干净。
- 启动已有 `deploy-postgres-1`，沿用 `deploy_mojie_pgdata`，不重建数据卷。
- 本地 `mojie` 原为 V5、无活动任务；迁移前通过 PostgreSQL custom dump 备份。
- 备份保存在不入库的 `.mojie-data/backups/mojie-before-skill-context-20261019.dump`，144341 字节，SHA-256：`cd5943d99c0753a7038d42d62838ab00a8ecdf6bb9b7a5b092d7e51b3c1e9fd0`。
- 使用当前仓库迁移文件和 Flyway 单独执行 V6–V15，不启动业务任务或调用真实模型。15 个迁移校验通过，无失败迁移。
- 迁移前后均为 11 个作品、22 个章节、13 条设定。最终无活动任务。
- 本次 Skill 收敛无需新增数据库迁移：历史包保持不可变，返回兼容性时按当前策略重算；历史执行/产物表继续保留。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| 后端 `mvn verify`，独立 `mojie_skill_context_test` 数据库及本地模拟 HTTP | 100 个单元测试、69 个集成测试通过，无跳过 |
| 前端 `npm test` | 97 个测试通过 |
| 部署 `python3 -m unittest discover -s scripts -p test_mojie_deploy.py` | 15 个测试通过 |
| `playwright.ui.config.js skills.spec.js` | 桌面/手机共 6 个浏览器测试通过 |
| 真实本地后端 `playwright.config.js conversation.spec.js` | 2 个浏览器场景通过；扩展完整读取链路后再次单独通过上下文 Skill 场景 |
| 前端 `npm run build` | 通过，共用指南随应用打包 |
| 本地 `docker build -f web/Dockerfile -t mojie-skill-context-web:local .` | 通过，确认 Docker context 包含唯一指南来源 |
| 生产 Compose 配置及工作流/API YAML 解析、`git diff --check` | 通过 |
| Docker 中实际文件恢复校验 | 未启动容器的只读挂载可复制并通过清单验证；损坏包被拒绝，临时副本清理完成 |

完整浏览器读取场景导入指南中的示例，并附带脚本和参考文本。后端返回 CONTEXT_ONLY、scriptExecution=false 及脚本限制提示。模型依次调用 read_skill、read_skill_file、read_settings、read_chapter，指导实际进入后续请求，任务成功、没有产物，正式章节保持原文。使用确定性本地模型验证工具链路，不代表真实模型写作质量验收。

指南浏览器场景验证桌面/手机可读、不横向溢出，下载的 SKILL.md 与指南模板一致，返回后保留搜索、添加区和未保存编辑。单元测试覆盖复制、包限制提示和无额外确认。

部署回归覆盖两镜像 contract 2 清单组装、旧 contract 1 三镜像记录兼容、新候选移除执行器及网络、仅停止实际存在的写服务，以及原有失败回退/提交后恢复约束。

## 范围

仅完成本地源码、迁移和上述验证；未调用真实模型，未执行生产发布或 GitHub 工作流。生产首次升级需要先同步经审阅的宿主机部署脚本和 Compose；旧部署程序不能识别 contract 2。操作要求已记录到 [部署说明](../design/safe-update-operations.md#上下文-skill-版本的首次升级)。
