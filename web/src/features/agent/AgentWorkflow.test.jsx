// @vitest-environment jsdom
import React from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import App from '../../app/App.jsx'

const reply = data => ({ ok: true, status: 200, json: async () => ({ data }) })
const makeRun = (id, status, instruction = '核对本章') => ({ id, status, instruction, baseVersion: 1,
  lastEventSeq: 1, queuedAt: '2026-10-01T00:00:00Z', steps: [], sources: [], analysis: status === 'SUCCEEDED' ? '已核对正文' : null })

class FakeEventSource extends EventTarget {
  static current
  constructor() { super(); FakeEventSource.current = this }
  close() {}
}

function setup({ runs = [], create } = {}) {
  window.history.replaceState({}, '', '/projects/p/chapters/c')
  vi.stubGlobal('EventSource', FakeEventSource)
  const chapters = [
    { id: 'c', title: '第一章', chapterOrder: 1, version: 1, content: '原文' },
    { id: 'c2', title: '第二章', chapterOrder: 2, version: 1, content: '第二章正文' },
  ]
  const writes = []
  const cancelled = []
  globalThis.fetch = vi.fn(async (url, options = {}) => {
    if (url === '/api/v1/projects/p') return reply({ id: 'p', title: '作品' })
    if (url === '/api/v1/projects/p/chapters?limit=50') return reply({ items: chapters, nextCursor: null })
    if (url === '/api/v1/projects/p/settings?limit=50') return reply({ items: [], nextCursor: null })
    if (url === '/api/v1/system/capabilities') return reply({ agentConfigured: true, maxInstructionCodePoints: 2000 })
    const chapter = chapters.find(item => url === `/api/v1/chapters/${item.id}` || url === `/api/v1/chapters/${item.id}/content`)
    if (chapter) {
      if (options.method === 'PUT') { chapter.content = JSON.parse(options.body).content; chapter.version++ }
      return reply({ ...chapter })
    }
    if (url.startsWith('/api/v1/agent/runs?')) {
      const chapterId = new URL(url, 'http://localhost').searchParams.get('chapterId')
      return reply({ items: chapterId === 'c2' ? [] : runs.map(item => ({ ...item })), nextCursor: null })
    }
    if (url === '/api/v1/agent/runs' && options.method === 'POST') {
      const body = JSON.parse(options.body)
      writes.push(body)
      if (create) return create(body, writes.length, runs)
      const run = { ...makeRun(`r${writes.length}`, 'SUCCEEDED', body.instruction), baseVersion: body.expectedVersion }
      runs.unshift(run)
      return reply({ runId: run.id, status: 'QUEUED' })
    }
    const run = runs.find(item => url === `/api/v1/agent/runs/${item.id}` || url === `/api/v1/agent/runs/${item.id}/cancel`)
    if (run) {
      if (url.endsWith('/cancel')) { cancelled.push(run.id); run.cancelRequested = true }
      return reply({ ...run })
    }
    throw new Error(`Unexpected request ${url}`)
  })
  render(<App />)
  return { writes, cancelled, runs }
}

const input = () => screen.getByRole('textbox', { name: '给写作助手的要求' })
const send = () => screen.getByRole('button', { name: '发送要求', exact: true })
async function ready() { await waitFor(() => expect(send().disabled).toBe(false)) }

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); window.history.replaceState({}, '', '/') })

test('Enter sends once with the saved chapter version and moves the requirement into the result panel', async () => {
  const state = setup()
  await ready()
  fireEvent.change(input(), { target: { value: '  检查时间顺序  ' } })
  fireEvent.keyDown(input(), { key: 'Enter' })
  fireEvent.keyDown(input(), { key: 'Enter' })
  await screen.findByText('已核对正文')
  expect(state.writes).toHaveLength(1)
  expect(state.writes[0]).toMatchObject({ projectId: 'p', targetChapterId: 'c', expectedVersion: 1, instruction: '检查时间顺序' })
  expect(screen.getByText('检查时间顺序')).toBeTruthy()
  expect(input().value).toBe('')
  expect(send().disabled).toBe(true)
})

test('Chinese composition and Shift+Enter never submit a requirement', async () => {
  const state = setup()
  await ready()
  fireEvent.compositionStart(input())
  fireEvent.keyDown(input(), { key: 'Enter' })
  fireEvent.compositionEnd(input())
  fireEvent.keyDown(input(), { key: 'Enter', isComposing: true })
  fireEvent.keyDown(input(), { key: 'Enter', keyCode: 229 })
  fireEvent.keyDown(input(), { key: 'Enter', shiftKey: true })
  expect(state.writes).toHaveLength(0)
  fireEvent.keyDown(input(), { key: 'Enter' })
  await waitFor(() => expect(state.writes).toHaveLength(1))
})

test('an unknown submission response keeps the text and reuses its idempotency key on retry', async () => {
  const state = setup({ create: (body, attempt, runs) => {
    if (attempt === 1) throw new TypeError('连接中断')
    runs.push(makeRun('retry', 'SUCCEEDED', body.instruction))
    return reply({ runId: 'retry', status: 'QUEUED' })
  } })
  await ready()
  fireEvent.change(input(), { target: { value: '核对前文' } })
  fireEvent.click(send())
  await screen.findByRole('alert')
  expect(input().value).toBe('核对前文')
  fireEvent.click(send())
  await screen.findByText('已核对正文')
  expect(state.writes).toHaveLength(2)
  expect(state.writes[0]).toEqual(state.writes[1])
})

test('an unsaved chapter blocks submission until explicit saving and uses the new version', async () => {
  const state = setup()
  await ready()
  fireEvent.change(document.querySelector('.editor'), { target: { value: '修改后的正文' } })
  expect(send().disabled).toBe(true)
  fireEvent.keyDown(input(), { key: 'Enter' })
  expect(state.writes).toHaveLength(0)
  fireEvent.click(screen.getByRole('button', { name: '保存正文以发送要求' }))
  await ready()
  fireEvent.click(send())
  await waitFor(() => expect(state.writes).toHaveLength(1))
  expect(state.writes[0].expectedVersion).toBe(2)
})

test('requirements remain separate for each chapter and survive opening settings', async () => {
  setup()
  await ready()
  fireEvent.change(input(), { target: { value: '第一章的要求' } })
  fireEvent.click(screen.getByRole('button', { name: /第二章.*v1/ }))
  await waitFor(() => expect(document.querySelector('.editorHeader h1').textContent).toBe('第二章'))
  fireEvent.change(input(), { target: { value: '第二章的要求' } })
  fireEvent.click(screen.getByRole('button', { name: /第一章.*v1/ }))
  await waitFor(() => expect(input().value).toBe('第一章的要求'))
  fireEvent.click(screen.getByRole('button', { name: '管理设定' }))
  await screen.findByText('作品设定')
  expect(screen.queryByRole('textbox', { name: '给写作助手的要求' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: '关闭设定' }))
  expect(input().value).toBe('第一章的要求')
})

test('stop cancels the active task even while viewing history and waits for the server terminal state', async () => {
  const state = setup({ runs: [makeRun('active', 'RUNNING'), makeRun('old', 'SUCCEEDED')] })
  await screen.findByRole('button', { name: '停止任务', exact: true })
  fireEvent.change(screen.getByRole('combobox', { name: '历史任务' }), { target: { value: 'old' } })
  await screen.findByText('已核对正文')
  fireEvent.click(screen.getByRole('button', { name: '停止任务', exact: true }))
  await waitFor(() => expect(state.cancelled).toEqual(['active']))
  expect(screen.getByRole('button', { name: '正在停止', exact: true }).disabled).toBe(true)
  state.runs[0].status = 'CANCELLED'
  fireEvent.click(screen.getByRole('button', { name: '刷新任务' }))
  await ready()
  expect(state.writes).toHaveLength(0)
})

test('whitespace and over-limit requirements cannot be sent, and Unicode characters count once', async () => {
  const state = setup()
  await ready()
  fireEvent.change(input(), { target: { value: '   ' } })
  expect(send().disabled).toBe(true)
  fireEvent.change(input(), { target: { value: '墨'.repeat(2001) } })
  expect(send().disabled).toBe(true)
  fireEvent.change(input(), { target: { value: '😀'.repeat(2000) } })
  expect(send().disabled).toBe(false)
  fireEvent.click(send())
  await waitFor(() => expect(state.writes).toHaveLength(1))
  expect([...state.writes[0].instruction]).toHaveLength(2000)
})

test('a receipt for a previous chapter cannot replace the new chapter result or draft', async () => {
  let complete
  const pending = new Promise(resolve => { complete = resolve })
  setup({ create: () => pending })
  await ready()
  fireEvent.click(send())
  fireEvent.click(screen.getByRole('button', { name: /第二章.*v1/ }))
  await waitFor(() => expect(document.querySelector('.editorHeader h1').textContent).toBe('第二章'))
  const nextDraft = input().value
  await act(async () => { complete(reply({ runId: 'previous-chapter', status: 'QUEUED' })) })
  expect(input().value).toBe(nextDraft)
  expect(document.querySelector('.runDetail')).toBeNull()
  expect(fetch.mock.calls.some(([url]) => url === '/api/v1/agent/runs/previous-chapter')).toBe(false)
})

test('a delayed running task list cannot reactivate a task whose event stream confirmed completion', async () => {
  const state = setup({ runs: [makeRun('active', 'RUNNING')] })
  await screen.findByRole('button', { name: '停止任务', exact: true })
  await waitFor(() => expect(FakeEventSource.current).toBeTruthy())
  let finishList
  const pending = new Promise(resolve => { finishList = resolve })
  const originalFetch = fetch
  globalThis.fetch = vi.fn((url, options) => url.includes('chapterId=c&limit=50') ? pending : originalFetch(url, options))
  fireEvent.click(screen.getByRole('button', { name: '刷新任务' }))
  state.runs[0].status = 'SUCCEEDED'
  state.runs[0].analysis = '完成后的分析'
  state.runs[0].lastEventSeq = 4
  await act(async () => { FakeEventSource.current.dispatchEvent(new MessageEvent('run.succeeded', {
    data: JSON.stringify({ runId: 'active', seq: 4 }),
  })) })
  await ready()
  await act(async () => { finishList(reply({ items: [makeRun('active', 'RUNNING')], nextCursor: null })) })
  expect(send().disabled).toBe(false)
  expect(screen.queryByRole('button', { name: '停止任务', exact: true })).toBeNull()
  expect(screen.getByText('完成后的分析')).toBeTruthy()
})
