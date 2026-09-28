# 墨界 Mojie

AI 小说写作工作台。管理作品、章节和人物 / 世界观设定，让 Agent 按需查阅前文、检查设定一致性并提出修改建议，由作者确认后保存。

## 当前实现

`backend/` 已加入 Java 21 Agent loop 核心、七个工具、PostgreSQL 迁移、JDBC 持久化和有界任务调度。测试和接入边界见 [backend/README.md](backend/README.md)。内容管理接口、真实模型、HTTP/SSE 和前端尚未接入。

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

实施路线是基于固定上游源码裁剪重实现：Java 后端独立运行，React 前端选择性迁移。上游参考代码不代表本项目功能已经完成。
