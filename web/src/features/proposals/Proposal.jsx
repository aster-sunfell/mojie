import React, { useEffect, useRef, useState } from 'react'
import { inlineDocument } from './merge.js'
import { InlineText } from './InlineText.jsx'
import { ReviewToolbar } from './ReviewToolbar.jsx'

export const proposalStatusName = status => ({ PENDING: '待处理', PARTIALLY_APPLIED: '部分已保存',
  APPLIED: '处理完成', REJECTED: '已忽略', CONFLICTED: '需要重新核对' }[status] || status)

export function Proposal({ review, draft, busy, onDraftChange }) {
  const { proposal, items, apply, ignore, notice } = review
  const [selected, setSelected] = useState(null)
  const changes = useRef({})
  const editingText = useRef(null)
  useEffect(() => { setSelected(null) }, [proposal?.id])
  if (!proposal) return null
  const document = inlineDocument(proposal.baseContent, draft, items,
    { editingText: editingText.current?.proposalId === proposal.id ? editingText.current : null })
  function navigate(index) {
    const element = changes.current[items[index]?.id]
    if (!element) return
    setSelected(index)
    element.focus({ preventScroll: true })
    // Focus may release a plain-text segment's editing boundary. Scroll after
    // React renders it, keeping the target header below the sticky toolbar.
    requestAnimationFrame(() => {
      const target = changes.current[items[index]?.id]
      const area = target?.closest('.editorArea')
      if (!area) return
      const toolbarHeight = area.querySelector('.reviewToolbar')?.offsetHeight || 0
      area.scrollTo({ top: Math.max(0, area.scrollTop + target.getBoundingClientRect().top
        - area.getBoundingClientRect().top - toolbarHeight - 16), behavior: 'smooth' })
    })
  }
  function editRange(range, text) {
    if (range.type === 'text' && editingText.current?.key === range.key)
      editingText.current = { ...editingText.current, start: range.start, end: range.start + text.length }
    onDraftChange(draft.slice(0, range.start) + text + draft.slice(range.end))
  }
  return <section className="proposalReview" aria-label="正文行内修改">
    <ReviewToolbar items={items} frames={document.frames} selected={selected} busy={busy} review={review} onNavigate={navigate} />
    {notice && <p className="hint" role="status">{notice}</p>}
    {review.error && <p className="error" role="alert">{review.error}</p>}
    {!items.length && proposal.status === 'PENDING' && <button className="secondary" disabled={busy || review.loading} onClick={review.dismiss}>关闭无修改建议</button>}
    <div className="inlineDocument" aria-label="完整正文与修改建议">
      {document.segments.map(segment => {
        if (segment.type === 'text') return <div className="documentText" key={segment.key}>
          <span className="lineNumber" aria-hidden="true">{draft.slice(0, segment.start).split('\n').length}</span>
          <InlineText label="章节正文" value={segment.text} onChange={text => editRange(segment, text)}
            onFocus={() => { editingText.current = { proposalId: proposal.id, key: segment.key, start: segment.start, end: segment.end,
              beforeIds: document.segments.slice(0, document.segments.indexOf(segment))
                .filter(value => value.type === 'change').map(value => value.item.id) } }}
            onBlur={() => { if (editingText.current?.key === segment.key) editingText.current = null }} />
        </div>
        const { item, range, original, current, preview, conflict } = segment
        const index = items.findIndex(value => value.id === item.id)
        const pending = item.status === 'PENDING'
        const applied = item.status === 'APPLIED'
        const status = applied ? item.unsaved ? '已应用到草稿' : `已保存 · 版本 ${item.savedVersion}`
          : !pending ? item.unsaved ? '已忽略 · 未保存' : '已忽略'
            : conflict ? '需要手动确认' : item.preview ? '已调整 · 待应用' : '待处理'
        return <div className={`inlineChange ${selected === index ? 'selectedChange' : ''}`} key={segment.key}
          ref={element => { changes.current[item.id] = element }} tabIndex={-1} onFocus={() => setSelected(index)}>
          <div className="changeHeader">
            <span>修改 {index + 1} · 第 {item.lineNumber} 行</span><span className={`changeState ${conflict ? 'conflictState' : ''}`}>{status}</span>
            <div className="changeActions">
              {pending && <><button disabled={busy || review.loading || conflict} onClick={() => apply([item.id])}>应用</button>
                <button className="secondary" disabled={busy || review.loading} onClick={() => ignore([item.id])}>忽略</button></>}
              {item.status === 'IGNORED' && item.unsaved && <button className="link" disabled={busy || review.loading} onClick={() => review.restore(item.id)}>恢复待处理</button>}
              {item.preview && <button className="link" disabled={busy || review.loading} onClick={() => review.resetSuggestion(item.id)}>重置建议</button>}
            </div>
          </div>
          {(pending || applied) && <div className="diffLine diffBefore" aria-label={`修改 ${index + 1} 前（只读）`}>
            <span className="diffGutter" aria-hidden="true">−</span><pre>{original || '（原无文字）'}</pre>
          </div>}
          {range && (applied || !pending || conflict) && <div className={`diffLine ${applied ? 'diffAfter' : conflict ? 'diffCurrent' : 'diffUnchanged'}`}>
            <span className="diffGutter" aria-hidden="true">{applied ? '+' : '·'}</span>
            <InlineText label={applied ? `修改 ${index + 1} 后的正文` : `修改 ${index + 1} 当前正文`}
              value={current} onChange={text => editRange(range, text)} />
          </div>}
          {pending && <div className="diffLine diffAfter">
            <span className="diffGutter" aria-hidden="true">+</span>
            <InlineText label={`修改 ${index + 1} 建议（应用前可编辑）`} value={preview}
              disabled={busy || review.loading} onChange={text => review.editSuggestion(item.id, text, current)} />
          </div>}
          {conflict && <div className="changeConflict">
            <span>{range ? '此处已有手动修改，保留当前正文。编辑完成后可标记已手动处理。' : '无法唯一定位此处，正文已完整保留在页面中。请在正文中手动处理。'}</span>
            <button className="link" disabled={busy || review.loading} onClick={() => review.markHandled(item.id)}>已手动处理</button>
          </div>}
          {applied && !range && <p className="changeConflict">此处正文已再次修改，处理记录保留。当前文字可在上方正文中继续编辑。</p>}
        </div>
      })}
    </div>
  </section>
}
