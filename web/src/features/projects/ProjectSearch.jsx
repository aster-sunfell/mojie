import React, { useRef } from 'react'
import { Icon } from '../../shared/ui/Icon.jsx'
import styles from './ProjectHome.module.css'

export function ProjectSearch({ value, onChange }) {
  const input = useRef(null)
  return <div role="search" className={styles.search}>
    <Icon name="search" />
    <input ref={input} type="search" aria-label="搜索作品名称" placeholder="搜索作品名称…"
      value={value} maxLength={80} onChange={event => onChange(event.target.value)} />
    {value && <button type="button" className={styles.clear} aria-label="清空搜索"
      onClick={() => { onChange(''); input.current.focus() }}><Icon name="close" size={16} /></button>}
  </div>
}
