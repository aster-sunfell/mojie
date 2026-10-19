// @vitest-environment jsdom
import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import App from '../../app/App.jsx'

const reply = data => ({ ok: true, status: 200, json: async () => ({ data }) })
const fail = (code = 'INTERNAL_ERROR') => ({ ok: false, status: 409, json: async () => ({ code, message: '暂时无法读取建议' }) })
function setup({ home = false, failSave = false, broken = false, paged = false } = {}) {
  window.history.replaceState({}, '', home ? '/' : '/projects/p/chapters/c')
  localStorage.setItem('mojie:conversation:p', 'new')
  const chapters = [
    { id: 'c', title: '第一章', chapterOrder: 1, content: '第一章正文', version: 1 },
    { id: 'c2', title: '第二章', chapterOrder: 2, content: '第二章原文', version: 1 },
  ]
  const rows = [
    { id: 'q', runId: 'old-run', conversationId: 'old', conversationTitle: '很早的检查', targetType: 'CHAPTER', operation: 'UPDATE',
      targetChapterId: 'c2', targetTitle: '第二章', chapterOrder: 2, baseVersion: 1, status: 'PENDING', remainingItemCount: 1, reason: '修正过河情节', createdAt: '2026-01-01T00:00:00Z' },
    { id: 'q2', runId: 'other-run', conversationId: 'other', conversationTitle: '另一段对话', targetType: 'CHAPTER', operation: 'UPDATE',
      targetChapterId: 'c2', targetTitle: '第二章', chapterOrder: 2, baseVersion: 1, status: 'PARTIALLY_APPLIED', remainingItemCount: 2, reason: '调整人物动作', createdAt: '2026-01-02T00:00:00Z' },
    { id: 'conflict', runId: 'conflict-run', conversationId: 'other', conversationTitle: '另一段对话', targetType: 'SETTING', operation: 'UPDATE',
      targetSettingId: 's', targetTitle: '林舟', status: 'CONFLICTED', remainingItemCount: null, reason: '版本已变化', createdAt: '2026-01-03T00:00:00Z' },
  ]
  const actionable = () => rows.filter(row => ['PENDING', 'PARTIALLY_APPLIED', 'CONFLICTED'].includes(row.status))
  const counts = () => ({ pendingCount: actionable().filter(row => row.status !== 'CONFLICTED').length,
    conflictedCount: actionable().filter(row => row.status === 'CONFLICTED').length, targetCount: 2 })
  const conversation = id => ({ id, title: id === 'new' ? '当前讨论' : id === 'old' ? '很早的检查' : '另一段对话', lastSeq: id === 'new' ? 0 : 2, version: 1,
    pendingProposalCount: actionable().filter(row => row.conversationId === id && row.status !== 'CONFLICTED').length,
    conflictedProposalCount: actionable().filter(row => row.conversationId === id && row.status === 'CONFLICTED').length })
  const writes = []
  const state = { broken, failedSave: failSave, rows, chapters, writes }
  globalThis.fetch = vi.fn(async (url, options = {}) => {
    const path = url.replace('/api/v1', '').split('?')[0], params = new URL(url, 'http://localhost').searchParams
    const total = counts()
    if (path === '/projects') return reply({ items: [{ id: 'p', title: '测试作品', pendingProposalCount: total.pendingCount, conflictedProposalCount: total.conflictedCount }], nextCursor: null })
    if (path === '/projects/p') return options.method === 'DELETE' ? fail('RESOURCE_BUSY') : reply({ id: 'p', title: '测试作品' })
    if (path === '/projects/p/chapters') return reply({ items: chapters, nextCursor: null })
    if (path === '/projects/p/edit-proposals/summary') return state.broken ? fail() : reply(total)
    if (path === '/projects/p/edit-proposals') {
      if (state.broken) return fail()
      const filter = params.get('filter')
      const all = actionable().filter(row => filter === 'all' || (filter === 'conflicted' ? row.status === 'CONFLICTED' : row.status !== 'CONFLICTED'))
      return reply({ items: paged ? all.slice(params.has('cursor') ? 1 : 0, params.has('cursor') ? undefined : 1) : all,
        nextCursor: paged && !params.has('cursor') && all.length > 1 ? 'next' : null })
    }
    const chapter = chapters.find(row => path === `/chapters/${row.id}` || path === `/chapters/${row.id}/content`)
    if (chapter) {
      if (options.method === 'PUT') {
        const body = JSON.parse(options.body); writes.push(body)
        if (state.failedSave && body.proposalReviews) { state.failedSave = false; return fail('VERSION_CONFLICT') }
        chapter.content = body.content; chapter.version++
        for (const review of body.proposalReviews || []) rows.find(row => row.id === review.proposalId).status = 'APPLIED'
      }
      return reply({ ...chapter })
    }
    if (path === '/projects/p/conversations') return reply({ items: ['new', 'old', 'other'].map(conversation), nextCursor: null })
    const id = path.match(/^\/conversations\/([^/]+)/)?.[1]
    if (id) {
      if (!path.endsWith('/messages')) return reply(conversation(id))
      return reply({ items: id === 'new' ? [] : [{ id: 'a', seq: 2, runId: id === 'old' ? 'old-run' : 'other-run', role: 'ASSISTANT', content: '早期生成的修改建议' }], nextCursor: null })
    }
    if (path.startsWith('/edit-proposals/')) {
      const row = rows.find(row => row.id === path.split('/').at(-1))
      return reply({ ...row, baseContent: '第二章原文', proposedContent: '第二章改文', items: [{ id: 0, start: 0, end: 5,
        before: '第二章原文', after: '第二章改文', lineNumber: 1, contextBefore: '', contextAfter: '', status: row.status === 'APPLIED' ? 'APPLIED' : 'PENDING' }] })
    }
    if (path === '/agent/runs') return reply({ items: [], nextCursor: null })
    if (path.endsWith('/resources')) return reply({ skills: [], artifacts: [] })
    if (path.startsWith('/agent/runs/')) {
      const row = rows.find(row => row.runId === path.split('/').at(-1))
      return reply({ ...row, id: row.runId, proposalId: row.id, status: 'SUCCEEDED', steps: [], sources: [], lastEventSeq: 1 })
    }
    if (path === '/system/capabilities') return reply({ agentConfigured: false })
    if (path === '/skills') return reply({ items: [], nextCursor: null })
    throw new Error(`Unexpected request ${url}`)
  })
  render(<App />)
  return state
}
async function openInbox() {
  fireEvent.click(await screen.findByRole('button', { name: /待处理建议/ }))
  const dialog = within(await screen.findByRole('dialog', { name: '待处理建议' }))
  await dialog.findByText('修正过河情节')
  return dialog
}
afterEach(() => { cleanup(); localStorage.clear(); sessionStorage.clear(); vi.restoreAllMocks(); window.history.replaceState({}, '', '/') })

test('entering a work reveals all pending suggestions without opening their conversations', async () => {
  const state = setup()
  await screen.findByRole('button', { name: '待处理建议 3' })
  expect(fetch.mock.calls.some(([url]) => url.includes('/conversations/old/messages'))).toBe(false)
  const dialog = await openInbox()
  expect(dialog.getByRole('heading', { name: /章节 · 第二章/ })).toBeTruthy()
  expect(dialog.getByText('剩余 2 处修改')).toBeTruthy()
  expect(dialog.getByText('来源：很早的检查')).toBeTruthy()
  expect(dialog.getByText(/待处理 2 份 · 需核对 1 份/)).toBeTruthy()
  fireEvent.click(dialog.getByRole('button', { name: '关闭' }))
  expect(screen.getByRole('button', { name: '待处理建议 3' })).toBeTruthy()
  expect(fetch.mock.calls.some(([url]) => url.includes('/conversations/old/messages'))).toBe(false)
  state.rows.push({ ...state.rows[0], id: 'new-suggestion' })
  fireEvent(window, new Event('focus'))
  await screen.findByRole('button', { name: '待处理建议 4' })
  expect(screen.getByText('新增 1 份待处理建议')).toBeTruthy()
})

test('direct review protects drafts and badges change only after the suggestion is successfully saved', async () => {
  const state = setup({ failSave: true })
  const editor = await screen.findByRole('textbox', { name: '章节正文', exact: true })
  fireEvent.change(editor, { target: { value: '手动新稿' } })
  const prompt = vi.spyOn(window, 'prompt').mockReturnValue('取消')
  const dialog = await openInbox()
  const row = within(document.querySelector('[data-proposal-id="q"]'))
  fireEvent.click(row.getByRole('button', { name: '审阅' }))
  await waitFor(() => expect(prompt).toHaveBeenCalledTimes(1))
  expect(screen.getByRole('dialog', { name: '待处理建议' })).toBeTruthy()
  expect(window.location.pathname).toBe('/projects/p/chapters/c')
  expect(editor.value).toBe('手动新稿')
  await waitFor(() => expect(row.getByRole('button', { name: '审阅' }).disabled).toBe(false))
  prompt.mockReturnValue('保存')
  fireEvent.click(row.getByRole('button', { name: '审阅' }))
  await screen.findByRole('textbox', { name: '修改 1 建议（应用前可编辑）' })
  expect(state.chapters[0].content).toBe('手动新稿')
  expect(window.location.pathname).toBe('/projects/p/chapters/c2')
  expect(screen.queryByRole('dialog', { name: '待处理建议' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: '应用', exact: true }))
  await screen.findByRole('button', { name: '重试保存修改' })
  expect(screen.getByRole('button', { name: '待处理建议 3' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: '重试保存修改' }))
  await screen.findByRole('button', { name: '待处理建议 2' })
  fireEvent.click(screen.getByRole('button', { name: '待处理建议 2' }))
  const refreshed = within(await screen.findByRole('dialog', { name: '待处理建议' }))
  await refreshed.findByText('调整人物动作')
  await waitFor(() => expect(document.querySelector('[data-proposal-id="q"]')).toBeNull())
  expect(refreshed.queryByText('修正过河情节')).toBeNull()
})

test('source navigation opens the exact historical turn and preserves the previous conversation draft', async () => {
  setup()
  const input = await screen.findByRole('textbox', { name: '对话消息' })
  fireEvent.change(input, { target: { value: '还没发送的构思' } })
  await openInbox()
  fireEvent.click(within(document.querySelector('[data-proposal-id="q"]')).getByRole('button', { name: '查看对话' }))
  await screen.findByText('早期生成的修改建议')
  await waitFor(() => expect(document.activeElement.dataset.runId).toBe('old-run'))
  expect(window.location.pathname).toBe('/projects/p/chapters/c')
  expect(screen.getByRole('button', { name: '待处理建议 3' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: '历史对话' }))
  const history = within(screen.getByRole('dialog', { name: '历史对话' }))
  await history.findByText('1 份建议待处理')
  expect(history.getByText('1 份建议待处理 · 1 份需核对')).toBeTruthy()
  fireEvent.click(history.getByRole('button', { name: '打开对话：当前讨论' }))
  await waitFor(() => expect(input.value).toBe('还没发送的构思'))
})

test('server pagination groups additional suggestions and filters conflicts without marking anything read', async () => {
  setup({ paged: true })
  const dialog = await openInbox()
  expect(dialog.getAllByRole('button', { name: '审阅' })).toHaveLength(1)
  fireEvent.click(dialog.getByRole('button', { name: '加载更多建议' }))
  await dialog.findByText('调整人物动作')
  expect(dialog.getAllByRole('heading', { name: /章节 · 第二章/ })).toHaveLength(1)
  fireEvent.click(dialog.getByRole('button', { name: '需核对', exact: true }))
  await waitFor(() => expect(dialog.queryByText('修正过河情节')).toBeNull())
  await dialog.findByText('版本已变化')
  expect(dialog.queryByRole('button', { name: '加载更多建议' })).toBeNull()
  expect(fetch.mock.calls.some(([url]) => url.includes('filter=conflicted'))).toBe(true)
})

test('a blocked deletion offers a direct path to the work-wide pending list', async () => {
  setup({ home: true })
  await screen.findByText('2 份建议待处理 · 1 份需核对')
  fireEvent.click(screen.getByRole('button', { name: '删除' }))
  fireEvent.click(screen.getByRole('button', { name: '确认删除' }))
  fireEvent.click(await screen.findByRole('button', { name: '查看待处理建议' }))
  await screen.findByRole('dialog', { name: '待处理建议' })
  await screen.findByText('修正过河情节')
  expect(window.location.search).toBe('?proposals=pending')
})

test('unavailable counts are not shown as zero and a list error can recover', async () => {
  const state = setup({ broken: true })
  fireEvent.click(await screen.findByRole('button', { name: '待处理建议 —' }))
  const dialog = within(screen.getByRole('dialog', { name: '待处理建议' }))
  await dialog.findByRole('alert')
  expect(dialog.queryByText('没有待处理建议，可以继续写作。')).toBeNull()
  state.broken = false
  fireEvent.click(dialog.getByRole('button', { name: '重试加载建议' }))
  await dialog.findByText('修正过河情节')
  expect(dialog.getByText(/待处理 2 份 · 需核对 1 份/)).toBeTruthy()
})
