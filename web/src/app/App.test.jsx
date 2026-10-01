// @vitest-environment jsdom
import React from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import App from './App.jsx'

const project = { id: 'p', title: '作品' }
const chapter = { id: 'c', projectId: 'p', chapterOrder: 1, title: '第一章', content: '原文', version: 1 }
const reply = data => ({ ok: true, status: 200, json: async () => ({ data }) })

afterEach(() => { cleanup(); vi.restoreAllMocks(); window.history.replaceState({}, '', '/') })

test('late save response preserves newer typing and uses the returned version on next save', async () => {
  window.history.replaceState({}, '', '/projects/p/chapters/c')
  let finishFirstSave
  const firstSave = new Promise(resolve => { finishFirstSave = resolve })
  const writes = []
  globalThis.fetch = vi.fn((url, options = {}) => {
    if (url === '/api/v1/projects/p') return Promise.resolve(reply(project))
    if (url === '/api/v1/projects/p/chapters?limit=50') return Promise.resolve(reply({ items: [chapter], nextCursor: null }))
    if (url === '/api/v1/chapters/c' && options.method === 'GET') return Promise.resolve(reply(chapter))
    if (url.startsWith('/api/v1/agent/runs?')) return Promise.resolve(reply({ items: [], nextCursor: null }))
    if (url === '/api/v1/system/capabilities') return Promise.resolve(reply({ agentConfigured: false }))
    if (url === '/api/v1/chapters/c/content' && options.method === 'PUT') {
      writes.push(JSON.parse(options.body))
      return writes.length === 1 ? firstSave : Promise.resolve(reply({ ...chapter, content: '第二稿', version: 3 }))
    }
    throw new Error(`Unexpected request ${url}`)
  })
  render(<App />)
  await waitFor(() => expect(document.querySelector('.editor')?.value).toBe('原文'))
  const editor = document.querySelector('.editor')
  fireEvent.change(editor, { target: { value: '第一稿' } })
  fireEvent.click(screen.getByRole('button', { name: '保存正文' }))
  await waitFor(() => expect(writes).toHaveLength(1))
  fireEvent.change(editor, { target: { value: '第二稿' } })
  await act(async () => { finishFirstSave(reply({ ...chapter, content: '第一稿', version: 2 })) })
  expect(editor.value).toBe('第二稿')
  expect(document.querySelector('.editorHeader p').textContent).toContain('版本 2')
  expect(document.querySelector('.editorHeader p').textContent).toContain('未保存')
  fireEvent.click(screen.getByRole('button', { name: '保存正文' }))
  await waitFor(() => expect(writes).toHaveLength(2))
  expect(writes[1]).toEqual({ content: '第二稿', expectedVersion: 2 })
  await waitFor(() => expect(document.querySelector('.editorHeader p').textContent).toContain('已保存'))
})

test('save response from a previous chapter cannot replace the selected chapter', async () => {
  window.history.replaceState({}, '', '/projects/p/chapters/c')
  const other = { ...chapter, id: 'c2', chapterOrder: 2, title: '第二章', content: '第二章正文' }
  let finishSave
  const pending = new Promise(resolve => { finishSave = resolve })
  globalThis.fetch = vi.fn((url, options = {}) => {
    if (url === '/api/v1/projects/p') return Promise.resolve(reply(project))
    if (url === '/api/v1/projects/p/chapters?limit=50') return Promise.resolve(reply({ items: [chapter, other], nextCursor: null }))
    if (url === '/api/v1/chapters/c' && options.method === 'GET') return Promise.resolve(reply(chapter))
    if (url === '/api/v1/chapters/c2' && options.method === 'GET') return Promise.resolve(reply(other))
    if (url.startsWith('/api/v1/agent/runs?')) return Promise.resolve(reply({ items: [], nextCursor: null }))
    if (url === '/api/v1/system/capabilities') return Promise.resolve(reply({ agentConfigured: false }))
    if (url === '/api/v1/chapters/c/content' && options.method === 'PUT') return pending
    throw new Error(`Unexpected request ${url}`)
  })
  render(<App />)
  await waitFor(() => expect(document.querySelector('.editor')?.value).toBe('原文'))
  fireEvent.change(document.querySelector('.editor'), { target: { value: '第一章新稿' } })
  fireEvent.click(screen.getByRole('button', { name: '保存正文' }))
  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/v1/chapters/c/content', expect.any(Object)))
  act(() => {
    window.history.pushState({}, '', '/projects/p/chapters/c2')
    window.dispatchEvent(new PopStateEvent('popstate'))
  })
  await waitFor(() => expect(document.querySelector('.editor')?.value).toBe('第二章正文'))
  await act(async () => { finishSave(reply({ ...chapter, content: '第一章新稿', version: 2 })) })
  expect(document.querySelector('.editor').value).toBe('第二章正文')
  expect(document.querySelector('.editorHeader h1').textContent).toBe('第二章')
})

test('late task list from a previous chapter cannot appear after switching chapters', async () => {
  window.history.replaceState({}, '', '/projects/p/chapters/c')
  const other = { ...chapter, id: 'c2', chapterOrder: 2, title: '第二章', content: '第二章正文' }
  let finishOldRuns
  const oldRuns = new Promise(resolve => { finishOldRuns = resolve })
  globalThis.fetch = vi.fn((url) => {
    if (url === '/api/v1/projects/p') return Promise.resolve(reply(project))
    if (url === '/api/v1/projects/p/chapters?limit=50') return Promise.resolve(reply({ items: [chapter, other], nextCursor: null }))
    if (url === '/api/v1/chapters/c') return Promise.resolve(reply(chapter))
    if (url === '/api/v1/chapters/c2') return Promise.resolve(reply(other))
    if (url === '/api/v1/agent/runs?projectId=p&chapterId=c&limit=50') return oldRuns
    if (url === '/api/v1/agent/runs?projectId=p&chapterId=c2&limit=50') return Promise.resolve(reply({ items: [], nextCursor: null }))
    if (url === '/api/v1/system/capabilities') return Promise.resolve(reply({ agentConfigured: false }))
    throw new Error(`Unexpected request ${url}`)
  })
  render(<App />)
  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/v1/agent/runs?projectId=p&chapterId=c&limit=50', expect.any(Object)))
  fireEvent.click(await screen.findByRole('button', { name: /第二章.*v1/ }))
  await waitFor(() => expect(document.querySelector('.editorHeader h1')?.textContent).toBe('第二章'))
  await act(async () => { finishOldRuns(reply({ items: [{ id: 'old-run', status: 'FAILED', queuedAt: '2026-09-29T00:00:00Z' }], nextCursor: null })) })
  expect(document.querySelector('.agentPanel .runList').textContent).toContain('当前章节暂无任务')
  expect(document.querySelector('.agentPanel .runDetail')).toBeNull()
  expect(fetch.mock.calls.some(([url]) => url === '/api/v1/agent/runs/old-run')).toBe(false)
})

test('late task pagination from a previous chapter cannot replace the current list', async () => {
  window.history.replaceState({}, '', '/projects/p/chapters/c')
  const other = { ...chapter, id: 'c2', chapterOrder: 2, title: '第二章', content: '第二章正文' }
  let finishOldPage
  const oldPage = new Promise(resolve => { finishOldPage = resolve })
  globalThis.fetch = vi.fn((url) => {
    if (url === '/api/v1/projects/p') return Promise.resolve(reply(project))
    if (url === '/api/v1/projects/p/chapters?limit=50') return Promise.resolve(reply({ items: [chapter, other], nextCursor: null }))
    if (url === '/api/v1/chapters/c') return Promise.resolve(reply(chapter))
    if (url === '/api/v1/chapters/c2') return Promise.resolve(reply(other))
    if (url === '/api/v1/agent/runs?projectId=p&chapterId=c&limit=50') return Promise.resolve(reply({ items: [], nextCursor: 'old-page' }))
    if (url === '/api/v1/agent/runs?projectId=p&chapterId=c&limit=50&cursor=old-page') return oldPage
    if (url === '/api/v1/agent/runs?projectId=p&chapterId=c2&limit=50') return Promise.resolve(reply({ items: [], nextCursor: null }))
    if (url === '/api/v1/system/capabilities') return Promise.resolve(reply({ agentConfigured: false }))
    throw new Error(`Unexpected request ${url}`)
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: '加载更多任务' }))
  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/v1/agent/runs?projectId=p&chapterId=c&limit=50&cursor=old-page', expect.any(Object)))
  fireEvent.click(screen.getByRole('button', { name: /第二章.*v1/ }))
  await waitFor(() => expect(document.querySelector('.editorHeader h1')?.textContent).toBe('第二章'))
  await act(async () => { finishOldPage(reply({ items: [{ id: 'old-page-run', status: 'FAILED', queuedAt: '2026-09-29T00:00:00Z' }], nextCursor: 'another-old-page' })) })
  expect(document.querySelector('.agentPanel .runList').textContent).toContain('当前章节暂无任务')
  expect(screen.queryByRole('button', { name: '加载更多任务' })).toBeNull()
})

test('an older task list response cannot overwrite a newer refresh', async () => {
  window.history.replaceState({}, '', '/projects/p/chapters/c')
  let finishFirstList
  const firstList = new Promise(resolve => { finishFirstList = resolve })
  let listReads = 0
  globalThis.fetch = vi.fn(url => {
    if (url === '/api/v1/projects/p') return Promise.resolve(reply(project))
    if (url === '/api/v1/projects/p/chapters?limit=50') return Promise.resolve(reply({ items: [chapter], nextCursor: null }))
    if (url === '/api/v1/chapters/c') return Promise.resolve(reply(chapter))
    if (url === '/api/v1/agent/runs?projectId=p&chapterId=c&limit=50')
      return ++listReads === 1 ? firstList : Promise.resolve(reply({ items: [], nextCursor: null }))
    if (url === '/api/v1/system/capabilities') return Promise.resolve(reply({ agentConfigured: false }))
    throw new Error(`Unexpected request ${url}`)
  })
  render(<App />)
  await waitFor(() => expect(listReads).toBe(1))
  fireEvent.click(screen.getByRole('button', { name: '刷新任务' }))
  await waitFor(() => expect(listReads).toBe(2))
  await act(async () => { finishFirstList(reply({ items: [{ id: 'stale-run', status: 'FAILED', queuedAt: '2026-09-29T00:00:00Z' }], nextCursor: 'stale-cursor' })) })
  expect(document.querySelector('.agentPanel .runList').textContent).toContain('当前章节暂无任务')
  expect(screen.queryByRole('button', { name: '加载更多任务' })).toBeNull()
})

test('late proposal detail from a previously selected task cannot appear under another task', async () => {
  window.history.replaceState({}, '', '/projects/p/chapters/c')
  let finishOldProposal
  const oldProposal = new Promise(resolve => { finishOldProposal = resolve })
  const run = id => ({ id, projectId: 'p', targetChapterId: 'c', status: 'SUCCEEDED', proposalId: `q-${id}`,
    lastEventSeq: 1, queuedAt: id === 'r1' ? '2026-09-29T00:00:00Z' : '2026-09-29T01:00:00Z', steps: [], sources: [] })
  globalThis.fetch = vi.fn(url => {
    if (url === '/api/v1/projects/p') return Promise.resolve(reply(project))
    if (url === '/api/v1/projects/p/chapters?limit=50') return Promise.resolve(reply({ items: [chapter], nextCursor: null }))
    if (url === '/api/v1/chapters/c') return Promise.resolve(reply(chapter))
    if (url === '/api/v1/agent/runs?projectId=p&chapterId=c&limit=50')
      return Promise.resolve(reply({ items: [run('r1'), run('r2')], nextCursor: null }))
    if (url === '/api/v1/agent/runs/r1') return Promise.resolve(reply(run('r1')))
    if (url === '/api/v1/agent/runs/r2') return Promise.resolve(reply(run('r2')))
    if (url === '/api/v1/edit-proposals/q-r1') return oldProposal
    if (url === '/api/v1/edit-proposals/q-r2')
      return Promise.resolve(reply({ id: 'q-r2', status: 'PENDING', baseVersion: 1, baseContent: '原文', proposedContent: '第二份建议', reason: '第二份理由', items: [] }))
    if (url === '/api/v1/system/capabilities') return Promise.resolve(reply({ agentConfigured: false }))
    throw new Error(`Unexpected request ${url}`)
  })
  render(<App />)
  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/v1/edit-proposals/q-r1', expect.any(Object)))
  fireEvent.change(screen.getByRole('combobox', { name: '历史任务' }), { target: { value: 'r2' } })
  await screen.findByText('第二份理由')
  await act(async () => { finishOldProposal(reply({ id: 'q-r1', status: 'PENDING', baseVersion: 1, baseContent: '原文', proposedContent: '第一份旧建议', reason: '旧理由', items: [] })) })
  expect(screen.getByText('第二份理由')).toBeTruthy()
  expect(screen.queryByText('旧理由')).toBeNull()
})

test('chapter navigation loads the next page without losing the first page', async () => {
  window.history.replaceState({}, '', '/projects/p/chapters/c')
  const later = { ...chapter, id: 'c51', chapterOrder: 51, title: '第五十一章' }
  globalThis.fetch = vi.fn((url, options = {}) => {
    if (url === '/api/v1/projects/p') return Promise.resolve(reply(project))
    if (url === '/api/v1/projects/p/chapters?limit=50')
      return Promise.resolve(reply({ items: [chapter], nextCursor: 'page-two' }))
    if (url === '/api/v1/projects/p/chapters?limit=50&cursor=page-two')
      return Promise.resolve(reply({ items: [later], nextCursor: null }))
    if (url === '/api/v1/chapters/c') return Promise.resolve(reply(chapter))
    if (url === '/api/v1/chapters/c51' && options.method === 'GET') return Promise.resolve(reply(later))
    if (url === '/api/v1/chapters/c51/content' && options.method === 'PUT')
      return Promise.resolve(reply({ ...later, content: '更新后的正文', version: 2 }))
    if (url.startsWith('/api/v1/agent/runs?')) return Promise.resolve(reply({ items: [], nextCursor: null }))
    if (url === '/api/v1/system/capabilities') return Promise.resolve(reply({ agentConfigured: false }))
    throw new Error(`Unexpected request ${url}`)
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: '加载更多章节' }))
  await screen.findByRole('button', { name: /第五十一章/ })
  expect(screen.getByRole('button', { name: /第一章/ })).toBeTruthy()
  expect(screen.queryByRole('button', { name: '加载更多章节' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: /第五十一章/ }))
  await waitFor(() => expect(document.querySelector('.editorHeader h1')?.textContent).toBe('第五十一章'))
  expect(document.querySelector('.editor').value).toBe('原文')
  expect(screen.getByRole('button', { name: /第五十一章/ })).toBeTruthy()
  fireEvent.change(document.querySelector('.editor'), { target: { value: '更新后的正文' } })
  fireEvent.click(screen.getByRole('button', { name: '保存正文' }))
  await waitFor(() => expect(screen.getByRole('button', { name: /第五十一章.*v2/ })).toBeTruthy())
})

test('setting search queries the server beyond the loaded page', async () => {
  window.history.replaceState({}, '', '/projects/p/chapters/c')
  const remote = { id: 's60', type: 'WORLD', title: '远处的山谷', content: '河流湍急' }
  globalThis.fetch = vi.fn((url) => {
    if (url === '/api/v1/projects/p') return Promise.resolve(reply(project))
    if (url === '/api/v1/projects/p/chapters?limit=50')
      return Promise.resolve(reply({ items: [chapter], nextCursor: null }))
    if (url === '/api/v1/chapters/c') return Promise.resolve(reply(chapter))
    if (url === '/api/v1/projects/p/settings?limit=50')
      return Promise.resolve(reply({ items: [], nextCursor: 'more' }))
    if (url === '/api/v1/projects/p/settings?limit=50&keyword=%E6%B2%B3%E6%B5%81')
      return Promise.resolve(reply({ items: [remote], nextCursor: null }))
    if (url.startsWith('/api/v1/agent/runs?')) return Promise.resolve(reply({ items: [], nextCursor: null }))
    if (url === '/api/v1/system/capabilities') return Promise.resolve(reply({ agentConfigured: false }))
    throw new Error(`Unexpected request ${url}`)
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: '管理设定' }))
  fireEvent.change(screen.getByPlaceholderText('筛选标题或正文'), { target: { value: '河流' } })
  await screen.findByText('远处的山谷')
  expect(fetch).toHaveBeenCalledWith('/api/v1/projects/p/settings?limit=50&keyword=%E6%B2%B3%E6%B5%81', expect.any(Object))
})

test.each([
  ['取消', 'c', '未保存的草稿', false],
  ['放弃', 'c2', '第二章正文', false],
  ['保存', 'c2', '第二章正文', true],
])('switching chapters with %s follows the selected draft decision', async (decision, expectedId, expectedText, saved) => {
  window.history.replaceState({}, '', '/projects/p/chapters/c')
  const other = { ...chapter, id: 'c2', chapterOrder: 2, title: '第二章', content: '第二章正文' }
  vi.spyOn(window, 'prompt').mockReturnValue(decision)
  globalThis.fetch = vi.fn((url, options = {}) => {
    if (url === '/api/v1/projects/p') return Promise.resolve(reply(project))
    if (url === '/api/v1/projects/p/chapters?limit=50')
      return Promise.resolve(reply({ items: [chapter, other], nextCursor: null }))
    if (url === '/api/v1/chapters/c') return Promise.resolve(reply(chapter))
    if (url === '/api/v1/chapters/c2') return Promise.resolve(reply(other))
    if (url === '/api/v1/chapters/c/content' && options.method === 'PUT')
      return Promise.resolve(reply({ ...chapter, content: '未保存的草稿', version: 2 }))
    if (url.startsWith('/api/v1/agent/runs?')) return Promise.resolve(reply({ items: [], nextCursor: null }))
    if (url === '/api/v1/system/capabilities') return Promise.resolve(reply({ agentConfigured: false }))
    throw new Error(`Unexpected request ${url}`)
  })
  render(<App />)
  await waitFor(() => expect(document.querySelector('.editor')?.value).toBe('原文'))
  fireEvent.change(document.querySelector('.editor'), { target: { value: '未保存的草稿' } })
  fireEvent.click(screen.getByRole('button', { name: /第二章/ }))
  await waitFor(() => expect(window.location.pathname).toBe(`/projects/p/chapters/${expectedId}`))
  await waitFor(() => expect(document.querySelector('.editor')?.value).toBe(expectedText))
  expect(window.prompt).toHaveBeenCalledTimes(1)
  expect(fetch.mock.calls.some(([url, options]) => url === '/api/v1/chapters/c/content' && options?.method === 'PUT')).toBe(saved)
})

test('failed save during chapter switch preserves the draft and stays on the chapter', async () => {
  window.history.replaceState({}, '', '/projects/p/chapters/c')
  const other = { ...chapter, id: 'c2', chapterOrder: 2, title: '第二章', content: '第二章正文' }
  vi.spyOn(window, 'prompt').mockReturnValue('保存')
  globalThis.fetch = vi.fn((url, options = {}) => {
    if (url === '/api/v1/projects/p') return Promise.resolve(reply(project))
    if (url === '/api/v1/projects/p/chapters?limit=50')
      return Promise.resolve(reply({ items: [chapter, other], nextCursor: null }))
    if (url === '/api/v1/chapters/c') return Promise.resolve(reply(chapter))
    if (url === '/api/v1/chapters/c/content' && options.method === 'PUT')
      return Promise.resolve({ ok: false, status: 409, json: async () => ({ code: 'VERSION_CONFLICT', message: 'Version changed' }) })
    if (url.startsWith('/api/v1/agent/runs?')) return Promise.resolve(reply({ items: [], nextCursor: null }))
    if (url === '/api/v1/system/capabilities') return Promise.resolve(reply({ agentConfigured: false }))
    throw new Error(`Unexpected request ${url}`)
  })
  render(<App />)
  await waitFor(() => expect(document.querySelector('.editor')?.value).toBe('原文'))
  fireEvent.change(document.querySelector('.editor'), { target: { value: '未保存的草稿' } })
  fireEvent.click(screen.getByRole('button', { name: /第二章/ }))
  await screen.findByText('章节版本已变化，请先查看最新正文。')
  expect(window.location.pathname).toBe('/projects/p/chapters/c')
  expect(document.querySelector('.editor').value).toBe('未保存的草稿')
})

test('saving applied suggestions preserves newer typing without follow-up reads', async () => {
  window.history.replaceState({}, '', '/projects/p/chapters/c')
  let finishSave
  const saved = new Promise(resolve => { finishSave = resolve })
  const run = { id: 'r', projectId: 'p', targetChapterId: 'c', status: 'SUCCEEDED', proposalId: 'q',
    queuedAt: '2026-09-29T00:00:00Z', baseVersion: 1, lastEventSeq: 7, steps: [], sources: [] }
  const proposal = { id: 'q', status: 'PENDING', baseVersion: 1, baseContent: '原文',
    proposedContent: '采纳后的正文', reason: '修正设定冲突', items: [{ id: 0, start: 0, end: 2,
      lineNumber: 1, before: '原文', after: '采纳后的正文', contextBefore: '', contextAfter: '', status: 'PENDING' }] }
  const writes = []
  globalThis.fetch = vi.fn((url, options = {}) => {
    if (url === '/api/v1/projects/p') return Promise.resolve(reply(project))
    if (url === '/api/v1/projects/p/chapters?limit=50') return Promise.resolve(reply({ items: [chapter], nextCursor: null }))
    if (url === '/api/v1/chapters/c') return Promise.resolve(reply(chapter))
    if (url === '/api/v1/chapters/c/content') { writes.push(JSON.parse(options.body)); return saved }
    if (url.startsWith('/api/v1/agent/runs?')) return Promise.resolve(reply({ items: [run], nextCursor: null }))
    if (url === '/api/v1/agent/runs/r') return Promise.resolve(reply(run))
    if (url === '/api/v1/edit-proposals/q') return Promise.resolve(reply(proposal))
    if (url === '/api/v1/system/capabilities') return Promise.resolve(reply({ agentConfigured: false }))
    throw new Error(`Unexpected request ${url}`)
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: '应用全部' }))
  expect(screen.queryByRole('button', { name: '编辑正文' })).toBeNull()
  expect(screen.getByRole('textbox', { name: '修改 1 后的正文' }).value).toBe('采纳后的正文')
  fireEvent.click(screen.getByRole('button', { name: '保存正文' }))
  fireEvent.change(screen.getByRole('textbox', { name: '修改 1 后的正文' }), { target: { value: '采纳后的正文，加上新输入' } })
  await act(async () => { finishSave(reply({ ...chapter, content: '采纳后的正文', version: 2 })) })
  expect(screen.getByRole('textbox', { name: '修改 1 后的正文' }).value).toBe('采纳后的正文，加上新输入')
  expect(writes).toEqual([{ content: '采纳后的正文', expectedVersion: 1,
    proposalReviews: [{ proposalId: 'q', decisions: [{ itemId: 0, status: 'APPLIED' }] }] }])
  expect(screen.getByText('已保存 · 版本 2')).toBeTruthy()
  expect(fetch.mock.calls.filter(([url]) => url === '/api/v1/chapters/c')).toHaveLength(1)
})

test('home removes a project after confirming in the dialog', async () => {
  const deleted = []
  globalThis.fetch = vi.fn((url, options = {}) => {
    if (url === '/api/v1/projects?limit=20')
      return Promise.resolve(reply({ items: [{ id: 'p', title: '旧作', updatedAt: '2026-09-01T00:00:00Z' }], nextCursor: null }))
    if (url === '/api/v1/projects/p' && options.method === 'DELETE') { deleted.push(url); return Promise.resolve({ ok: true, status: 204 }) }
    throw new Error(`Unexpected request ${url}`)
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: '删除' }))
  fireEvent.click(await screen.findByRole('button', { name: '确认删除' }))
  await waitFor(() => expect(screen.queryByText('旧作')).toBeNull())
  expect(deleted).toEqual(['/api/v1/projects/p'])
  expect(screen.queryByRole('dialog')).toBeNull()
})

test('home keeps the project when the deletion dialog is cancelled', async () => {
  let deleteCalled = false
  globalThis.fetch = vi.fn((url, options = {}) => {
    if (url === '/api/v1/projects?limit=20')
      return Promise.resolve(reply({ items: [{ id: 'p', title: '旧作', updatedAt: '2026-09-01T00:00:00Z' }], nextCursor: null }))
    if (options.method === 'DELETE') { deleteCalled = true; return Promise.resolve({ ok: true, status: 204 }) }
    throw new Error(`Unexpected request ${url}`)
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: '删除' }))
  fireEvent.click(screen.getByRole('button', { name: '取消' }))
  expect(screen.queryByRole('dialog')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: '删除' }))
  fireEvent.keyDown(window, { key: 'Escape' })
  expect(screen.queryByRole('dialog')).toBeNull()
  expect(screen.getByText('旧作')).toBeTruthy()
  expect(deleteCalled).toBe(false)
})

test('home shows the busy error and keeps the project when deletion is rejected', async () => {
  globalThis.fetch = vi.fn((url, options = {}) => {
    if (url === '/api/v1/projects?limit=20')
      return Promise.resolve(reply({ items: [{ id: 'p', title: '旧作', updatedAt: '2026-09-01T00:00:00Z' }], nextCursor: null }))
    if (url === '/api/v1/projects/p' && options.method === 'DELETE')
      return Promise.resolve({ ok: false, status: 409, json: async () => ({ code: 'RESOURCE_BUSY', message: 'Project has an active run' }) })
    throw new Error(`Unexpected request ${url}`)
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: '删除' }))
  fireEvent.click(await screen.findByRole('button', { name: '确认删除' }))
  await screen.findByText('作品有正在运行的任务或待确认建议。')
  expect(screen.getByText('旧作')).toBeTruthy()
  expect(screen.queryByRole('dialog')).toBeNull()
})

test('home loads the next project page', async () => {
  globalThis.fetch = vi.fn((url) => {
    if (url === '/api/v1/projects?limit=20')
      return Promise.resolve(reply({ items: [{ id: 'p1', title: '第一卷', updatedAt: '2026-09-01T00:00:00Z' }], nextCursor: 'page-two' }))
    if (url === '/api/v1/projects?limit=20&cursor=page-two')
      return Promise.resolve(reply({ items: [{ id: 'p2', title: '第二卷', updatedAt: '2026-08-01T00:00:00Z' }], nextCursor: null }))
    throw new Error(`Unexpected request ${url}`)
  })
  render(<App />)
  await screen.findByText('第一卷')
  fireEvent.click(screen.getByRole('button', { name: '加载更多作品' }))
  await screen.findByText('第二卷')
  expect(screen.getByText('第一卷')).toBeTruthy()
  expect(screen.queryByRole('button', { name: '加载更多作品' })).toBeNull()
})
