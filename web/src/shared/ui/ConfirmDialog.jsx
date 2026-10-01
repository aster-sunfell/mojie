import React from 'react'
import { Dialog } from './Dialog.jsx'
import styles from './Dialog.module.css'

export function ConfirmDialog({ title, busy, confirmLabel, onConfirm, onCancel, children }) {
  return <Dialog title={title} busy={busy} onCancel={onCancel}>
    {children}
    <div className={styles.actions}>
      <button className="secondary" data-autofocus disabled={busy} onClick={onCancel}>取消</button>
      <button className="dangerSolid" disabled={busy} onClick={onConfirm}>
        {busy ? '删除中…' : confirmLabel}
      </button>
    </div>
  </Dialog>
}
