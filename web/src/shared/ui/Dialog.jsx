import React, { useEffect, useId, useRef } from 'react'
import styles from './Dialog.module.css'
import { ScrollArea } from './ScrollArea.jsx'

export function Dialog({ title, busy = false, onCancel, children }) {
  const titleId = useId()
  const dialog = useRef(null)
  const cancel = useRef(onCancel)
  const blocked = useRef(busy)
  cancel.current = onCancel
  blocked.current = busy

  useEffect(() => {
    const previous = document.activeElement
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const focusable = () => [...dialog.current.querySelectorAll(
      'button:not(:disabled), input:not(:disabled), [tabindex="0"]')]
    ;(dialog.current.querySelector('[data-autofocus]') || focusable()[0] || dialog.current).focus()
    const handleKey = event => {
      if (event.key === 'Escape') {
        event.preventDefault()
        if (!blocked.current) cancel.current()
      }
      if (event.key !== 'Tab') return
      const elements = focusable()
      const first = elements[0]
      const last = elements.at(-1)
      if (!first) { event.preventDefault(); dialog.current.focus(); return }
      if (event.shiftKey && (document.activeElement === first || !dialog.current.contains(document.activeElement))) {
        event.preventDefault(); last.focus()
      } else if (!event.shiftKey && (document.activeElement === last || !dialog.current.contains(document.activeElement))) {
        event.preventDefault(); first.focus()
      }
    }
    window.addEventListener('keydown', handleKey)
    return () => {
      window.removeEventListener('keydown', handleKey)
      document.body.style.overflow = previousOverflow
      if (previous?.isConnected) previous.focus()
    }
  }, [])

  return <div className={styles.overlay} onClick={() => { if (!busy) onCancel() }}>
    <ScrollArea ref={dialog} className={styles.dialog} role="dialog" aria-modal="true"
      aria-labelledby={titleId} aria-busy={busy} tabIndex={-1}
      onClick={event => event.stopPropagation()}>
      <h2 id={titleId}>{title}</h2>
      {children}
    </ScrollArea>
  </div>
}
