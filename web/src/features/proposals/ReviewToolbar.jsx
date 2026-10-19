import React, { useEffect, useRef } from 'react'

export function ReviewToolbar({ items, frames, selected, busy, review, onNavigate }) {
  const more = useRef(null)
  useEffect(() => {
    const close = event => { if (!more.current?.contains(event.target)) more.current?.removeAttribute('open') }
    document.addEventListener('pointerdown', close)
    return () => document.removeEventListener('pointerdown', close)
  }, [])
  const pending = items.map((item, index) => ({ item, index, conflict: frames.find(frame => frame.item.id === item.id)?.conflict }))
    .filter(({ item }) => item.status === 'PENDING')
  const conflicts = pending.filter(entry => entry.conflict).length
  const unsaved = items.filter(item => item.unsaved).length
  const current = pending.findIndex(entry => entry.index === selected)
  const previous = pending.filter(entry => selected != null && entry.index < selected).at(-1) || pending.at(-1)
  const next = pending.find(entry => selected == null || entry.index > selected) || pending[0]
  const disabled = busy || review.loading
  function action(callback) {
    more.current?.removeAttribute('open'); more.current?.querySelector('summary')?.focus({ preventScroll: true }); callback()
  }
  return <div className="reviewToolbar" role="region" aria-label="修改导航工具栏">
    <div className="reviewCount">
      <span>{pending.length ? `待处理 ${pending.length} 处` : unsaved ? review.saveFailed ? '保存失败' : '正在保存' : items.length ? '已全部处理' : '没有修改'}</span>
      {!!conflicts && <span className="conflictState"> · 待确认 {conflicts} 处</span>}
      {!!unsaved && !!pending.length && <span> · {review.saveFailed ? '保存失败' : '正在保存'} {unsaved} 处</span>}
    </div>
    <div className="reviewToolbarControls">
      <div className="reviewNavigation">
        <button className="secondary" disabled={!previous} onClick={() => onNavigate(previous.index)} aria-label="上一处待处理修改">↑</button>
        <button className="secondary" disabled={!next} onClick={() => onNavigate(next.index)} aria-label="下一处待处理修改">↓</button>
        {!!pending.length && <span className="reviewPosition" aria-label="待处理修改位置">{current >= 0 ? current + 1 : '—'} / {pending.length}</span>}
      </div>
      <div className="reviewGlobalActions">
        <button disabled={disabled || !pending.length} onClick={() => review.apply(pending.map(({ item }) => item.id))}>
          {pending.length === items.length && pending.length ? '应用全部' : '应用剩余'}</button>
        <details className="reviewMore" ref={more}
          onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) more.current?.removeAttribute('open') }}
          onKeyDown={event => {
            if (event.key === 'Escape') {
              event.preventDefault(); more.current?.removeAttribute('open'); more.current?.querySelector('summary')?.focus()
            }
          }}>
          <summary>更多</summary>
          <div className="reviewMoreActions">
            <button className="secondary" disabled={disabled || !pending.length} onClick={() => action(() => review.ignore(pending.map(({ item }) => item.id)))}>忽略剩余</button>
            <button className="secondary" disabled={disabled || review.saving || review.dirty} onClick={() => action(review.retry)}>刷新状态</button>
          </div>
        </details>
      </div>
    </div>
  </div>
}
