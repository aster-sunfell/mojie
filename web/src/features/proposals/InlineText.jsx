import React, { useLayoutEffect, useRef, useState } from 'react'

// Native plain-text editing keeps paste and IME behavior, with no independent scrollbars.
export function InlineText({ value, onChange, label, className = '', disabled = false, onFocus, onBlur,
  placeholder, readOnly = false, startLine = 1, hideFirstNumber = false, hideLastNumber = false }) {
  const local = useRef(null)
  const measure = useRef(null)
  const [lines, setLines] = useState([{ top: 0, rows: 1 }])
  const [hovered, setHovered] = useState(null)
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
    let geometry = null
    let wrapCache = new Map()
    const updateHeight = () => {
      const style = getComputedStyle(element)
      width = element.getBoundingClientRect().width
      const padding = parseFloat(style.paddingTop) + parseFloat(style.paddingBottom)
      const border = parseFloat(style.borderTopWidth) + parseFloat(style.borderBottomWidth)
      const properties = ['boxSizing', 'fontFamily', 'fontSize', 'fontWeight', 'fontStyle', 'lineHeight',
        'letterSpacing', 'textIndent', 'textTransform', 'tabSize', 'whiteSpace', 'wordBreak', 'overflowWrap',
        'paddingTop', 'paddingBottom', 'paddingLeft', 'paddingRight', 'direction',
        'borderTopWidth', 'borderBottomWidth', 'borderLeftWidth', 'borderRightWidth', 'borderStyle', 'borderRadius',
        'scrollbarGutter', 'scrollbarWidth']
      for (const property of properties) mirror.style[property] = style[property]
      // Preserve the unitless line height. Copying the rounded computed pixel
      // value accumulates a few pixels of error across a long chapter.
      mirror.style.lineHeight = style.getPropertyValue('--editor-line-height').trim() || style.lineHeight
      // Match the input's scrollbar gutter: reserving extra width in the
      // mirror invents wraps and leaves a large blank area below long text.
      mirror.style.width = `${style.boxSizing === 'border-box' ? width : parseFloat(style.width)}px`
      const text = readOnly ? element.textContent : element.value
      mirror.value = text
      const textHeight = mirror.scrollHeight
      const lineHeight = parseFloat(style.lineHeight)
      if (!Number.isFinite(lineHeight) || lineHeight <= 0) return
      const nextGeometry = [width, ...properties.map(property => style[property])].join('|')
      if (nextGeometry !== geometry) { wrapCache.clear(); geometry = nextGeometry }
      let top = parseFloat(style.paddingTop) + parseFloat(style.borderTopWidth)
      const nextCache = new Map()
      const layout = text.split('\n').map(line => {
        let rows = wrapCache.get(line) ?? nextCache.get(line)
        if (rows == null) {
          mirror.value = line
          rows = Math.max(1, Math.round((mirror.scrollHeight - padding) / lineHeight))
        }
        nextCache.set(line, rows)
        const position = { top, rows }
        top += rows * lineHeight
        return position
      })
      wrapCache = nextCache
      if (!readOnly) {
        const height = Math.max(textHeight + (style.boxSizing === 'border-box' ? border : -padding), 32)
        if (element.style.height !== `${height}px`) element.style.height = `${height}px`
      }
      setLines(previous => previous.length === layout.length && previous.every((line, index) =>
        line.top === layout[index].top && line.rows === layout[index].rows) ? previous : layout)
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
  }, [readOnly])
  useLayoutEffect(() => { measure.current?.() }, [value])
  function hoverLine(event) {
    if (event.pointerType === 'touch') return
    const element = local.current
    const style = getComputedStyle(element)
    const lineHeight = parseFloat(style.lineHeight)
    const y = event.clientY - element.getBoundingClientRect().top + element.scrollTop
    const row = lines.findIndex(line => y >= line.top && y < line.top + line.rows * lineHeight)
    const next = row < 0 || (hideFirstNumber && row === 0) || (hideLastNumber && row === lines.length - 1) ? null : row
    setHovered(previous => previous === next ? previous : next)
  }
  return <div className="numberedText" onPointerMove={hoverLine} onPointerLeave={() => setHovered(null)}>
    <div className="lineNumbers" aria-hidden="true">
      {lines.map((line, index) => ((hideFirstNumber && index === 0) || (hideLastNumber && index === lines.length - 1)) ? null
        : <span key={index} style={{ top: `${line.top}px` }}
          className={`lineNumber ${hovered === index ? 'hoveredLineNumber' : ''}`}>{startLine + index}</span>)}
    </div>
    {readOnly ? <pre ref={local} className={className} aria-label={label}>{value}</pre>
      : <textarea ref={local}
        className={`inlineText ${className}`} aria-label={label} value={value} disabled={disabled}
        rows={1} spellCheck={false} placeholder={placeholder} onFocus={onFocus} onBlur={onBlur}
        onChange={event => onChange(event.target.value)} />}
  </div>
}
