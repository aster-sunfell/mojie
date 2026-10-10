# 单作者、YAML 配置与模型元数据验证

日期：2026-10-10。使用 Java 21、独立 PostgreSQL 18.1 测试容器和本地模型模拟服务；未执行真实模型生成请求，未迁移实际开发或生产数据库。

## 实现行为

- 删除部署作者身份和跨 Controller、Service、Repository 的 owner 参数。V6 保留内容及任务历史，删除 owner 字段，将任务提交键改为实例范围唯一；发现历史重复键时报告冲突并回滚。
- 应用配置迁移到 `backend/src/main/resources/application.yaml`，模型配置由 Spring 绑定；保留旧 Anthropic 凭据配置兼容行为。
- 优先读取供应商 `/v1/models` 的明确总上下文字段，否则按已识别的供应商和完整模型 ID 查询 models.dev。中国与全球服务、`Pro/` 模型分别匹配；未知服务不会猜测目录条目。
- V7 持久化窗口、来源和获取时间。默认 5 秒请求超时、24 小时刷新、最多使用 7 天内缓存；失败不延长缓存寿命。公共目录请求无模型凭据，HTTP 重定向不自动跟随。
- 无可用元数据时内容编辑和健康检查继续可用，新任务返回 HTTP 503 / `MODEL_METADATA_UNAVAILABLE`。前端保留要求并检查恢复。任务快照不随缓存刷新变化，历史任务的幂等重试不依赖当前元数据。
- 清理了本地 `deploy/.env` 中的旧身份，将原示例窗口 `163840` 改成注释，以启用自动解析。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| Maven 单元、配置、HTTP 元数据及架构测试 | 69 通过，0 跳过 |
| PostgreSQL、真实 Spring 应用及模型适配器集成测试 | 49 通过，0 跳过 |
| 前端 Vitest | 58 通过 |
| Playwright，真实 Java API + 本地模拟模型 | 33 通过 |
| 前端生产构建 | 通过 |
| `git diff --check` | 通过 |
| 公共模型目录联网检查 | Python 与 Java HttpClient 均收到 HTTP 200 |

后端与端到端测试使用以下独立测试数据库配置：

```bash
export JAVA_HOME=/home/dream/.sdkman/candidates/java/21.0.12+1.1-tem
export PATH="$JAVA_HOME/bin:$PATH"
export MOJIE_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:55439/mojie_change_test
export MOJIE_TEST_DB_USER=mojie_test
export MOJIE_TEST_DB_PASSWORD=mojie_test_only
MOJIE_TEST_LOCAL_HTTP=1 mvn -B -ntp -f backend/pom.xml verify
npm --prefix web test
npm --prefix web run build
npm --prefix web run test:e2e
```

新增覆盖包括：YAML 默认值及环境变量覆盖、非法窗口配置、目录精确匹配、凭据隔离、HTTP 超时及重定向、缓存刷新和过期、保存失败、应用从元数据不可用状态恢复、任务快照、重启复用缓存、历史多 owner 数据保留及重复提交键导致的迁移回滚。

## 实际限制与升级

联网检查时，models.dev 的 `siliconflow-cn / deepseek-ai/DeepSeek-V3.2` 返回 `limit.context=164000`。仓库此前根据供应商列表使用的显式值为 `163840`。目录数据是参考元数据，不能保证与供应商实际限制完全相同；可选窗口覆盖仍可指定供应商准确值。测试不构成真实供应商多轮模型任务的验证。

升级时先停止旧后端，再启动新版本执行 V6/V7。所有旧 owner 的资源会统一可见，旧分页游标因范围变化应重新获取。旧版本后端无法使用删除 owner 字段后的数据库结构。临时测试容器和测试卷在验证后清理。
