# OpenFic 参考与复用清单

固定来源：[OpenFic v0.11.1](https://github.com/syrizelink/OpenFic/commit/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb)，许可证为 [Apache-2.0](https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/LICENSE)。`reference/OpenFic/` 是未跟踪的原样参考副本，不随本项目交付。当前项目代码为 Java/React 重实现；截至此记录，未发现直接复制或逐文件改编的上游源码、图标或素材。本清单中的“改编”指机制和接口设计的改编，并非版权意义上的源码改编。

| 分类 | 上游固定源码 | 本地位置 | 处理 |
| --- | --- | --- | --- |
| 直接复用 | 无 | 无 | 当前无直接复用文件 |
| 机制改编：运行循环 | [react_agent.py](https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/agent_runtime/graph/react_agent.py)、[session_runner.py](https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/agent_runtime/runner/session_runner.py) | `backend/src/main/java/com/mojie/agent/loop/AgentLoop.java`、`agent/runtime/RunScheduler.java` | LangGraph 长期会话收敛为单章串行循环 |
| 机制改编：工具 | [base.py](https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/agent_runtime/tools/base.py)、[read_chapter.py](https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/agent_runtime/tools/impls/chapter/read_chapter.py) | `backend/src/main/java/com/mojie/agent/tool/` | 后端固定权限和快照，重新定义参数与七工具集合 |
| 机制改编：消息与事件 | [persister.py](https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/agent_runtime/persistence/persister.py)、[event_translator.py](https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/agent_runtime/runner/event_translator.py) | `backend/src/main/java/com/mojie/agent/trace/`、`agent/run/infra/` | 保留消息/产品事件分层，改用 PostgreSQL 和 SSE |
| 机制改编：受控编辑 | [edit_chapter.py](https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/agent_runtime/tools/impls/chapter/edit_chapter.py) | `backend/src/main/java/com/mojie/proposal/` | 审批中断改为 run 后整章建议与版本采纳 |
| 机制改编：工作台 | [writing-page.tsx](https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/frontend/src/features/writing/pages/writing-page.tsx)、[use-chapters.ts](https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/frontend/src/features/writing/hooks/use-chapters.ts) | `web/src/App.jsx`、`web/src/api.js` | 精简为纯文本工作台，重写数据和草稿状态 |
| 仅参考：混合检索 | [search_chapters.py](https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/agent_runtime/tools/impls/chapter/search_chapters.py)、[builder.py](https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/retrieval/internal/query/builder.py) | 无 P0 对应实现 | P1 候选；P0 字面搜索独立实现 |
| 仅参考：后台作业 | [supervisor.py](https://github.com/syrizelink/OpenFic/blob/fd62cb4942f8f6a080a8421de1ea121ef9fee7fb/backend/app/background/runtime/supervisor.py) | 无直接移植 | P0 采用不可续跑的单实例调度器 |

逐项文件/符号、差异和测试见 [工程映射](engineering-map.md)。发布时仍须核对最终依赖和素材的各自许可证；上游主仓库许可证不能代替第三方授权。
