import React, { useLayoutEffect, useRef } from 'react'

// Native plain-text editing keeps paste and IME behavior, with no independent scrollbars.
export function InlineText({ value, onChange, label, className = '', disabled = false }) {
  const local = useRef(null)
  useLayoutEffect(() => {
    const element = local.current
    if (!element) return
    element.style.height = '0px'
    element.style.height = `${Math.max(element.scrollHeight, 32)}px`
  }, [value])
  useLayoutEffect(() => {
    const element = local.current
    if (!element || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => {
      element.style.height = '0px'
      element.style.height = `${Math.max(element.scrollHeight, 32)}px`
    })
    observer.observe(element.parentElement)
    return () => observer.disconnect()
  }, [])
  return <textarea ref={local}
    className={`inlineText ${className}`} aria-label={label} value={value} disabled={disabled}
    rows={1} spellCheck={false} onChange={event => onChange(event.target.value)} />
}
