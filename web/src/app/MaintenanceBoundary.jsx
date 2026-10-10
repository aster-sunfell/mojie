import React, { useEffect, useRef, useState } from 'react'
import { setMaintenanceBlocked } from '../shared/api/maintenance.js'
import './styles/maintenance.css'

export function MaintenanceBoundary({ children, intervalMs = 5000 }) {
  const [mode, setMode] = useState('checking')
  const [opened, setOpened] = useState(false)
  const checkRef = useRef(() => {})
  const panel = useRef(null)
  const modal = useRef(null)
  const previousFocus = useRef(null)
  const locked = mode !== 'ready'

  useEffect(() => {
    let disposed = false
    let timer
    let controller
    let checking = false
    let generation = 0
    setMaintenanceBlocked(true)
    const update = value => {
      if (disposed) return
      setMode(value)
      setMaintenanceBlocked(value !== 'ready')
      if (value === 'ready') setOpened(true)
    }
    const check = async () => {
      if (checking) return
      checking = true
      const requestGeneration = generation
      clearTimeout(timer)
      controller = new AbortController()
      const timeout = setTimeout(() => controller.abort(), 8000)
      try {
        const response = await fetch('/api/v1/system/maintenance', {
          cache: 'no-store', signal: controller.signal
        })
        const value = await response.json()
        if (!response.ok || typeof value.data?.active !== 'boolean') throw new Error('Invalid status')
        if (requestGeneration === generation) update(value.data.active ? 'maintenance' : 'ready')
      } catch {
        if (requestGeneration === generation) update('unavailable')
      } finally {
        clearTimeout(timeout)
        checking = false
        if (!disposed) timer = setTimeout(check, intervalMs)
      }
    }
    const announce = () => {
      generation++
      update('maintenance')
      // Abort an older status request: it must not unlock a newly rejected write.
      controller?.abort()
      clearTimeout(timer)
      timer = setTimeout(check, intervalMs)
    }
    checkRef.current = check
    window.addEventListener('mojie:maintenance', announce)
    window.addEventListener('online', check)
    void check()
    return () => {
      disposed = true
      clearTimeout(timer)
      controller?.abort()
      window.removeEventListener('mojie:maintenance', announce)
      window.removeEventListener('online', check)
      setMaintenanceBlocked(false)
    }
  }, [intervalMs])

  useEffect(() => {
    if (locked) {
      previousFocus.current = document.activeElement
      if (modal.current?.showModal) modal.current.showModal()
      else modal.current?.setAttribute('open', '')
      panel.current?.focus()
    } else if (previousFocus.current?.isConnected) {
      previousFocus.current.focus()
    }
  }, [locked])

  useEffect(() => {
    if (!locked) return
    const stopShortcuts = event => {
      if (event.key === 'Escape' || ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's')) {
        event.preventDefault()
        event.stopImmediatePropagation()
      }
    }
    window.addEventListener('keydown', stopShortcuts, true)
    return () => window.removeEventListener('keydown', stopShortcuts, true)
  }, [locked])

  return <>
    <div className="maintenance-workspace" inert={locked} aria-hidden={locked || undefined}>
      {opened && children}
    </div>
    {locked && <dialog className="maintenance-screen" ref={modal}
      aria-modal="true" aria-labelledby="maintenance-title" aria-describedby="maintenance-description"
      onCancel={event => event.preventDefault()}>
      <section className="maintenance-card" ref={panel} tabIndex={-1}>
        <div className="maintenance-mark" aria-hidden="true">墨</div>
        <span className="maintenance-eyebrow">墨界 · 写作工作台</span>
        <h1 id="maintenance-title">{mode === 'maintenance' ? '墨界正在维护' : '正在确认服务状态'}</h1>
        <p id="maintenance-description">{mode === 'maintenance'
          ? '正在更新服务，操作已暂时停用。恢复后会自动解除维护。'
          : mode === 'unavailable' ? '暂时无法连接服务，操作已停用。我们会继续检查连接。'
            : '请稍候，正在检查工作台是否可用。'}</p>
        {opened && <p className="maintenance-draft">当前页面的草稿仍保留，请不要刷新或关闭页面。</p>}
        <div className="maintenance-status" role="status"><span />每 5 秒自动检查</div>
        <button type="button" className="maintenance-retry" onClick={() => checkRef.current()}>检查恢复状态</button>
      </section>
    </dialog>}
  </>
}
