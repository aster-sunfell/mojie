# 设定修改建议验证

实现依据：[章节与设定修改建议](../design/setting-edit-proposals.md)。

## 已验证行为

- 章节工具改名为 propose_chapter_edit，新增 propose_setting_edit，无旧工具别名。
- 本轮完整读取设定且结果进入后续模型请求后才能提交候选；同批次 read/propose 返回可恢复的上下文不足错误。
- 候选基准取自服务端读取缓存，生成建议不写正式设定，每轮最多一份章节或设定建议。
- 设定建议可以应用类型、标题、内容，调整草稿后保存；不切换章节，不清空章节未保存草稿。
- 手动和助手保存递增设定版本并写不可变历史；作者调整内容记录在 AGENT 修订中。
- 旧版本建议不能覆盖新设定，重复保存不会重复创建修订，已处理建议刷新后展示真实状态。
- “仅分析”在后端禁用两种修改建议工具。
- taskType=CHAT 请求与数据库写入均拒绝；旧 CHAT 记录迁移为 ASSISTANT，原对话消息保留；现有设定回填 v1 历史。

## 检查结果

| 检查 | 结果 |
| --- | --- |
| Java 单元及架构测试 | 83 通过 |
| Java 集成测试 | 45 通过，13 未启用 |
| 前端 Vitest | 80 通过 |
| Playwright 完整检查 | 48 通过 |
| 建议卡片状态更新后的定向复验 | 设定浏览器测试 3 通过；前端 80 通过 |
| 前端生产构建 | 通过 |
| OpenAPI YAML 解析、git diff --check | 通过 |

未启用的 13 项 Java 集成测试依赖真实供应商模型或外部 Skill 执行器；本次未调用真实供应商模型。浏览器测试运行真实 Java API、独立 PostgreSQL 18.1 数据库和确定性本地模型服务。桌面 1280×900、手机 390×640 的审阅截图已人工查看，保存按钮与操作区位于视口内，正文与设定草稿保持独立。

本次未部署，未修改实际开发数据库。测试数据库容器及本次测试上传的 Skill 包已清理。

## 复现

使用 Java 21，设置 MOJIE_TEST_JDBC_URL、MOJIE_TEST_DB_USER、MOJIE_TEST_DB_PASSWORD 指向专用可丢弃数据库，再运行：

```bash
mvn -f backend/pom.xml verify
npm --prefix web test
npm --prefix web run build
npm --prefix web run test:e2e
npm --prefix web run test:e2e -- setting-proposals.spec.js
```

服务启动时自动执行 V12 迁移。手动更新设定的客户端现在必须提交 expectedVersion；旧 CHAT 客户端应改为统一 ASSISTANT 请求或省略 taskType。
