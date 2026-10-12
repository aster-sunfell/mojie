import React, { useEffect, useId, useRef } from 'react'
import styles from './Dialog.module.css'
import { ScrollArea } from './ScrollArea.jsx'
import { Icon } from './Icon.jsx'

export function Dialog({ title, busy = false, onCancel, children, size, showClose = false, closeLabel = '关闭', className = '' }) {
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
      'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), a[href], [tabindex="0"]')]
      .filter(element => {
        for (let parent = element; parent && parent !== dialog.current.parentElement; parent = parent.parentElement) {
          const style = window.getComputedStyle(parent)
          if (parent.hidden || parent.inert || style.display === 'none' || style.visibility === 'hidden') return false
        }
        return true
      })
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
    <ScrollArea ref={dialog} className={[styles.dialog, size === 'wide' ? styles.wide : '', className].filter(Boolean).join(' ')} role="dialog" aria-modal="true"
      aria-labelledby={titleId} aria-busy={busy} tabIndex={-1}
      onClick={event => event.stopPropagation()}>
      <div className={styles.header}>
        <h2 id={titleId}>{title}</h2>
        {showClose && <button type="button" className={styles.close} disabled={busy} aria-label={closeLabel} onClick={onCancel}>
          <Icon name="close" size={20} />
        </button>}
      </div>
      {children}
    </ScrollArea>
  </div>
}
