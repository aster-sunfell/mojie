import React, { useCallback, useEffect, useRef, useState } from 'react'

export function ConfirmDialog({ title, busy, confirmLabel, onConfirm, onCancel, children }) {
  const cancel = useCallback(() => { if (!busy) onCancel() }, [busy, onCancel])
  useEffect(() => {
    const key = event => { if (event.key === 'Escape') cancel() }
    window.addEventListener('keydown', key)
    return () => window.removeEventListener('keydown', key)
  }, [cancel])
  return <div className="modalOverlay" onClick={cancel}>
    <div className="confirmDialog" role="dialog" aria-modal="true" aria-label={title} onClick={event => event.stopPropagation()}>
      <h2>{title}</h2>
      {children}
      <div className="confirmActions">
        <button className="secondary" autoFocus disabled={busy} onClick={cancel}>取消</button>
        <button className="dangerSolid" disabled={busy} onClick={onConfirm}>{busy ? '删除中…' : confirmLabel}</button>
      </div>
    </div>
  </div>
}
