# HTTP 与 SSE 契约 v0.1

所有路径以 `/api/v1` 为前缀。成功 JSON 使用 `{ "data": ... }`；删除成功返回 204、无正文。失败 JSON 使用 `{ "code": "...", "message": "...", "requestId": "...", "details": {} }`。客户端应按 HTTP 状态和 `code` 处理错误，不解析服务端堆栈。

任务内模型调用的 HTTP 402 记录为 `FAILED / MODEL_PAYMENT_REQUIRED`，提示检查模型账户余额或付费权限。消息采用后端固定文案，不展示提供方原始响应；不自动重试，也不发布候选稿。任务详情查询本身仍正常返回 HTTP 200。

`GET /system/capabilities` 的 `modelId` 表示服务端当前选用的模型；实测客户端应在提交前核对它。受控的 `GET /agent/runs/{id}/trace` 另返回该任务持久化的 `modelId` 和 `modelConfig`，后者仅包含 `apiPath`、`maxOutputTokens`、`contextWindowTokens`，以及新任务的 `contextWindowSource`、`contextWindowFetchedAt`，不会把其他配置字段或密钥带出。当前服务配置和历史任务配置可能不同，以任务快照核对该次实测。

## 列表分页

`GET /projects`、`GET /projects/{id}/chapters`、`GET /projects/{id}/settings`、`GET /chapters/{id}/revisions` 和 `GET /agent/runs` 的 `data` 为 `{ "items": [...], "nextCursor": string | null }`。`limit` 默认 20、最大 50。传回 `nextCursor` 作为下一次请求的 `cursor`，末页为 `null`；游标绑定当前作者、资源及筛选条件，改条件后从第一页重新请求。无效或篡改游标返回 400 `VALIDATION_ERROR`。游标是已读位置，不是整个列表的时间点快照；翻页期间新增或更改内容可能改变列表。

## 任务事件

`GET /agent/runs/{id}/events?afterSeq=N` 返回 `text/event-stream`。请求头 `Last-Event-ID` 存在时优先于 `afterSeq`。两者均表示已处理的最后序号；服务端只发送当前任务中 `seq > N` 的已提交事件，按序递增。事件可以重放，客户端用 `runId` 和 `seq` 去重。约每 15 秒发送 SSE 注释心跳；终态事件发出后连接结束。

全局最多 20 条活动订阅，超额请求在打开事件流前返回 HTTP 429、`STREAM_CAPACITY_EXCEEDED` JSON 错误；客户端可继续查询任务快照。每条订阅每 500 毫秒检查一次，每批最多 100 条事件。定时调度与查询/发送使用不同的受限执行器，同一连接最多有一个执行中的批次。终态、检测到断线或发送失败时释放订阅名额，重复关闭不重复释放。空闲连接的断线可能直到下一次事件或 15 秒心跳发送时才被检测到。

每条事件的 SSE `id` 是十进制 `seq`，`event` 是类型，`data` 是如下 JSON：

```json
{
  "schemaVersion": 1,
  "runId": "run UUID",
  "seq": 3,
  "type": "model.started",
  "occurredAt": "2026-09-28T18:00:00Z",
  "payload": { "callIndex": 1 }
}
```

类型为 `run.queued`、`run.started`、`model.started`、`model.finished`、`tool.started`、`tool.finished`、`run.succeeded`、`run.failed`、`run.cancelled`。模型事件包含 `callIndex`；工具事件包含 `toolExecutionId`、`toolName`，完成时包含 `status` 和 `returnedToModel`；终态事件包含 `status`，失败时可含 `errorCode`，成功发布建议时可含 `proposalId`。事件只提示客户端刷新 `GET /agent/runs/{id}`；详情快照和数据库中的步骤、来源是展示依据。断线后可用最后处理的序号重连，同时定期查询任务详情作为降级路径；旧快照不得回退已显示的序号和终态。

模型上下文窗口在后台联网解析并持久化缓存。`GET /system/capabilities` 新增 `modelMetadataAvailable`，表示是否具备可用于新任务的窗口信息；`agentConfigured` 继续表示模型凭据已配置且执行器健康。无可用元数据时，新任务受理返回 HTTP 503 / `MODEL_METADATA_UNAVAILABLE`，不创建任务；已有任务的幂等重试仍返回原受理结果，内容编辑和查询继续可用。每次任务固定使用受理时的窗口快照。

当前实例使用单作者数据模型，不接受作者身份参数；历史 owner 字段通过 V8 迁移移除。任务提交键 `clientRequestId` 在整个实例内唯一。

## 连续对话与 Skill 扩展（2026-10-02）

新 Conversation 入口由 [连续对话与 Skill 设计](../design/conversation-skills.md) 承接。CHAT 无目标章，CHAPTER_REVIEW 不提供 propose_edit，CHAPTER_EDIT 保留当前基准全文与作者采纳边界；旧 Run API 保持原预算和独立任务语义。新任务 16 次模型/32 次工具、120000 新增工具码点和 300 秒运行预算，存入配置快照；请求保存历史来源 ID 与完整逻辑输入。终态展示消息和对话活动槽在数据库事务内收尾。

Skill 目录固定于 Run，版本包在文件卷不可变保存，PG 保存状态及引用；Skill 指导不授予权限。新增 context.compacted、skill.loaded、script.started、script.finished、artifact.created 持久化事件，继续使用按 Run 序号恢复的 SSE。文件下载 attachment/nosniff。独立脚本沙箱及协调备份见 [运维说明](../design/conversation-skills-operations.md)。
