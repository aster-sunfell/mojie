import React, { useEffect, useState } from 'react'
import { api, readableError } from '../../shared/api/client.js'
import { Markdown } from '../../shared/ui/Markdown.jsx'
import { ConversationActivity } from './ConversationActivity.jsx'
import { proposalStatusName } from '../proposals/Proposal.jsx'

const taskNames = { CHAPTER_REVIEW: '审阅本章', CHAPTER_EDIT: '修改本章' }

export function ConversationTurn({ message, reply, run, chapterNames, review, proposalStatuses, onOpenProposal, onSource, onRetry, canRetry, connection, cancelRequested }) {
  const [resources, setResources] = useState(null), [resourceError, setResourceError] = useState('')
  const [retryResources, setRetryResources] = useState(0)
  const resourceVersion = run?.steps?.map(step => `${step.toolExecutionId}:${step.status}`).join(',')
  useEffect(() => { setResources(null); setResourceError('') }, [run?.id])
  useEffect(() => {
    if (!run) return
    let disposed = false
    api(`/agent/runs/${run.id}/resources`).then(value => {
      if (!disposed) { setResources(value); setResourceError('') }
    }).catch(e => { if (!disposed) setResourceError(readableError(e)) })
    return () => { disposed = true }
  }, [run?.id, run?.status, resourceVersion, retryResources])
  const user = message.role === 'USER'
  const assistant = reply || (!user ? message : null)
  const status = run?.status || message.status
  const failed = ['FAILED', 'CANCELLED'].includes(status)
  const answer = assistant?.content || (status === 'SUCCEEDED' ? run?.analysis : '')
  const proposals = run?.proposals?.length ? run.proposals : run?.proposalId ? [run] : []
  const settingProposal = run?.targetType === 'SETTING'
  const title = settingProposal ? `设定 · ${run.targetTitle || '目标设定'}` : run?.targetChapterId ? chapterNames[run.targetChapterId] || run.targetTitle || '目标章节' : ''
  const contextTitle = run?.contextChapterId ? chapterNames[run.contextChapterId] || '参考章节' : ''
  const turnContext = run?.taskType === 'ASSISTANT'
    ? contextTitle ? `参考：${contextTitle} · v${run.contextVersion}` : ''
    : `${taskNames[run?.taskType] || '章节任务'}${title ? ` · ${title} · v${run.baseVersion}` : ''}`
  return <>
    {user && <article className="conversationMessage user">
      <div className="messageMeta"><span>你</span>{run && turnContext && <span>{turnContext}</span>}</div>
      {message.skillMentions?.length > 0 && <div className="messageMeta">{message.skillMentions.map(s => <span key={s.skillId}>引用：{s.name}</span>)}</div>}
      <p className="userContent">{message.content}</p>
    </article>}
    {(run || assistant || user && message.runId) && <article className="conversationMessage assistant" data-run-id={run?.id || message.runId}>
    <div className="messageMeta"><span>写作助手</span></div>
    <ConversationActivity run={run} chapterNames={chapterNames} resources={resources} connection={connection} cancelRequested={cancelRequested} />
    {!run && !assistant && <p className="muted">正在恢复助手回复…</p>}
    {answer && <Markdown className="conversationMarkdown assistantAnswer" content={answer} />}
    {failed && <div className="turnFailure" role="status"><span>本轮{status === 'FAILED' ? '失败' : '已停止'}，未完成回答。</span>
      {canRetry && <button className="link" onClick={() => onRetry(message)}>重试本轮</button>}
      {run?.errorDetail && <p className="error">{readableError({ code: run.errorCode, message: run.errorDetail })}</p>}</div>}
    {run?.status === 'SUCCEEDED' && proposals.map(proposal => <ProposalCard key={proposal.proposalId}
      proposal={{ ...run, ...proposal }} chapterNames={chapterNames} review={review}
      statusOverride={proposalStatuses?.[proposal.proposalId]} onOpenProposal={onOpenProposal} />)}
    {run?.sources?.length > 0 && <details className="messageSources"><summary>查阅来源 · {run.sources.length} 条</summary>
      <div className="sources">{run.sources.map(source => <button key={source.id} onClick={() => onSource(source, run)}>
        <span>{source.chapterId ? chapterNames[source.chapterId] || '章节' : '作品设定'}</span>
        <small>{source.chapterId ? `v${source.chapterVersion}` : '设定'}{source.lineNumber ? ` · 第 ${source.lineNumber} 行` : ''}
          {source.providedToModel ? ' · 已供模型查阅' : ' · 未确认送达模型'}</small>
      </button>)}</div>
    </details>}
    {resources?.artifacts?.length > 0 && <div className="messageAttachments">{resources.artifacts.map(a =>
      <a key={a.id} href={`/api/v1/artifacts/${a.id}/download`} download>{a.name}{failed ? ' · 未完成任务产物' : ''} · 到期 {new Date(a.expiresAt).toLocaleDateString()}</a>)}</div>}
    {resourceError && <p className="error messageResourceError">附件与 Skill 信息暂不可用：{resourceError}<button className="link" onClick={() => setRetryResources(v => v + 1)}>重试加载</button></p>}
    </article>}
  </>
}

function ProposalCard({ proposal, chapterNames, review, statusOverride, onOpenProposal }) {
  const [status, setStatus] = useState(proposal.proposalStatus || null)
  useEffect(() => {
    let disposed = false
    api(`/edit-proposals/${proposal.proposalId}`).then(value => { if (!disposed) setStatus(value.status) })
      .catch(() => { if (!disposed) setStatus('UNKNOWN') })
    return () => { disposed = true }
  }, [proposal.proposalId])
  const creating = proposal.operation === 'CREATE'
  const setting = proposal.targetType === 'SETTING'
  const selected = review?.id === proposal.proposalId
  const title = setting ? `设定 · ${proposal.targetTitle || '目标设定'}`
    : chapterNames[proposal.targetChapterId] || proposal.targetTitle || '目标章节'
  const state = statusOverride || (selected ? review.proposal?.status : null) || status
  return <div className="messageProposal">
    <div><strong>{title} · {creating ? setting ? '新建设定建议' : '新建章节建议' : '修改建议'}</strong>
      {state && <small>{state === 'UNKNOWN' ? '状态暂不可用' : proposalStatusName(state)}</small>}
      {selected && review.proposal && <small>共 {review.items.length} 处修改 · {review.items.filter(item => item.status === 'PENDING').length} 处待处理</small>}
    </div>
    <button className="secondary" onClick={() => onOpenProposal(proposal)}>
      {setting ? '在设定中查看' : creating ? '审阅新章节' : selected ? '定位正文修改' : '在正文中查看'}</button>
  </div>
}
