# 章节与设定建议

日期：2026-10-12。统一助手支持新建和修改章节／设定，每轮最多 8 份建议；正式内容在作者审阅保存后生效。

## 工具契约

保留 `propose_chapter_edit` 与 `propose_setting_edit`，通过 `operation: CREATE | UPDATE` 区分操作。旧调用未传 operation 时按 UPDATE 处理；绝不因为 ID 不存在而自动新建。

CREATE 不传已有目标 ID 和 expectedVersion，不要求读取不存在的目标。章节提交 proposedTitle、proposedContent、reason；设定另外提交 proposedType（CHARACTER/WORLD/RULE）。标题上限为章节 120、设定 80 码点，正文上限为章节 6000、设定 2000 码点，reason 最多 2000 码点。工具只记录候选草稿，不创建正式对象。

UPDATE 必须先在本轮完整 read_chapter/read_settings，并在后续模型请求中消化读取结果。提交真实目标 ID 和 expectedVersion；基准来自服务端已读缓存，版本不符拒绝。章节 UPDATE 不支持改标题；设定 UPDATE 支持类型、标题和内容调整。只分析的请求不生成建议。

工具 schema 使用扁平 properties/required，CREATE/UPDATE 条件由服务端检查，不依赖部分供应商会误解析的 oneOf。

两种目标共享每轮 8 份候选上限。同一已有目标只能提出一份建议；同类新建目标按标题去重。修改设定不自动修改章节。历史读取不代替本轮修改证据。

只有作者提出创作、新建或修改请求才生成建议；记住背景、简短确认及讨论不应触发自动新建。初始设定默认采用约 200–350 字的结构化草稿，优先覆盖全部请求项目，作者要求详细内容时可扩展。任务和写作约束归为 RULE，世界观归为 WORLD；只生成请求中的项目，独立新建可同批提交。新建设定只读相关材料，避免无关章节目录读取。

## 持久化与保存

V15 移除 run_id 的单建议唯一约束，增加每轮 proposal_index（1–8，唯一）以保持生成顺序。旧数据 operation 默认 UPDATE。CREATE 的基准版本为 0、基准内容为空，已有目标及基准类型／标题为空；保存后通过 createdChapterId/createdSettingId 记录实际对象，保持原 UPDATE 目标外键和版本语义。

运行成功时在一个事务里发布全部建议。失败或取消不发布候选稿，不修改正式内容。完成事务检查最终回复、工具成功记录，以及每份 UPDATE 的完整目标读取确实进入最终模型请求。

作者确认 CREATE 时，同一事务锁作品与建议，校验建议类型及状态，分配正式 ID，写入完整 v1 内容和关联建议的 AGENT 历史，再标记 APPLIED。新章节顺序在保存时分配，默认追加；不会先创建空章再写成 v2。重复保存／并发请求返回同一个已创建目标，不产生额外对象或修订。

UPDATE 保存保留乐观版本控制和 CONFLICTED 状态，绝不覆盖新版本。章节行内差异审阅中，应用、忽略及标记手动处理立即提交当前正文与处理状态，无需再次保存；失败保留草稿与处理选择，提供重试。普通手动修改仍需保存正文。设定通过 PUT /edit-proposals/{id}/setting 保存审阅稿。CREATE 设定请求省略 expectedVersion；UPDATE 必填。新章节通过 PUT /edit-proposals/{id}/chapter 保存 title/content。两个新建保存结果返回实际 targetSettingId/targetChapterId。

## 查询与界面

运行详情新增 proposals 数组，每项包含 proposalId、operation、targetType、目标／创建对象 ID、targetTitle、baseVersion 和 proposalStatus。兼容字段 proposalId 仍指第一份建议；运行分页始终一行一个任务，章节筛选匹配任意建议目标。run.succeeded 事件增加 proposalIds，保留 proposalId。

对话展示全部建议卡片，各自可审阅、保存或忽略；刷新恢复每份状态。新建设定直接进入类型／标题／正文草稿，不读取空目标 ID，不进行基准冲突判定。新章节使用独立草稿审阅窗口；保存后刷新章节目录并定位，导航仍保护原章节未保存草稿。UPDATE 继续展示原内容差异。关闭、取消、切换及页面离开保护未保存编辑。

## 失败收敛与预算

工具错误明确区分未读取、资源不存在、版本冲突和参数错误，并给出 CREATE/UPDATE 的修正方向。同一工具与错误码连续未解决达到两次后，停止本轮继续执行工具，下一模型请求仅允许直接回答并说明未完成部分。成功的完整章节／设定读取允许纠正依赖错误；换虚构 ID 或重新列目录不能清除该错误。单次搜索失败仍允许读取原文等替代路径。

模型或工具预算接近上限，或剩余时间不足 90 秒时，下一请求转为简短最终回复（不提供工具，最多 2048 输出 tokens）。普通工具请求预留 60 秒最终回复时间。供应商超时、取消和最终模型仍坚持调用工具均明确终止，不伪造成功回复。原单请求上限 180 秒、统一运行上限 300 秒保持不变。

Chat Completions 无工具请求显式发送 tool_choice=none，收尾指令同时出现在系统约束和末尾消息。供应商把原生 DSML 工具标记作为最终正文返回时，报 INVALID_MODEL_RESPONSE，不作为成功分析发布。
