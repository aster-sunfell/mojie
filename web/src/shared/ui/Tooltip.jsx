import React, { useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import styles from './Tooltip.module.css'

export const TOOLTIP_DELAY = 500

export function Tooltip({ label, placement = 'top', className, children }) {
  const [show, setShow] = useState(false)
  const [position, setPosition] = useState(null)
  const timer = useRef(null)
  const anchor = useRef(null)
  const bubble = useRef(null)
  const id = useId()
  const open = () => {
    clearTimeout(timer.current)
    timer.current = setTimeout(() => setShow(true), TOOLTIP_DELAY)
  }
  const close = () => { clearTimeout(timer.current); setShow(false); setPosition(null) }
  useEffect(() => () => clearTimeout(timer.current), [])

  useLayoutEffect(() => {
    if (!show) return
    const update = () => {
      const trigger = anchor.current.getBoundingClientRect()
      const tooltip = bubble.current.getBoundingClientRect()
      const gutter = 8
      const gap = 8
      const above = trigger.top - tooltip.height - gap
      const below = trigger.bottom + gap
      let side = placement
      if (side === 'top' && above < gutter) side = 'bottom'
      if (side === 'bottom' && below + tooltip.height > window.innerHeight - gutter && above >= gutter) side = 'top'
      const center = trigger.left + trigger.width / 2
      const left = Math.max(gutter, Math.min(center - tooltip.width / 2, window.innerWidth - tooltip.width - gutter))
      setPosition({ left, top: side === 'top' ? above : below, placement: side,
        arrow: Math.max(gutter, Math.min(center - left, tooltip.width - gutter)) })
    }
    update()
    window.addEventListener('resize', update)
    window.addEventListener('scroll', update, true)
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(update)
    observer?.observe(anchor.current)
    observer?.observe(bubble.current)
    const dismiss = event => { if (event.key === 'Escape') close() }
    window.addEventListener('keydown', dismiss)
    return () => {
      window.removeEventListener('resize', update)
      window.removeEventListener('scroll', update, true)
      window.removeEventListener('keydown', dismiss)
      observer?.disconnect()
    }
  }, [show, placement, label])

  const trigger = React.isValidElement(children) ? React.cloneElement(children, {
    'aria-describedby': show ? [children.props['aria-describedby'], id].filter(Boolean).join(' ') : children.props['aria-describedby'],
  }) : children

  return <span ref={anchor} className={[styles.wrap, className].filter(Boolean).join(' ')}
    onMouseEnter={open} onMouseLeave={close} onFocus={open} onBlur={close}>
    {trigger}
    {show && createPortal(<span ref={bubble} id={id} className={styles.tooltip}
      role="tooltip" data-placement={position?.placement || placement}
      style={{ left: position?.left ?? -10000, top: position?.top ?? -10000,
        '--arrow-left': `${position?.arrow ?? 0}px` }}>{label}</span>, document.body)}
  </span>
}
