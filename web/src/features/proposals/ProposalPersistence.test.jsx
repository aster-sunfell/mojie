// @vitest-environment jsdom
import React from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import App from '../../app/App.jsx'

const base = '开头。\n\n甲。\n\n乙。\n\n结尾。'
const reply = data => ({ ok: true, status: 200, json: async () => ({ data }) })
const failure = code => ({ ok: false, status: 409, json: async () => ({ code }) })
function deferred() {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}
function setup({ beforeSave, content = base } = {}) {
  window.history.replaceState({}, '', '/projects/p/chapters/c')
  localStorage.setItem('mojie:conversation:p', 'd')
  const chapter = { id: 'c', projectId: 'p', title: '第一章', chapterOrder: 1, version: 1, content }
  const other = { ...chapter, id: 'c2', title: '第二章', chapterOrder: 2, content: '第二章正文' }
  const items = [['甲', '新甲'], ['乙', '新乙']].map(([before, after], id) => {
    const start = base.indexOf(before), end = start + before.length
    return { id, before, after, start, end, lineNumber: base.slice(0, start).split('\n').length,
      contextBefore: base.slice(0, start), contextAfter: base.slice(end), status: 'PENDING' }
  })
  const proposal = { id: 'q', targetChapterId: 'c', status: 'PENDING', baseVersion: 1,
    baseContent: base, proposedContent: base.replace('甲', '新甲').replace('乙', '新乙'), items }
  const run = { id: 'r', projectId: 'p', targetChapterId: 'c', status: 'SUCCEEDED', proposalId: 'q',
    baseVersion: 1, lastEventSeq: 1, steps: [], sources: [] }
  const conversation = { id: 'd', title: '历史任务', lastSeq: 2, version: 1 }
  const writes = []
  globalThis.fetch = vi.fn(async (url, options = {}) => {
    const path = url.replace('/api/v1', '').split('?')[0]
    if (path === '/projects/p') return reply({ id: 'p', title: '测试作品' })
    if (path === '/projects/p/chapters') return reply({ items: [chapter, other], nextCursor: null })
    if (path === '/chapters/c') return reply({ ...chapter })
    if (path === '/chapters/c2') return reply({ ...other })
    if (path === '/chapters/c/content') {
      const body = JSON.parse(options.body)
      writes.push(body)
      const commit = () => {
        if (body.expectedVersion !== chapter.version) return failure('VERSION_CONFLICT')
        if (body.content !== chapter.content) { chapter.content = body.content; chapter.version++ }
        for (const review of body.proposalReviews || []) for (const choice of review.decisions) {
          if (items[choice.itemId].status === 'PENDING') Object.assign(items[choice.itemId], choice, { savedVersion: chapter.version })
        }
        const applied = items.some(item => item.status === 'APPLIED')
        proposal.status = items.every(item => item.status !== 'PENDING') ? applied ? 'APPLIED' : 'REJECTED'
          : applied ? 'PARTIALLY_APPLIED' : 'PENDING'
        return reply({ ...chapter })
      }
      return beforeSave ? beforeSave(body, commit, writes.length) : commit()
    }
    if (path === '/projects/p/conversations') return reply({ items: [conversation], nextCursor: null })
    if (path === '/conversations/d') return reply(conversation)
    if (path === '/conversations/d/messages') return reply({ items: [
      { id: 'm', seq: 2, role: 'ASSISTANT', content: '请审阅建议', runId: 'r' },
    ], nextCursor: null })
    if (path === '/edit-proposals/q') return reply(structuredClone(proposal))
    if (path === '/agent/runs') return reply({ items: [run], nextCursor: null })
    if (path === '/agent/runs/r') return reply(run)
    if (path === '/agent/runs/r/resources') return reply({ skills: [], artifacts: [] })
    if (path === '/system/capabilities') return reply({ agentConfigured: false })
    if (path === '/skills') return reply({ items: [], nextCursor: null })
    throw new Error(`Unexpected request ${url}`)
  })
  render(<App />)
  return { chapter, proposal, writes }
}
async function open() {
  fireEvent.click(await screen.findByRole('button', { name: '在正文中查看' }))
  await screen.findByRole('textbox', { name: '修改 1 建议（应用前可编辑）' })
}
const changes = () => [...document.querySelectorAll('.inlineChange')]
const apply = index => fireEvent.click(changes()[index].querySelector('.changeActions button'))
const header = () => document.querySelector('.editorHeader').textContent
afterEach(() => {
  cleanup(); localStorage.clear(); sessionStorage.clear(); vi.restoreAllMocks()
  window.history.replaceState({}, '', '/')
})

test('applying edited suggestions saves the current manual draft and survives remount', async () => {
  const state = setup()
  await open()
  const tail = screen.getAllByRole('textbox', { name: '章节正文', exact: true }).at(-1)
  fireEvent.change(tail, { target: { value: tail.value + '手写补充。' } })
  fireEvent.change(screen.getByRole('textbox', { name: '修改 1 建议（应用前可编辑）' }), { target: { value: '自定义甲。' } })
  fireEvent.click(screen.getByRole('button', { name: '应用全部' }))
  await waitFor(() => expect(header()).toContain('已保存'))
  expect(state.chapter.content).toBe(base.replace('甲。', '自定义甲。').replace('乙', '新乙') + '手写补充。')
  expect(state.writes).toHaveLength(1)
  expect(state.writes[0].proposalReviews[0].decisions[0].editedContent).toBe('自定义甲。')
  expect(document.querySelector('.messageProposal').textContent).toContain('处理完成')
  expect(window.dispatchEvent(new Event('beforeunload', { cancelable: true }))).toBe(true)
  cleanup(); render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: '在正文中查看' }))
  await screen.findByRole('textbox', { name: '修改 1 后的正文' })
  expect(screen.getByRole('textbox', { name: '修改 1 后的正文' }).value).toBe('自定义甲。')
  expect(changes().every(element => element.textContent.includes('已应用 · 版本 2'))).toBe(true)
})

test('ignoring all saves decisions immediately without changing content or chapter version', async () => {
  const state = setup()
  await open()
  fireEvent.click(screen.getByText('更多', { exact: true }))
  fireEvent.click(screen.getByRole('button', { name: '忽略剩余' }))
  await waitFor(() => expect(state.proposal.status).toBe('REJECTED'))
  await waitFor(() => expect(header()).toContain('已保存'))
  expect(state.chapter).toMatchObject({ content: base, version: 1 })
  expect(state.writes[0].proposalReviews[0].decisions).toEqual([
    { itemId: 0, status: 'IGNORED' }, { itemId: 1, status: 'IGNORED' },
  ])
  expect(document.querySelector('.messageProposal').textContent).toContain('已忽略')
  expect(screen.getByRole('button', { name: '保存正文' }).disabled).toBe(true)
})

test('consecutive applications queue writes with returned versions and preserve later typing', async () => {
  const first = deferred(), second = deferred()
  const state = setup({ beforeSave: (body, commit, count) => (count === 1 ? first : second).promise.then(commit) })
  await open()
  const firstButton = changes()[0].querySelector('.changeActions button')
  fireEvent.click(firstButton); fireEvent.click(firstButton)
  apply(1)
  expect(state.writes).toHaveLength(1)
  expect(document.querySelector('.messageProposal').textContent).not.toContain('处理完成')
  expect(screen.queryByText('已应用 · 版本 2')).toBeNull()
  fireEvent.change(screen.getByRole('textbox', { name: '修改 2 后的正文' }), { target: { value: '新乙。继续输入。' } })
  await act(async () => { first.resolve() })
  await waitFor(() => expect(state.writes).toHaveLength(2))
  expect(state.writes[1]).toMatchObject({ expectedVersion: 2,
    proposalReviews: [{ proposalId: 'q', decisions: [{ itemId: 1, status: 'APPLIED' }] }] })
  await act(async () => { second.resolve() })
  expect(state.chapter.content).toBe(base.replace('甲', '新甲').replace('乙', '新乙'))
  expect(screen.getByRole('textbox', { name: '修改 2 后的正文' }).value).toBe('新乙。继续输入。')
  expect(header()).toContain('未保存')
  expect(changes()[0].textContent).toContain('已应用 · 版本 2')
  expect(changes()[1].textContent).toContain('已应用 · 版本 3')
})

test('a failed write stops queued applications, preserves edits and retries all pending decisions', async () => {
  const first = deferred()
  const state = setup({ beforeSave: (body, commit, count) => count === 1
    ? first.promise.then(() => { throw new TypeError('网络连接中断') }) : commit() })
  await open()
  apply(0); apply(1)
  await act(async () => { first.resolve() })
  await screen.findByRole('button', { name: '重试保存修改' })
  expect(state.writes).toHaveLength(1)
  expect(state.chapter.content).toBe(base)
  expect(state.proposal.status).toBe('PENDING')
  expect(document.querySelector('.messageProposal').textContent).not.toContain('处理完成')
  expect(changes().every(element => element.textContent.includes('保存失败'))).toBe(true)
  expect(screen.getByRole('textbox', { name: '修改 1 后的正文' }).value).toBe('新甲。')
  fireEvent.click(screen.getByRole('button', { name: '重试保存修改' }))
  await waitFor(() => expect(state.proposal.status).toBe('APPLIED'))
  await waitFor(() => expect(header()).toContain('已保存'))
  expect(state.writes).toHaveLength(2)
  expect(state.writes[1].proposalReviews[0].decisions).toHaveLength(2)
  expect(screen.queryByRole('button', { name: '重试保存修改' })).toBeNull()
})

test('a version conflict preserves the local applied text and the server suggestion remains pending', async () => {
  const state = setup({ beforeSave: () => failure('VERSION_CONFLICT') })
  await open()
  apply(0)
  await screen.findByText('内容版本已变化，请先查看最新版本。')
  await screen.findByRole('button', { name: '重试保存修改' })
  expect(state.chapter.content).toBe(base)
  expect(state.proposal.items[0].status).toBe('PENDING')
  expect(changes()[0].textContent).toContain('保存失败')
  expect(screen.getByRole('textbox', { name: '修改 1 后的正文' }).value).toBe('新甲。')
})

test('apply all saves only safe edits and manual resolution saves the remaining conflict', async () => {
  const state = setup({ content: base.replace('乙', '丙') })
  await open()
  fireEvent.click(screen.getByRole('button', { name: '应用全部' }))
  await screen.findByText('1 处已应用，1 处需要手动确认，已保留当前内容。')
  expect(state.chapter.content).toBe(base.replace('甲', '新甲').replace('乙', '丙'))
  expect(state.proposal.status).toBe('PARTIALLY_APPLIED')
  fireEvent.change(screen.getByRole('textbox', { name: '修改 2 当前正文' }), { target: { value: '手动确认的文字。' } })
  fireEvent.click(screen.getByRole('button', { name: '已手动处理' }))
  await waitFor(() => expect(state.proposal.status).toBe('APPLIED'))
  expect(state.chapter.content).toContain('手动确认的文字。')
  expect(state.writes[1].proposalReviews[0].decisions).toEqual([
    { itemId: 1, status: 'APPLIED', editedContent: '手动确认的文字。' },
  ])
})

test('an application queues behind a manual save and navigation awaits both without another prompt', async () => {
  const first = deferred()
  const state = setup({ beforeSave: (body, commit, count) => count === 1 ? first.promise.then(commit) : commit() })
  await open()
  const tail = screen.getAllByRole('textbox', { name: '章节正文', exact: true }).at(-1)
  fireEvent.change(tail, { target: { value: tail.value + '手写内容。' } })
  fireEvent.click(screen.getByRole('button', { name: '保存正文' }))
  apply(0)
  const prompt = vi.spyOn(window, 'prompt')
  fireEvent.click(screen.getByRole('button', { name: /第二章.*v1/ }))
  expect(window.location.pathname).toBe('/projects/p/chapters/c')
  await act(async () => { first.resolve() })
  await waitFor(() => expect(window.location.pathname).toBe('/projects/p/chapters/c2'))
  expect(prompt).not.toHaveBeenCalled()
  expect(state.writes).toHaveLength(2)
  expect(state.writes[1].expectedVersion).toBe(2)
  expect(state.chapter.content).toBe(base.replace('甲', '新甲') + '手写内容。')
})
