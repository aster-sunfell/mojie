import React from 'react'
import { active, statusName } from './status.js'
import { proposalStatusName } from '../proposals/Proposal.jsx'
import { ScrollArea } from '../../shared/ui/ScrollArea.jsx'
import { Markdown } from '../../shared/ui/Markdown.jsx'

export function RunResult({ task, review, onSource }) {
  const { run, connection, refresh } = task
  return <div className="runDetail">
    <div className="sectionTitle"><h3>{statusName(run.status)}</h3></div>
    {run.instruction && <section className="requestMessage">
      <h3>本次要求</h3><ScrollArea as="p" tabIndex={0} aria-label="本次要求">{run.instruction}</ScrollArea>
    </section>}
    <p className="muted">{run.targetChapterId ? `基准版本 ${run.baseVersion} · ` : ''}模型 {run.modelCallCount ?? 0}/{run.budget?.maxModelCalls || 8} · 工具 {run.toolCallCount ?? 0}/{run.budget?.maxToolCalls || 12}</p>
    {active(run.status) && <p className="hint">{connection === 'reconnecting'
      ? '事件连接中断，正在重连并查询状态…'
      : task.cancelRequested ? '正在停止…' : '正在处理，页面关闭后任务继续运行。'}</p>}
    {run.status === 'FAILED' && <p className="error">{run.errorCode}：{run.errorDetail}</p>}
    {run.analysis && <section className="analysis"><h3>最终分析</h3><Markdown content={run.analysis} /></section>}
    {run.status === 'SUCCEEDED' && run.proposalId && <section className="proposal">
      <h3>修改建议 {review?.proposal && <span className="badge">{proposalStatusName(review.proposal.status)}</span>}</h3>
      {review?.proposal && <><Markdown className="proposalReason" content={review.proposal.reason} />
        <p className="muted">共 {review.items.length} 处修改 · {review.items.filter(item => item.status === 'PENDING').length} 处待处理</p>
      </>}
      {review?.loading && <p className="muted">正在加载修改建议…</p>}
      {review?.error && <><p className="error" role="alert">{review.error}</p><button className="secondary" onClick={review.retry}>重试加载建议</button></>}
    </section>}
    <details className="steps">
      <summary>执行记录 · {run.steps?.length || 0} 步</summary>
      <div>{run.steps?.map(step => <details key={step.toolExecutionId}>
        <summary><span>{step.toolName}</span><small>{step.status}{step.errorCode ? ` · ${step.errorCode}` : ''}</small></summary>
        <p>{step.summary}</p><ScrollArea as="pre" tabIndex={0} aria-label={`${step.toolName} 参数`}>{JSON.stringify(step.args, null, 2)}</ScrollArea>
        {step.result && <ScrollArea as="pre" tabIndex={0} aria-label={`${step.toolName} 结果`}>{JSON.stringify(step.result, null, 2)}</ScrollArea>}
      </details>)}</div>
    </details>
    <div className="sectionTitle"><h3>查阅来源</h3><small>{run.sources?.length || 0} 条</small></div>
    <div className="sources">{run.sources?.map(item => <button key={item.id} onClick={() => onSource(item)}>
      <strong>{item.kind === 'SEARCH_SNIPPET' ? '搜索摘录' : item.kind === 'FULL_SETTING' ? '完整设定' : '完整章节'}</strong>
      <small>{item.chapterId ? `版本 ${item.chapterVersion}${item.lineNumber ? ` · 第 ${item.lineNumber} 行` : ''}` : '设定'}</small>
      <small>{item.providedToModel ? '已进入模型请求' : '仅执行，未确认送达模型'}</small>
    </button>)}</div>
  </div>
}
