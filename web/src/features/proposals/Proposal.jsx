import React, { useEffect, useRef, useState } from 'react'
import { locateChanges, locateReviewedText, reviewReference } from './merge.js'

export const proposalStatusName = status => ({ PENDING: '待处理', PARTIALLY_APPLIED: '部分已保存',
  APPLIED: '处理完成', REJECTED: '已忽略', CONFLICTED: '需要重新核对' }[status] || status)

export function Proposal({ review, draft, busy, onEdit, selection }) {
  const { proposal, items, apply, ignore, notice } = review
  const [selected, setSelected] = useState(0)
  const cards = useRef({})
  useEffect(() => { setSelected(0) }, [proposal?.id])
  useEffect(() => {
    if (!selection) return
    const index = items.findIndex(item => item.id === selection.itemId)
    if (index >= 0) {
      setSelected(index)
      cards.current[selection.itemId]?.scrollIntoView?.({ behavior: 'smooth', block: 'nearest' })
    }
  }, [selection])
  if (!proposal) return null
  const pending = items.filter(item => item.status === 'PENDING')
  const unsaved = items.filter(item => item.unsaved).length
  const reference = reviewReference(proposal.baseContent, items)
  const locations = locateChanges(reference.base, draft, reference.items.filter(item => item.status === 'PENDING'))
  function navigate(index) {
    const next = Math.max(0, Math.min(items.length - 1, index))
    setSelected(next); cards.current[items[next]?.id]?.scrollIntoView?.({ behavior: 'smooth', block: 'nearest' })
  }
  return <section className="proposalReview" aria-label="修改审阅">
    <div className="reviewToolbar">
      <div><h2>修改审阅 <span className="badge">{proposalStatusName(proposal.status)}</span></h2>
        <p>基于版本 {proposal.baseVersion} · 共 {items.length} 处 · 待处理 {pending.length} 处{unsaved ? ` · ${unsaved} 处未保存` : ''}</p></div>
      <div className="row">
        <button disabled={busy || review.loading || !pending.length} onClick={() => apply(pending.map(item => item.id))}>
          {pending.length === items.length ? '应用全部' : '应用剩余'}</button>
        <button className="secondary" disabled={busy || review.loading || !pending.length} onClick={() => ignore(pending.map(item => item.id))}>忽略剩余</button>
        <button className="link" disabled={busy || review.loading} onClick={review.retry}>刷新建议状态</button>
      </div>
      <p className="reviewHint">应用只改变草稿。你可以随时编辑正文，也可以只处理一部分后保存。</p>
      <p className="reviewReason">修改理由：{proposal.reason}</p>
      {items.length > 1 && <div className="reviewNavigation">
        <button className="secondary" disabled={selected === 0} onClick={() => navigate(selected - 1)}>上一处</button>
        <span>{selected + 1} / {items.length}</span>
        <button className="secondary" disabled={selected === items.length - 1} onClick={() => navigate(selected + 1)}>下一处</button>
      </div>}
    </div>
    {notice && <p className="hint" role="status">{notice}</p>}
    {review.error && <p className="error" role="alert">{review.error}</p>}
    {!items.length && <><p className="muted">建议稿与原文一致，无需修改。</p>
      {proposal.status === 'PENDING' && <button className="secondary" disabled={busy || review.loading} onClick={review.dismiss}>关闭无修改建议</button>}</>}
    {items.map((item, index) => {
      const adjusted = reference.items.find(value => value.id === item.id)
      const location = locations.get(item.id)
      const conflict = location?.status === 'CONFLICT'
      const contextBefore = item.contextBefore.split('\n').at(-1)
      const contextAfter = item.contextAfter.split('\n')[0]
      return <article className={`changeCard ${selected === index ? 'selectedChange' : ''}`} key={item.id}
        ref={element => { cards.current[item.id] = element }} onFocus={() => setSelected(index)}>
        <div className="changeHeader"><strong>修改 {index + 1} · 原文第 {item.lineNumber} 行</strong>
          <span className={`changeState ${conflict ? 'conflictState' : ''}`}>
            {item.status === 'APPLIED' ? item.unsaved ? '已应用到草稿' : `已保存 · 版本 ${item.savedVersion}`
              : item.status === 'IGNORED' ? item.unsaved ? '已忽略 · 未保存' : '已忽略'
                : conflict ? '需要手动确认' : location?.status === 'INCLUDED' ? '正文已包含' : '待处理'}</span></div>
        <div className="diffLine diffBefore"><span className="diffLabel">− 修改前</span>
          <p><span className="diffContext">{contextBefore}</span><del>{item.before || '（此处原无文字）'}</del><span className="diffContext">{contextAfter}</span></p></div>
        <div className="diffLine diffAfter"><span className="diffLabel">+ 修改后</span>
          <p><span className="diffContext">{contextBefore}</span><ins>{item.after || '（删除此处文字）'}</ins><span className="diffContext">{contextAfter}</span></p></div>
        {conflict && <div className="changeConflict"><strong>当前位置已修改或无法唯一定位，保留你的正文。</strong>
          {location.current !== null && <p className="currentExcerpt">当前内容：{location.current || '（空）'}</p>}
          <p>切换到正文手动处理后，可点击“已手动处理”，或忽略此处建议。</p></div>}
        <div className="changeActions">
          {item.status === 'PENDING' && <>
            <button disabled={busy || review.loading || conflict} onClick={() => apply([item.id])}>应用此处</button>
            <button className="secondary" disabled={busy || review.loading} onClick={() => ignore([item.id])}>忽略</button>
            {conflict && <button className="secondary" disabled={busy || review.loading} onClick={() => review.markHandled(item.id)}>已手动处理</button>}
          </>}
          {item.status === 'IGNORED' && item.unsaved && <button className="secondary" disabled={busy || review.loading} onClick={() => review.restore(item.id)}>恢复待处理</button>}
          <button className="link" onClick={() => onEdit(locateReviewedText(reference.base, draft, adjusted))}>定位正文并编辑</button>
        </div>
      </article>
    })}
    <details className="reviewFullText"><summary>查看任务原文与完整建议稿</summary>
      <h3>任务基准原文</h3><pre>{proposal.baseContent || '（空章节）'}</pre>
      <h3>完整建议稿</h3><pre>{proposal.proposedContent}</pre>
    </details>
  </section>
}
