import React, { useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import { Icon } from './Icon.jsx'
import { ScrollArea } from './ScrollArea.jsx'
import styles from './Drawer.module.css'

export function Drawer({ title, onClose, children }) {
  const dialog = useRef(null)
  useEffect(() => {
    const element = dialog.current
    const previous = document.activeElement
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    if (element.showModal) element.showModal()
    else element.setAttribute('open', '')
    return () => {
      element.close?.()
      document.body.style.overflow = overflow
      if (previous?.isConnected) previous.focus()
    }
  }, [])
  return createPortal(<dialog ref={dialog} className={styles.drawer} aria-label={title}
    onCancel={event => { event.preventDefault(); onClose() }}
    onClick={event => {
      const rect = event.currentTarget.getBoundingClientRect()
      if (event.target === event.currentTarget && (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom)) onClose()
    }}>
    <header className={styles.header}>
      <h2>{title}</h2>
      <button type="button" className={styles.close} aria-label={`关闭${title}`} onClick={onClose}>
        <Icon name="close" />
      </button>
    </header>
    <ScrollArea className={styles.content}>{children}</ScrollArea>
  </dialog>, document.body)
}
