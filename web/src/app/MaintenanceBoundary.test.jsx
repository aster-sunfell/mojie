// @vitest-environment jsdom
import React from 'react'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { MaintenanceBoundary } from './MaintenanceBoundary.jsx'
import { api } from '../shared/api/client.js'
import { maintenanceBlocked, setMaintenanceBlocked } from '../shared/api/maintenance.js'

const reply = active => ({ ok: true, json: async () => ({ data: { active } }) })
afterEach(() => { cleanup(); setMaintenanceBlocked(false); vi.useRealTimers(); vi.restoreAllMocks() })

test('maintenance blocks writes and preserves the mounted editor draft through recovery', async () => {
  vi.useFakeTimers()
  let active = false
  globalThis.fetch = vi.fn(async () => reply(active))
  render(<MaintenanceBoundary><textarea aria-label="草稿" defaultValue="原文" /></MaintenanceBoundary>)
  await act(async () => {})
  const editor = screen.getByRole('textbox')
  fireEvent.change(editor, { target: { value: '未保存的文字' } })
  editor.focus()
  active = true
  await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
  expect(screen.getByRole('dialog').textContent).toContain('墨界正在维护')
  expect(editor.closest('[inert]')).toBeTruthy()
  const before = fetch.mock.calls.length
  await expect(api('/chapters/c/content', 'PUT', { content: '不能保存' })).rejects.toMatchObject({ code: 'MAINTENANCE_MODE' })
  expect(fetch.mock.calls).toHaveLength(before)
  const shortcut = vi.fn()
  window.addEventListener('keydown', shortcut)
  fireEvent.keyDown(window, { key: 's', ctrlKey: true })
  expect(shortcut).not.toHaveBeenCalled()
  window.removeEventListener('keydown', shortcut)
  active = false
  await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
  expect(screen.queryByRole('dialog')).toBeNull()
  expect(screen.getByRole('textbox')).toBe(editor)
  expect(editor.value).toBe('未保存的文字')
  expect(document.activeElement).toBe(editor)
})

test('an unavailable or invalid maintenance status never unlocks the workbench', async () => {
  globalThis.fetch = vi.fn(async () => { throw new Error('offline') })
  render(<MaintenanceBoundary><button>保存</button></MaintenanceBoundary>)
  await act(async () => {})
  expect(screen.queryByRole('button', { name: '保存' })).toBeNull()
  expect(maintenanceBlocked()).toBe(true)
  fetch.mockResolvedValue({ ok: true, json: async () => ({ data: {} }) })
  fireEvent.click(screen.getByRole('button', { name: '检查恢复状态' }))
  await act(async () => {})
  expect(maintenanceBlocked()).toBe(true)
  fetch.mockResolvedValue(reply(false))
  fireEvent.click(screen.getByRole('button', { name: '检查恢复状态' }))
  await act(async () => {})
  expect(screen.getByRole('button', { name: '保存' })).toBeTruthy()
})

test('a server maintenance rejection opens the overlay without waiting for polling', async () => {
  globalThis.fetch = vi.fn(async () => reply(false))
  render(<MaintenanceBoundary><textarea aria-label="草稿" /></MaintenanceBoundary>)
  await act(async () => {})
  fetch.mockResolvedValue({ ok: false, status: 503, json: async () => ({ code: 'MAINTENANCE_MODE' }) })
  await act(async () => { await expect(api('/projects', 'POST', {})).rejects.toMatchObject({ code: 'MAINTENANCE_MODE' }) })
  expect(screen.getByRole('dialog').textContent).toContain('墨界正在维护')
  expect(document.querySelector('textarea').closest('[inert]')).toBeTruthy()
})
