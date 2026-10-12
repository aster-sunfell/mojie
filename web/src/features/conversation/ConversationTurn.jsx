import React, { useEffect, useState } from 'react'
import { api, readableError } from '../../shared/api/client.js'
import { Markdown } from '../../shared/ui/Markdown.jsx'
import { RunResult } from '../agent/RunResult.jsx'
import { active } from '../agent/status.js'
import { proposalStatusName } from '../proposals/Proposal.jsx'

const taskNames = { CHAPTER_REVIEW: '审阅本章', CHAPTER_EDIT: '修改本章' }

export function ConversationTurn({ message, run, chapterNames, review, proposalStatuses, onOpenProposal, onSource, onRetry, canRetry, connection }) {
  const [resources, setResources] = useState(null), [resourceError, setResourceError] = useState('')
  const [retryResources, setRetryResources] = useState(0)
  const [settingStatus, setSettingStatus] = useState(null)
  useEffect(() => {
    if (message.role === 'USER' || run?.targetType !== 'SETTING' || !run.proposalId) return
    let disposed = false
    api(`/edit-proposals/${run.proposalId}`).then(value => { if (!disposed) setSettingStatus(value.status) })
      .catch(() => { if (!disposed) setSettingStatus('UNKNOWN') })
    return () => { disposed = true }
  }, [message.role, run?.targetType, run?.proposalId])
  useEffect(() => {
    if (!run || message.role === 'USER' && !['FAILED', 'CANCELLED'].includes(message.status)) return
    let disposed = false
    api(`/agent/runs/${run.id}/resources`).then(value => {
      if (!disposed) { setResources(value); setResourceError('') }
    }).catch(e => { if (!disposed) setResourceError(readableError(e)) })
    return () => { disposed = true }
  }, [message.role, message.status, run?.id, retryResources])
  const user = message.role === 'USER'
  const failed = user && ['FAILED', 'CANCELLED'].includes(message.status)
  const selected = review?.id === run?.proposalId
  const settingProposal = run?.targetType === 'SETTING'
  const title = settingProposal ? `设定 · ${run.targetTitle || '目标设定'}` : run?.targetChapterId ? chapterNames[run.targetChapterId] || run.targetTitle || '目标章节' : ''
  const contextTitle = run?.contextChapterId ? chapterNames[run.contextChapterId] || '参考章节' : ''
  const turnContext = run?.taskType === 'ASSISTANT'
    ? contextTitle ? `参考：${contextTitle} · v${run.contextVersion}` : ''
    : `${taskNames[run?.taskType] || '章节任务'}${title ? ` · ${title} · v${run.baseVersion}` : ''}`
  return <article className={`conversationMessage ${user ? 'user' : 'assistant'}`}>
    <div className="messageMeta"><span>{user ? '你' : '写作助手'}</span>
      {user && run && turnContext && <span>{turnContext}</span>}{user && run?.analysisOnly && <span>仅分析</span>}</div>
    {user && message.skillMentions?.length > 0 && <div className="messageMeta">{message.skillMentions.map(s => <span key={s.skillId}>引用：{s.name}</span>)}</div>}
    {user ? <p className="userContent">{message.content}</p> : <Markdown content={message.content} />}
    {failed && <div className="turnFailure"><span>本轮{message.status === 'FAILED' ? '失败' : '已停止'}，未完成回答。</span>
      {canRetry && <button className="link" onClick={() => onRetry(message)}>重试本轮</button>}
      {run?.errorDetail && <p className="error">{readableError({ code: run.errorCode, message: run.errorDetail })}</p>}</div>}
    {!user && run?.status === 'SUCCEEDED' && run.proposalId && <div className="messageProposal">
      <div><strong>{title} · 修改建议</strong>
        {settingProposal && (proposalStatuses?.[run.proposalId] || settingStatus) && <small>{settingStatus === 'UNKNOWN' && !proposalStatuses?.[run.proposalId] ? '状态暂不可用' : proposalStatusName(proposalStatuses?.[run.proposalId] || settingStatus)}</small>}
        {selected && review.proposal && <small>共 {review.items.length} 处修改 · {review.items.filter(item => item.status === 'PENDING').length} 处待处理 · {proposalStatusName(review.proposal.status)}</small>}</div>
      <button className="secondary" onClick={() => onOpenProposal(run)}>
        {settingProposal ? '在设定中查看' : selected ? '定位正文修改' : '在正文中查看'}</button>
    </div>}
    {!user && run?.sources?.length > 0 && <details className="messageSources"><summary>查阅来源 · {run.sources.length} 条</summary>
      <div className="sources">{run.sources.map(source => <button key={source.id} onClick={() => onSource(source, run)}>
        <span>{source.chapterId ? chapterNames[source.chapterId] || '章节' : '作品设定'}</span>
        <small>{source.chapterId ? `v${source.chapterVersion}` : '设定'}{source.lineNumber ? ` · 第 ${source.lineNumber} 行` : ''}
          {source.providedToModel ? ' · 已供模型查阅' : ' · 未确认送达模型'}</small>
      </button>)}</div>
    </details>}
    {resources?.artifacts?.length > 0 && <div className="messageAttachments">{resources.artifacts.map(a =>
      <a key={a.id} href={`/api/v1/artifacts/${a.id}/download`} download>{a.name}{failed ? ' · 未完成任务产物' : ''} · 到期 {new Date(a.expiresAt).toLocaleDateString()}</a>)}</div>}
    {run && (!user || failed || active(run.status)) && <details className="conversationExecution"><summary>执行详情</summary>
      <RunResult compact task={{ run, connection }} onSource={source => onSource(source, run)} />
      {resources?.skills?.filter(s => s.loaded).map(s => <p key={s.skillId} className="muted">{s.metadata.name} · {s.source === 'EXPLICIT' ? '用户指定' : s.source === 'PINNED' ? '历史固定项' : '助手发现'} · {s.providedToModel ? '已供模型使用' : '已加载，未确认送达'}</p>)}
      {resourceError && <p className="error">附件与 Skill 信息暂不可用：{resourceError}<button className="link" onClick={() => setRetryResources(v => v + 1)}>重试加载</button></p>}
    </details>}
  </article>
}
