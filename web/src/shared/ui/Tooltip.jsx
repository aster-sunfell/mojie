import React, { useEffect, useRef, useState } from 'react'

export const TOOLTIP_DELAY = 500

export function Tooltip({ label, placement = 'top', className, children }) {
  const [show, setShow] = useState(false)
  const timer = useRef(null)
  const open = () => {
    clearTimeout(timer.current)
    timer.current = setTimeout(() => setShow(true), TOOLTIP_DELAY)
  }
  const close = () => { clearTimeout(timer.current); setShow(false) }
  useEffect(() => () => clearTimeout(timer.current), [])
  return <span className={className ? `tooltipWrap ${className}` : 'tooltipWrap'}
    onMouseEnter={open} onMouseLeave={close} onFocus={open} onBlur={close}>
    {children}
    {show && <span className="tooltip" role="tooltip" data-placement={placement}>{label}</span>}
  </span>
}
