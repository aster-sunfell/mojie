# 统一写作助手验证

日期：2026-10-10。实现依据：[统一写作助手设计方案](../design/unified-writing-assistant.md)。

## 实现结果

- 工作区改为左侧导航与中间写作区两列；连续对话、输入框位于正文下方，删除旧任务面板、独立输入组件及模式 tab。
- 首次发送自动建立持久化对话；切换对话保留各自草稿，刷新恢复未发送文字；支持讨论、审阅本章、修改本章及 Skill 选择、固定与管理。
- 助手支持消息折叠、阅读位置恢复、拖动和键盘调节高度；正文与消息独立滚动，手机端可扩大对话，较矮窗口和软键盘布局优先保持输入可见。
- 本轮章节和版本固定在任务中；保存失败或保存期间新增正文不发送任务；切章不会取消任务或重建对话。
- 修改建议由消息中的明确入口打开，绑定对应 runId / proposalId；后续聊天不覆盖正文审阅，跨章节查看建议沿用草稿保护；应用后保存才正式生效。
- 停止、终态恢复、断线重连、延迟快照和提交重试均保留真实执行状态。运行时可准备下一轮输入，不能重复提交。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| Vitest | 66 通过 |
| Playwright 完整端到端检查 | 37 通过，0 跳过 |
| Playwright 界面检查（含新增高度与阅读位置检查） | 28 通过 |
| 最终针对性检查 | 连续聊天与 Skill、桌面/手机布局、折叠与高度 4 通过；停止终态检查单独复验通过 |
| 前端生产构建 | 通过 |
| git diff --check | 通过 |

浏览器检查包括 1280px 桌面、390px 手机及 500px 高度窗口；截图经人工查看。端到端测试使用真实 Java 21 API、独立 PostgreSQL 18.1 测试容器和确定性的本地模型服务。真实供应商模型未用于本次验证。

最终停止检查遇到“任务在请求后立即进入 CANCELLED，测试错过短暂的正在停止按钮”的情况。已改为检查取消接口 HTTP 200、界面已停止、后端 CANCELLED 且没有修改建议；持续运行时停止按钮禁用的行为由状态可控的单元和界面测试覆盖。

## 复现

```bash
npm --prefix web test
npm --prefix web run build
npm --prefix web run test:ui

# Java 21 环境，必须使用专用可丢弃数据库。
export MOJIE_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:55440/mojie_assistant_test
export MOJIE_TEST_DB_USER=mojie_test
export MOJIE_TEST_DB_PASSWORD=mojie_test_only
export MOJIE_MAINTENANCE_FILE=/tmp/mojie-assistant-test-maintenance.flag
npm --prefix web run test:e2e
```

测试数据库和测试上传包已清理；本次未改变实际开发数据库或远程部署。
