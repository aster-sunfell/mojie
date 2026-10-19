import React, { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { inlineDocument } from './merge.js'
import { InlineText } from './InlineText.jsx'
import { ReviewToolbar } from './ReviewToolbar.jsx'
import { ProposalChange } from './ProposalChange.jsx'
import { ProposalHistory } from './ProposalHistory.jsx'
import { preserveReadingPosition } from './reviewMotion.js'
import { useMediaQuery } from '../../shared/useMediaQuery.js'

export const proposalStatusName = status => ({ PENDING: '待处理', PARTIALLY_APPLIED: '部分已保存',
  APPLIED: '处理完成', REJECTED: '已忽略', CONFLICTED: '需要重新核对' }[status] || status)

export function Proposal({ review, draft, busy, onDraftChange }) {
  const { proposal, items, notice } = review
  const [selected, setSelected] = useState(null)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [visibleNotice, setVisibleNotice] = useState(notice)
  const root = useRef(null)
  const changes = useRef({})
  const editingText = useRef(null)
  const reducedMotion = useMediaQuery('(prefers-reduced-motion: reduce)')
  const saved = items.filter(item => item.status !== 'PENDING' && !item.unsaved)
  const savedKey = saved.map(item => `${item.id}:${item.status}`).join('|')
  const previousSaved = useRef({ proposalId: proposal?.id, keys: new Set() })
  useEffect(() => { setSelected(null); setHistoryOpen(false) }, [proposal?.id])
  useEffect(() => {
    setVisibleNotice(notice)
    if (!notice || review.saving || review.saveFailed) return
    const timer = window.setTimeout(() => setVisibleNotice(''), 2200)
    return () => window.clearTimeout(timer)
  }, [notice, review.saving, review.saveFailed])
  useLayoutEffect(() => {
    const keys = new Set(savedKey.split('|').filter(Boolean))
    const newlySaved = previousSaved.current.proposalId === proposal?.id
      && [...keys].some(key => !previousSaved.current.keys.has(key))
    previousSaved.current = { proposalId: proposal?.id, keys }
    if (newlySaved && !reducedMotion) return preserveReadingPosition(root.current)
  }, [proposal?.id, savedKey, reducedMotion])
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
  const lineAt = offset => draft.slice(0, offset).split('\n').length
  return <section ref={root} className="proposalReview" aria-label="正文行内修改">
    <ReviewToolbar key={proposal.id} items={items} frames={document.frames}
      selected={selected} busy={busy} review={review} onNavigate={navigate} />
    <div className="reviewUtility">
      {!!saved.length && <button className="link" aria-haspopup="dialog" onClick={() => setHistoryOpen(true)}>查看修改记录（{saved.length}）</button>}
      {visibleNotice && <div className="reviewFeedback"><p className={review.saveFailed ? 'error' : 'hint'} role="status">{visibleNotice}</p></div>}
    </div>
    {review.saveFailed && <button className="secondary" disabled={review.saving} onClick={review.retrySave}>重试保存修改</button>}
    {review.error && <p className="error" role="alert">{review.error}</p>}
    {!items.length && proposal.status === 'PENDING' && <button className="secondary" disabled={busy || review.loading} onClick={review.dismiss}>关闭无修改建议</button>}
    <div className="inlineDocument" aria-label="完整正文与修改建议">
      {document.segments.map(segment => {
        if (segment.type === 'text') return <div className="documentText" key={segment.key}>
          <InlineText startLine={lineAt(segment.start)}
            hideFirstNumber={segment.start > 0 && segment.text.startsWith('\n')}
            hideLastNumber={segment.end < draft.length && segment.text.endsWith('\n')} label="章节正文" value={segment.text} onChange={text => editRange(segment, text)}
            onFocus={() => { editingText.current = { proposalId: proposal.id, key: segment.key, start: segment.start, end: segment.end,
              beforeIds: document.segments.slice(0, document.segments.indexOf(segment))
                .filter(value => value.type === 'change').map(value => value.item.id) } }}
            onBlur={() => { if (editingText.current?.key === segment.key) editingText.current = null }} />
        </div>
        const index = items.findIndex(value => value.id === segment.item.id)
        return <ProposalChange key={`${proposal.id}:${segment.key}`} segment={segment} index={index} selected={selected === index}
          changeRef={element => { changes.current[segment.item.id] = element }} onSelect={() => setSelected(index)}
          review={review} busy={busy} lineAt={lineAt} onEdit={editRange} emptyChapter={!draft.length} />
      })}
    </div>
    {historyOpen && <ProposalHistory proposal={proposal} items={items} onClose={() => setHistoryOpen(false)} />}
  </section>
}
