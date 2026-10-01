import React from 'react'
import './scrollbar.css'
import styles from './ScrollArea.module.css'

/**
 * Native scrolling with the shared scrollbar theme, without an extra wrapper.
 * Set the available height/width with className or style to enable overflow.
 * Supports as="main"/"nav"/"pre", orientation="vertical"/"horizontal"/"both",
 * and native attributes, events and refs. Text-only regions can use tabIndex={0}
 * and aria-label for keyboard access. Existing elements can use .scrollbar.
 */
export function ScrollArea({ as: Component = 'div', orientation = 'both', className = '', ...props }) {
  const axis = orientation === 'vertical' ? styles.vertical
    : orientation === 'horizontal' ? styles.horizontal : ''
  return <Component {...props} className={['scrollbar', styles.viewport, axis, className].filter(Boolean).join(' ')} />
}
