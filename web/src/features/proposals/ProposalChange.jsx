import React, { useLayoutEffect, useRef, useState } from 'react'
import { InlineText } from './InlineText.jsx'
import { useMediaQuery } from '../../shared/useMediaQuery.js'

export function ProposalChange({ segment, index, selected, changeRef, onSelect, review, busy, lineAt, onEdit, emptyChapter }) {
  const { item, range, original, current, preview, conflict } = segment
  const pending = item.status === 'PENDING'
  const applied = item.status === 'APPLIED'
  const confirmed = !pending && !item.unsaved
  const reducedMotion = useMediaQuery('(prefers-reduced-motion: reduce)')
  const previous = useRef(confirmed)
  const [phase, setPhase] = useState(confirmed ? 'plain' : 'review')
  const root = useRef(null)
  useLayoutEffect(() => {
    const newlyConfirmed = confirmed && !previous.current
    previous.current = confirmed
    if (!confirmed) { setPhase('review'); return }
    if (reducedMotion) { setPhase('plain'); return }
    if (!newlyConfirmed) { setPhase('plain'); return }
    setPhase('collapsing')
    const collapse = window.setTimeout(() => setPhase(applied ? 'highlight' : 'plain'), 200)
    const finish = window.setTimeout(() => setPhase('plain'), 1200)
    return () => { window.clearTimeout(collapse); window.clearTimeout(finish) }
  }, [confirmed, applied, reducedMotion])
  useLayoutEffect(() => {
    if (!confirmed) return
    // A keyboard action may have focused a control that is about to disappear.
    // Move focus into the same editable body, never to a different change.
    const focused = root.current.ownerDocument.activeElement
    if (root.current.querySelector('.reviewChrome')?.contains(focused))
      root.current.querySelector('textarea')?.focus({ preventScroll: true })
  }, [confirmed])
  const plain = confirmed && phase !== 'review' && phase !== 'collapsing'
  const status = item.unsaved ? review.saveFailed ? '保存失败' : '正在保存'
    : applied ? `已应用 · 版本 ${item.savedVersion}` : !pending ? '已忽略'
      : conflict ? '需要手动确认' : item.preview ? '已调整 · 待应用' : '待处理'
  const bodyClass = plain ? 'documentText' : `diffLine ${applied ? 'diffAfter' : conflict ? 'diffCurrent' : 'diffUnchanged'}`
  return <div ref={element => { root.current = element; changeRef(element) }} data-change-id={item.id} data-phase={phase}
    className={`${plain ? `documentText processedChange ${phase === 'highlight' && applied ? 'appliedHighlight' : ''}`
      : `inlineChange ${selected ? 'selectedChange' : ''} ${phase === 'collapsing' ? `settlingChange ${applied ? 'appliedChange' : ''}` : ''}`}
      ${confirmed && !current && !emptyChapter ? 'emptyChange' : ''}`}
    tabIndex={plain ? undefined : -1} onFocus={plain ? undefined : onSelect}>
    <div className="reviewChrome" hidden={plain} inert={confirmed ? true : undefined}>
      {!plain && <div className="reviewChromeContent">
        <div className="changeHeader">
          <span>修改 {index + 1} · 原文第 {item.lineNumber} 行</span><span className={`changeState ${conflict ? 'conflictState' : ''}`}>{status}</span>
          <div className="changeActions">
            {pending && <><button disabled={busy || review.loading || conflict} onClick={() => review.apply([item.id])}>应用</button>
              <button className="secondary" disabled={busy || review.loading} onClick={() => review.ignore([item.id])}>忽略</button></>}
            {item.preview && <button className="link" disabled={busy || review.loading} onClick={() => review.resetSuggestion(item.id)}>重置建议</button>}
          </div>
        </div>
        {(pending || applied) && <div className="diffLine diffBefore" aria-label={`修改 ${index + 1} 前（只读）`}>
          <span className="diffGutter" aria-hidden="true">−</span>
          <InlineText startLine={item.lineNumber} readOnly value={original || '（原无文字）'} />
        </div>}
      </div>}
    </div>
    {range && (applied || !pending || conflict) && <div className={`changeBody ${bodyClass}`} hidden={confirmed && !current && !emptyChapter}>
      <span className="diffGutter" aria-hidden="true" hidden={plain}>{applied ? '+' : '·'}</span>
      <InlineText key="current" startLine={lineAt(range.start)} label={applied ? `修改 ${index + 1} 后的正文` : `修改 ${index + 1} 当前正文`}
        value={current} onChange={text => onEdit(range, text)} />
    </div>}
    {pending && <div className="diffLine diffAfter">
      <span className="diffGutter" aria-hidden="true">+</span>
      <InlineText startLine={range ? lineAt(range.start) : item.lineNumber} label={`修改 ${index + 1} 建议（应用前可编辑）`} value={preview}
        disabled={busy || review.loading} onChange={text => review.editSuggestion(item.id, text, current)} />
    </div>}
    {conflict && <div className="changeConflict">
      <span>{range ? '此处已有手动修改，保留当前正文。编辑完成后可标记已手动处理。' : '无法唯一定位此处，正文已完整保留在页面中。请在正文中手动处理。'}</span>
      <button className="link" disabled={busy || review.loading} onClick={() => review.markHandled(item.id)}>已手动处理</button>
    </div>}
    {!confirmed && applied && !range && <p className="changeConflict">此处正文已再次修改，处理记录保留。当前文字可在上方正文中继续编辑。</p>}
  </div>
}
