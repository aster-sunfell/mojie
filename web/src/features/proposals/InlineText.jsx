import React, { useLayoutEffect, useRef } from 'react'

// Native plain-text editing keeps paste and IME behavior, with no independent scrollbars.
export function InlineText({ value, onChange, label, className = '', disabled = false, onFocus, onBlur }) {
  const local = useRef(null)
  const measure = useRef(null)
  useLayoutEffect(() => {
    const element = local.current
    // Measure outside the document flow: collapsing the focused textarea, even
    // within a layout effect, makes the browser clamp its ancestor's scrollTop.
    const mirror = element.ownerDocument.createElement('textarea')
    mirror.setAttribute('aria-hidden', 'true')
    mirror.tabIndex = -1
    mirror.readOnly = true
    mirror.style.cssText = 'position:fixed;left:-10000px;top:0;visibility:hidden;pointer-events:none;height:0;min-height:0;max-height:none;overflow:hidden;resize:none;'
    element.ownerDocument.body.appendChild(mirror)
    let width = -1
    const updateHeight = () => {
      const style = getComputedStyle(element)
      width = element.getBoundingClientRect().width
      for (const property of ['boxSizing', 'fontFamily', 'fontSize', 'fontWeight', 'fontStyle', 'lineHeight',
        'letterSpacing', 'textIndent', 'textTransform', 'tabSize', 'whiteSpace', 'wordBreak', 'overflowWrap',
        'paddingTop', 'paddingBottom', 'paddingLeft', 'paddingRight',
        'borderTopWidth', 'borderBottomWidth', 'borderLeftWidth', 'borderRightWidth', 'borderStyle'])
        mirror.style[property] = style[property]
      mirror.style.width = `${style.boxSizing === 'border-box' ? width : parseFloat(style.width)}px`
      mirror.value = element.value
      const padding = parseFloat(style.paddingTop) + parseFloat(style.paddingBottom)
      const border = parseFloat(style.borderTopWidth) + parseFloat(style.borderBottomWidth)
      const height = Math.max(mirror.scrollHeight + (style.boxSizing === 'border-box' ? border : -padding), 32)
      if (element.style.height !== `${height}px`) element.style.height = `${height}px`
    }
    measure.current = updateHeight
    element.ownerDocument.fonts?.ready.then(() => {
      if (measure.current === updateHeight) updateHeight()
    })
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(() => {
      // Height changes are our own work; only a new width requires remeasuring wraps.
      if (element.getBoundingClientRect().width !== width) measure.current?.()
    })
    observer?.observe(element)
    return () => { observer?.disconnect(); mirror.remove(); measure.current = null }
  }, [])
  useLayoutEffect(() => { measure.current?.() }, [value])
  return <textarea ref={local}
    className={`inlineText ${className}`} aria-label={label} value={value} disabled={disabled}
    rows={1} spellCheck={false} onFocus={onFocus} onBlur={onBlur} onChange={event => onChange(event.target.value)} />
}
