// @vitest-environment jsdom
import React from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import App from '../../app/App.jsx'

const reply = data => ({ ok: true, status: 200, json: async () => ({ data }) })
const failure = (code, status = 409) => ({ ok: false, status, json: async () => ({ code }) })
class FakeEventSource extends EventTarget {
  static instances = new Map()
  constructor(url) { super(); FakeEventSource.instances.set(url.split('/runs/')[1].split('/')[0], this) }
  close() {}
}
function setup({ initial = [], initialMessages = {}, result = 'SUCCEEDED', afterPost, savePost, capabilities, detail, provideProposal = false, proposalTarget, patchPost, listGet, skillItems = [] } = {}) {
  window.history.replaceState({}, '', '/projects/p/chapters/c')
  vi.stubGlobal('EventSource', FakeEventSource)
  const chapters = [
    { id: 'c', title: '第一章', chapterOrder: 1, version: 1, content: '原文' },
    { id: 'c2', title: '第二章', chapterOrder: 2, version: 1, content: '第二章正文' },
  ]
  const conversations = Object.fromEntries(initial.map((c, index) => [c.id, { title: `讨论 ${index}`, lastSeq: 0, version: 1, ...c }]))
  const records = Object.fromEntries(initial.map(c => [c.id, initialMessages[c.id] || []]))
  const runs = {}, receipts = new Map(), writes = [], saves = [], cancelled = [], creates = []
  if (initial.length) localStorage.setItem('mojie:conversation:p', initial[0].id)
  globalThis.fetch = vi.fn(async (url, options = {}) => {
    const path = url.replace('/api/v1', '').split('?')[0]
    if (path === '/projects/p') return reply({ id: 'p', title: '作品' })
    if (path === '/projects/p/chapters') return reply({ items: chapters, nextCursor: null })
    if (path === '/projects/p/settings') return reply({ items: [], nextCursor: null })
    if (path === '/system/capabilities') return reply({ agentConfigured: true, ...capabilities?.() })
    if (path === '/skills') return reply({ items: skillItems, nextCursor: null })
    if (path === '/skills/capabilities') return reply({ maxRawUploadBytes: 10485760, maxFiles: 500, executor: { configured: false } })
    const chapter = chapters.find(c => path === `/chapters/${c.id}` || path === `/chapters/${c.id}/content`)
    if (chapter) {
      if (options.method === 'PUT') {
        const body = JSON.parse(options.body); saves.push(body)
        if (savePost) return savePost(body, chapter)
        chapter.content = body.content; chapter.version++
      }
      return reply({ ...chapter })
    }
    if (path === '/projects/p/conversations') {
      if (options.method === 'POST') {
        const id = `d${Object.keys(conversations).length + 1}`
        const c = { id, title: '新对话', lastSeq: 0, version: 1 }
        conversations[id] = c; records[id] = []; creates.push(id)
        return reply({ ...c })
      }
      if (listGet) return listGet(url, conversations)
      return reply({ items: Object.values(conversations), nextCursor: null })
    }
    const id = path.match(/^\/conversations\/([^/]+)/)?.[1], c = conversations[id]
    if (c) {
      if (path === `/conversations/${id}`) {
        if (detail) { const override = detail(id); if (override) return override }
        if (options.method === 'PATCH') {
          if (patchPost) return patchPost(JSON.parse(options.body), c)
          Object.assign(c, JSON.parse(options.body)); c.version++
        }
        return reply({ ...c })
      }
      if (path.endsWith('/messages')) {
        if (options.method !== 'POST') return reply({ items: records[id].map(m => ({ ...m })), nextCursor: null })
        const body = JSON.parse(options.body); writes.push(body)
        let receipt = receipts.get(body.clientRequestId)
        if (!receipt) {
          const runId = `r${Object.keys(runs).length + 1}`, seq = ++c.lastSeq
          runs[runId] = { id: runId, conversationId: id, taskType: 'ASSISTANT', contextChapterId: body.contextChapterId, contextVersion: body.contextVersion,
            status: result, baseVersion: body.contextVersion, instruction: body.content, lastEventSeq: 1, steps: [], sources: [], analysis: result === 'SUCCEEDED' ? '已核对正文' : null }
          records[id].push({ id: `u${runId}`, seq, role: 'USER', content: body.content, runId, status: result })
          if (result === 'SUCCEEDED') records[id].push({ id: `a${runId}`, seq: ++c.lastSeq, role: 'ASSISTANT', content: '已核对正文', runId, status: result })
          else c.activeRunId = runId
          receipt = { messageId: `u${runId}`, runId, conversationSeq: seq }; receipts.set(body.clientRequestId, receipt)
        }
        if (provideProposal && body.content.includes('修改')) {
          const run = runs[receipt.runId], target = chapters.find(c => c.id === (proposalTarget || body.contextChapterId || 'c'))
          run.proposalId = `q-${receipt.runId}`; run.targetChapterId = target.id; run.baseVersion = target.version
        }
        return afterPost ? afterPost(body, writes.length, receipt) : reply(receipt)
      }
    }
    if (path.startsWith('/edit-proposals/')) {
      const run = Object.values(runs).find(r => r.proposalId === path.split('/').at(-1))
      return reply({ id: run.proposalId, targetChapterId: run.targetChapterId, status: 'PENDING', baseVersion: run.baseVersion, baseContent: '原文', proposedContent: '改写正文',
        items: [{ id: 0, start: 0, end: 2, lineNumber: 1, before: '原文', after: '改写正文', contextBefore: '', contextAfter: '', status: 'PENDING' }] })
    }
    if (path === '/agent/runs') return reply({ items: Object.values(runs).map(r => ({ ...r })), nextCursor: null })
    const runId = path.match(/^\/agent\/runs\/([^/]+)/)?.[1], run = runs[runId]
    if (run) {
      if (path.endsWith('/resources')) return reply({ skills: [], artifacts: [] })
      if (path.endsWith('/cancel')) { cancelled.push(runId); run.cancelRequested = true }
      return reply({ ...run })
    }
    throw new Error(`Unexpected request ${url}`)
  })
  render(<App />)
  return { conversations, records, runs, writes, saves, cancelled, creates, chapters }
}
const input = () => screen.getByRole('textbox', { name: '对话消息' })
const send = () => screen.getByRole('button', { name: '发送', exact: true })
async function ready(text = '核对前文') {
  await screen.findByRole('textbox', { name: '章节正文', exact: true })
  fireEvent.change(input(), { target: { value: text } })
  await waitFor(() => expect(send().disabled).toBe(false))
}
async function select(id) {
  fireEvent.click(screen.getByRole('button', { name: '历史对话' }))
  await waitFor(() => expect(document.querySelector(`[data-conversation-id="${id}"]`)).not.toBeNull())
  fireEvent.click(document.querySelector(`[data-conversation-id="${id}"]`))
}
afterEach(() => { cleanup(); localStorage.clear(); sessionStorage.clear(); vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); FakeEventSource.instances.clear() })

test('first Enter creates a conversation once and publishes only one user turn', async () => {
  const state = setup()
  await ready('  核对前文  ')
  expect(state.creates).toHaveLength(0)
  fireEvent.keyDown(input(), { key: 'Enter' }); fireEvent.keyDown(input(), { key: 'Enter' })
  await screen.findByText('已核对正文')
  expect(state.creates).toEqual(['d1'])
  expect(state.writes).toHaveLength(1)
  expect(state.writes[0]).toMatchObject({ content: '核对前文', contextChapterId: 'c', contextVersion: 1, expectedConversationSeq: 0 })
  expect(state.writes[0].targetChapterId).toBeUndefined()
  expect(state.writes[0].taskType).toBeUndefined()
  expect(screen.queryByRole('combobox', { name: '本轮任务' })).toBeNull()
  expect(input().value).toBe('')
  expect(document.querySelector('.assistantTabs')).toBeNull()
  expect(document.querySelector('.agentPanel')).toBeNull()
})

test('Chinese composition and Shift+Enter do not send', async () => {
  const state = setup(); await ready()
  fireEvent.compositionStart(input()); fireEvent.keyDown(input(), { key: 'Enter' }); fireEvent.compositionEnd(input())
  fireEvent.keyDown(input(), { key: 'Enter', isComposing: true }); fireEvent.keyDown(input(), { key: 'Enter', keyCode: 229 })
  fireEvent.keyDown(input(), { key: 'Enter', shiftKey: true })
  expect(state.creates).toHaveLength(0)
  fireEvent.keyDown(input(), { key: 'Enter' })
  await waitFor(() => expect(state.writes).toHaveLength(1))
})

test('natural-language analysis and editing use the same conversation without a mode flag', async () => {
  const state = setup(); await ready('只分析人物动机')
  expect(screen.queryByRole('button', { name: /仅分析/ })).toBeNull()
  fireEvent.click(send())
  await screen.findByText('已核对正文')
  expect(state.writes[0].content).toBe('只分析人物动机')
  expect(state.writes[0]).not.toHaveProperty('analysisOnly')
  expect(state.writes[0].taskType).toBeUndefined()
  fireEvent.click(screen.getByRole('button', { name: '专注对话' }))
  fireEvent.change(input(), { target: { value: '现在修改人物设定' } })
  fireEvent.click(send())
  await waitFor(() => expect(state.writes).toHaveLength(2))
  expect(state.writes[1].content).toBe('现在修改人物设定')
  expect(state.writes[1]).not.toHaveProperty('analysisOnly')
})

test('lost submission response retains the created conversation, text and idempotency key', async () => {
  const state = setup({ afterPost: (body, attempt, receipt) => { if (attempt === 1) throw new TypeError('连接中断'); return reply(receipt) } })
  await ready(); fireEvent.click(send())
  await screen.findByRole('alert')
  expect(input().value).toBe('核对前文')
  fireEvent.click(send()); await screen.findByText('已核对正文')
  expect(state.creates).toHaveLength(1)
  expect(state.writes).toHaveLength(2)
  expect(state.writes[1]).toEqual(state.writes[0])
  expect(state.records.d1).toHaveLength(2)
})

test('clearing and restoring the preferred chapter changes context without selecting a task', async () => {
  const state = setup(); await ready('讨论人物')
  fireEvent.click(screen.getByRole('button', { name: '移除章节参考' }))
  expect(screen.getByRole('button', { name: '使用当前章节' })).toBeTruthy()
  fireEvent.click(send()); await screen.findByText('已核对正文')
  expect(state.writes[0].contextChapterId).toBeUndefined()
  expect(state.writes[0].contextVersion).toBeUndefined()
  fireEvent.click(screen.getByRole('button', { name: '使用当前章节' }))
  fireEvent.change(input(), { target: { value: '审核本章，再提出修改建议' } })
  await waitFor(() => expect(send().disabled).toBe(false)); fireEvent.click(send())
  await waitFor(() => expect(state.writes).toHaveLength(2))
  expect(state.writes[1]).toMatchObject({ contextChapterId: 'c', contextVersion: 1 })
  expect(state.writes[1].taskType).toBeUndefined()
})

test('switching chapters after acceptance preserves the original turn reference', async () => {
  const state = setup({ result: 'RUNNING' }); await ready('检查这一章'); fireEvent.click(send())
  await screen.findByRole('button', { name: '停止', exact: true })
  fireEvent.click(screen.getByRole('button', { name: /第二章.*v1/ }))
  await screen.findByRole('heading', { name: '第二章' })
  expect(state.writes[0]).toMatchObject({ contextChapterId: 'c', contextVersion: 1 })
  expect(screen.getByText('参考：第一章 · v1')).toBeTruthy()
  expect(document.querySelector('.assistantChapterLabel').textContent).toBe('第二章')
})

test('a proposal can target a different chapter from the preferred reference', async () => {
  setup({ provideProposal: true, proposalTarget: 'c2' }); await ready('修改第二章')
  fireEvent.click(send())
  await screen.findByRole('button', { name: '在正文中查看' })
  expect(screen.getByText('参考：第一章 · v1')).toBeTruthy()
  expect(screen.getByText('第二章 · 修改建议')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: '在正文中查看' }))
  await screen.findByRole('heading', { name: '第二章' })
  expect(window.location.pathname).toBe('/projects/p/chapters/c2')
})

test('unsaved text is clearly excluded and sending retains the saved chapter reference', async () => {
  const state = setup(); await ready()
  fireEvent.change(screen.getByRole('textbox', { name: '章节正文', exact: true }), { target: { value: '未保存' } })
  expect(screen.getByText('助手读取已保存版本，本轮不包含未保存正文。')).toBeTruthy()
  fireEvent.click(send()); await screen.findByText('已核对正文')
  expect(state.saves).toHaveLength(0)
  expect(state.writes[0]).toMatchObject({ contextChapterId: 'c', contextVersion: 1 })
  expect(state.writes[0].targetChapterId).toBeUndefined()
})

test('changing chapters and reading modes preserve conversation and chapter drafts', async () => {
  setup({ initial: [{ id: 'a' }], initialMessages: { a: [{ id: 'm', role: 'USER', content: '之前的消息', seq: 1 }] } })
  await ready('这段输入应继续保留')
  fireEvent.click(screen.getByRole('button', { name: /第二章.*v1/ }))
  await screen.findByRole('heading', { name: '第二章' })
  expect(input().value).toBe('这段输入应继续保留')
  fireEvent.change(screen.getByRole('textbox', { name: '章节正文', exact: true }), { target: { value: '尚未保存的第二章' } })
  fireEvent.click(screen.getByRole('button', { name: '专注章节' }))
  expect(screen.queryByRole('textbox', { name: '对话消息' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: '专注对话' }))
  expect(screen.queryByRole('textbox', { name: '章节正文', exact: true })).toBeNull()
  expect(screen.getByText('之前的消息')).toBeTruthy()
  expect(input().value).toBe('这段输入应继续保留')
  fireEvent.click(screen.getByRole('button', { name: '并排阅读' }))
  expect(screen.getByRole('textbox', { name: '章节正文', exact: true }).value).toBe('尚未保存的第二章')
  expect(input().value).toBe('这段输入应继续保留')
  fireEvent.click(screen.getByRole('button', { name: '作品设定' }))
  await screen.findByRole('dialog', { name: '作品设定' }); expect(input().value).toBe('这段输入应继续保留')
})

test('separate conversation drafts survive switching and new conversations are created lazily', async () => {
  const state = setup({ initial: [{ id: 'a' }, { id: 'b' }] }); await ready('对话 A 的草稿')
  await select('b'); await ready('对话 B 的草稿'); await select('a')
  await waitFor(() => expect(input().value).toBe('对话 A 的草稿'))
  fireEvent.click(screen.getByRole('button', { name: '新对话', exact: true }))
  expect(input().value).toBe(''); expect(state.creates).toHaveLength(0)
  await select('b'); expect(input().value).toBe('对话 B 的草稿')
})

test('late conversation detail cannot replace the newly selected conversation', async () => {
  let finish, wait = true
  const pending = new Promise(resolve => { finish = resolve })
  const state = setup({ initial: [{ id: 'a' }, { id: 'b' }], detail: id => id === 'a' && wait ? pending : null })
  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/v1/conversations/a', expect.any(Object)))
  await select('b'); await ready('对话 B 的输入')
  wait = false
  await act(async () => { finish(reply({ ...state.conversations.a, title: '旧的延迟对话' })) })
  expect(screen.queryByText('旧的延迟对话')).toBeNull()
  expect(input().value).toBe('对话 B 的输入')
})

test('active turns allow drafting the next message and stop waits for the server terminal state', async () => {
  const state = setup({ result: 'RUNNING' }); await ready(); fireEvent.click(send())
  await screen.findByRole('button', { name: '停止', exact: true })
  fireEvent.change(input(), { target: { value: '下一轮的输入' } })
  fireEvent.keyDown(input(), { key: 'Enter' }); expect(state.writes).toHaveLength(1)
  fireEvent.click(screen.getByRole('button', { name: '停止', exact: true }))
  await waitFor(() => expect(state.cancelled).toEqual(['r1']))
  expect(screen.getByRole('button', { name: '正在停止', exact: true }).disabled).toBe(true)
  const run = state.runs.r1; run.status = 'CANCELLED'; run.lastEventSeq = 3
  state.conversations.d1.activeRunId = null; state.records.d1[0].status = 'CANCELLED'
  await act(async () => { FakeEventSource.instances.get('r1').dispatchEvent(new MessageEvent('run.cancelled', { data: JSON.stringify({ runId: 'r1', seq: 3 }) })) })
  await waitFor(() => expect(send().disabled).toBe(false))
  expect(input().value).toBe('下一轮的输入')
})

test('assistant commentary and tools stay in one assistant region as the final reply arrives', async () => {
  const state = setup({ result: 'RUNNING' }); await ready('检查人物动机'); fireEvent.click(send())
  await screen.findByRole('button', { name: '停止', exact: true })
  const assistant = document.querySelector('.conversationMessage.assistant[data-run-id="r1"]')
  const user = document.querySelector('.conversationMessage.user')
  expect(assistant).not.toBeNull()
  const run = state.runs.r1
  run.activities = [{ id: 'comment-1', modelCallIndex: 1, text: '我先阅读本章，再核对人物设定。' },
    { id: 'comment-2', modelCallIndex: 2, text: '已读完本章，接下来核对人物动机。' }]
  run.steps = [{ toolExecutionId: 'read-1', modelCallIndex: 1, toolName: 'read_chapter', args: { chapterId: 'c' },
    result: { success: true, data: { title: '第一章' } }, status: 'SUCCEEDED' }]
  run.lastEventSeq = 2
  await waitFor(() => expect(FakeEventSource.instances.has('r1')).toBe(true))
  await act(async () => { FakeEventSource.instances.get('r1').dispatchEvent(new MessageEvent('model.finished', { data: JSON.stringify({ runId: 'r1', seq: 2 }) })) })
  await within(assistant).findByText('我先阅读本章，再核对人物设定。')
  expect(user.textContent).not.toMatch(/先阅读|已完成|执行详情/)
  expect(within(assistant).getByText('已阅读《第一章》')).toBeTruthy()
  const timeline = [...assistant.querySelector('.conversationActivity').children]
  expect(timeline.map(element => element.textContent)).toEqual([
    '我先阅读本章，再核对人物设定。', expect.stringContaining('已阅读《第一章》'), '已读完本章，接下来核对人物动机。', expect.any(String),
  ])
  const tool = assistant.querySelector('.conversationToolActivity')
  tool.open = true
  run.status = 'SUCCEEDED'; run.analysis = '人物动机有一处需要补充。'; run.lastEventSeq = 3
  state.conversations.d1.activeRunId = null; state.conversations.d1.lastSeq = 2
  state.records.d1[0].status = 'SUCCEEDED'
  state.records.d1.push({ id: 'a-r1', seq: 2, role: 'ASSISTANT', content: run.analysis, runId: 'r1', status: 'SUCCEEDED' })
  await act(async () => { FakeEventSource.instances.get('r1').dispatchEvent(new MessageEvent('run.succeeded', { data: JSON.stringify({ runId: 'r1', seq: 3 }) })) })
  await screen.findByText(run.analysis)
  expect(document.querySelector('.conversationMessage.assistant')).toBe(assistant)
  expect(assistant.querySelector('.conversationToolActivity')).toBe(tool)
  expect(tool.open).toBe(true)
  expect(screen.getAllByText(run.analysis)).toHaveLength(1)
  expect(assistant.querySelector('.assistantProgress')).toBeNull()
  expect(screen.queryByText('执行详情')).toBeNull()
  expect(assistant.textContent).not.toMatch(/模型 \d|工具 \d/)
})

test('accepted input renders the assistant before the conversation reload finishes', async () => {
  let finish
  const pending = new Promise(resolve => { finish = resolve })
  const state = setup({ result: 'RUNNING', detail: () => pending })
  await ready('已接受的输入'); fireEvent.click(send())
  await screen.findByText('已接受的输入')
  const assistant = document.querySelector('.conversationMessage.assistant[data-run-id="r1"]')
  expect(assistant).not.toBeNull()
  await act(async () => { finish(reply({ ...state.conversations.d1 })) })
  await screen.findByRole('button', { name: '停止', exact: true })
  expect(document.querySelector('.conversationMessage.assistant[data-run-id="r1"]')).toBe(assistant)
  expect(screen.getAllByText('已接受的输入')).toHaveLength(1)
})

for (const status of ['FAILED', 'CANCELLED']) {
  test(`${status} preserves the assistant process and puts recovery on the assistant side`, async () => {
    const state = setup({ result: 'RUNNING' }); await ready(); fireEvent.click(send())
    await screen.findByRole('button', { name: '停止', exact: true })
    const assistant = document.querySelector('.conversationMessage.assistant')
    const run = state.runs.r1
    run.activities = [{ id: 'comment-1', modelCallIndex: 1, text: '我先查阅前文。' }]
    run.steps = [{ toolExecutionId: 'search-1', modelCallIndex: 1, toolName: 'search_chapters', args: { keyword: '林舟' }, status: 'ABORTED' }]
    run.status = status; run.lastEventSeq = 3
    state.conversations.d1.activeRunId = null; state.records.d1[0].status = status
    await waitFor(() => expect(FakeEventSource.instances.has('r1')).toBe(true))
    await act(async () => { FakeEventSource.instances.get('r1').dispatchEvent(new MessageEvent(status === 'FAILED' ? 'run.failed' : 'run.cancelled', { data: JSON.stringify({ runId: 'r1', seq: 3 }) })) })
    await within(assistant).findByRole('button', { name: '重试本轮' })
    expect(within(assistant).getByText('我先查阅前文。')).toBeTruthy()
    expect(within(assistant).getByText('已停止：搜索章节：“林舟”')).toBeTruthy()
    expect(document.querySelector('.conversationMessage.user').textContent).not.toMatch(/未完成|已停止|我先查阅|重试/)
    expect(assistant.querySelector('.assistantAnswer')).toBeNull()
    expect(assistant.querySelector('.assistantProgress')).toBeNull()
  })
}

test('a background reply stays connected in chapter focus and can be read without losing either draft', async () => {
  const state = setup({ result: 'RUNNING' }); await ready(); fireEvent.click(send())
  await screen.findByRole('button', { name: '停止', exact: true })
  fireEvent.change(input(), { target: { value: '下一轮追问' } })
  fireEvent.click(screen.getByRole('button', { name: '专注章节', exact: true }))
  fireEvent.change(screen.getByRole('textbox', { name: '章节正文', exact: true }), { target: { value: '正在编辑的正文' } })
  state.runs.r1.status = 'SUCCEEDED'; state.runs.r1.lastEventSeq = 3
  state.conversations.d1.activeRunId = null; state.conversations.d1.lastSeq = 2
  state.records.d1.push({ id: 'background-answer', seq: 2, role: 'ASSISTANT', content: '后台完成的回复', runId: 'r1', status: 'SUCCEEDED' })
  await waitFor(() => expect(FakeEventSource.instances.has('r1')).toBe(true))
  await act(async () => { FakeEventSource.instances.get('r1').dispatchEvent(new MessageEvent('run.succeeded', { data: JSON.stringify({ runId: 'r1', seq: 3 }) })) })
  await screen.findByRole('button', { name: /专注对话.*有新回复/ })
  expect(screen.queryByRole('textbox', { name: '对话消息' })).toBeNull()
  expect(screen.getByRole('textbox', { name: '章节正文', exact: true }).value).toBe('正在编辑的正文')
  fireEvent.click(screen.getByRole('button', { name: /专注对话/ }))
  expect(screen.getByText('后台完成的回复')).toBeTruthy()
  expect(input().value).toBe('下一轮追问')
  expect(screen.queryByText('有新回复')).toBeNull()
  expect(state.saves).toHaveLength(0)
})

test('whitespace and over-limit text are blocked while Unicode code points count once', async () => {
  setup(); await ready()
  fireEvent.change(input(), { target: { value: '  ' } }); expect(send().disabled).toBe(true)
  fireEvent.change(input(), { target: { value: '墨'.repeat(8001) } }); expect(send().disabled).toBe(true)
  fireEvent.change(input(), { target: { value: '😀'.repeat(8000) } }); expect(send().disabled).toBe(false)
})

test('missing model metadata recovers without losing the input', async () => {
  vi.useFakeTimers()
  let available = false
  await act(async () => { setup({ capabilities: () => ({ modelMetadataAvailable: available }) }) })
  expect(screen.getByText('模型信息暂不可用，请稍后重试；章节和设定仍可编辑。')).toBeTruthy()
  fireEvent.change(input(), { target: { value: '恢复后发送' } }); expect(send().disabled).toBe(true)
  available = true
  await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
  expect(send().disabled).toBe(false)
  expect(input().value).toBe('恢复后发送')
})

test('a later chat reply preserves the explicitly opened proposal and saved suggestion', async () => {
  const state = setup({ provideProposal: true }); await ready('修改本章')
  fireEvent.click(send()); fireEvent.click(await screen.findByRole('button', { name: '在正文中查看' }))
  fireEvent.click(await screen.findByRole('button', { name: '应用全部' }))
  fireEvent.change(input(), { target: { value: '为什么这样改？' } }); fireEvent.click(send())
  await waitFor(() => expect(state.writes).toHaveLength(2))
  await waitFor(() => expect(document.querySelectorAll('.conversationMessage.assistant')).toHaveLength(2))
  expect(screen.getByRole('textbox', { name: '修改 1 后的正文' }).value).toBe('改写正文')
  expect(state.saves).toHaveLength(1)
  expect(state.saves[0]).toMatchObject({ content: '改写正文',
    proposalReviews: [{ proposalId: 'q-r1', decisions: [{ itemId: 0, status: 'APPLIED' }] }] })
  expect(screen.getByRole('button', { name: '定位正文修改' })).toBeTruthy()
})

test('opening historical advice in another chapter respects the unsaved draft decision', async () => {
  const state = setup({ provideProposal: true }); await ready('修改本章')
  fireEvent.click(send()); await screen.findByRole('button', { name: '在正文中查看' })
  fireEvent.click(screen.getByRole('button', { name: /第二章.*v1/ })); await screen.findByRole('heading', { name: '第二章' })
  fireEvent.change(screen.getByRole('textbox', { name: '章节正文', exact: true }), { target: { value: '第二章的新草稿' } })
  const prompt = vi.spyOn(window, 'prompt').mockReturnValue('取消')
  fireEvent.click(screen.getByRole('button', { name: '在正文中查看' }))
  await waitFor(() => expect(prompt).toHaveBeenCalledTimes(1))
  expect(window.location.pathname).toBe('/projects/p/chapters/c2')
  expect(screen.getByRole('textbox', { name: '章节正文', exact: true }).value).toBe('第二章的新草稿')
  prompt.mockReturnValue('保存')
  fireEvent.click(screen.getByRole('button', { name: '在正文中查看' }))
  await screen.findByRole('textbox', { name: '修改 1 建议（应用前可编辑）' })
  expect(window.location.pathname).toBe('/projects/p/chapters/c')
  expect(state.chapters[1].content).toBe('第二章的新草稿')
})

test('refresh restores an unsent message draft without creating an empty conversation', async () => {
  const state = setup(); await ready('刷新后仍在')
  fireEvent.click(screen.getByRole('button', { name: '移除章节参考' }))
  cleanup(); render(<App />)
  await screen.findByRole('textbox', { name: '章节正文', exact: true })
  expect(input().value).toBe('刷新后仍在')
  expect(screen.getByRole('button', { name: '使用当前章节' })).toBeTruthy()
  expect(state.creates).toHaveLength(0)
})

test('empty composer has only icon actions and does not offer shortcuts or empty message expansion', async () => {
  const state = setup()
  await screen.findByRole('textbox', { name: '章节正文', exact: true })
  for (const name of ['梳理人物动机', '检查前后矛盾', '改进章节表达', '展开消息']) {
    expect(screen.queryByRole('button', { name })).toBeNull()
  }
  expect(screen.getAllByRole('button', { name: '新对话', exact: true })).toHaveLength(1)
  expect(screen.getByRole('button', { name: '引用 Skill' }).querySelector('svg')).toBeTruthy()
  expect(screen.queryByRole('combobox', { name: '本轮任务' })).toBeNull()
  expect(input().value).toBe('')
  expect(state.creates).toHaveLength(0); expect(state.writes).toHaveLength(0)
})

test('history dialog lists all conversations and renames without switching drafts', async () => {
  const state = setup({ initial: [{ id: 'a' }, { id: 'b', updatedAt: '2026-10-12T03:00:00Z' }] })
  await ready('保留当前草稿')
  fireEvent.click(screen.getByRole('button', { name: '历史对话' }))
  const dialog = within(screen.getByRole('dialog', { name: '历史对话' }))
  expect(dialog.getByRole('button', { name: '打开对话：讨论 0' }).getAttribute('aria-current')).toBe('true')
  expect(dialog.getByText(/更新于/)).toBeTruthy()
  fireEvent.click(dialog.getByRole('button', { name: '更多操作：讨论 1' }))
  fireEvent.click(dialog.getByRole('button', { name: '重命名' }))
  fireEvent.change(dialog.getByRole('textbox', { name: '对话标题' }), { target: { value: '人物讨论' } })
  fireEvent.click(dialog.getByRole('button', { name: '保存', exact: true }))
  await dialog.findByRole('button', { name: '打开对话：人物讨论' })
  expect(state.conversations.b.title).toBe('人物讨论')
  expect(input().value).toBe('保留当前草稿')
  expect(document.querySelector('.conversationName').textContent).toBe('讨论 0')
  expect(dialog.queryByRole('tab')).toBeNull()
  expect(dialog.getAllByRole('button', { name: /打开对话：/ })).toHaveLength(2)
  const listRequests = fetch.mock.calls.filter(([url]) => url.includes('/projects/p/conversations?'))
  expect(listRequests.length).toBeGreaterThan(0)
  expect(listRequests.every(([url]) => !new URL(url, 'http://localhost').searchParams.has('status'))).toBe(true)
  fireEvent.click(dialog.getByRole('button', { name: '关闭' }))
  expect(input().value).toBe('保留当前草稿')
  expect(send().disabled).toBe(false)
})

test('history loads further pages and recovers from a list error while retaining the composer draft', async () => {
  let broken = false
  setup({ initial: [{ id: 'a' }, { id: 'b' }], listGet: (url, conversations) => {
    if (broken) throw new TypeError('历史加载失败')
    return new URL(url, 'http://localhost').searchParams.get('cursor')
      ? reply({ items: [conversations.b], nextCursor: null })
      : reply({ items: [conversations.a], nextCursor: 'next' })
  } })
  await ready('正在写的草稿')
  fireEvent.click(screen.getByRole('button', { name: '历史对话' }))
  const dialog = within(screen.getByRole('dialog', { name: '历史对话' }))
  fireEvent.click(await dialog.findByRole('button', { name: '加载更多对话' }))
  await dialog.findByRole('button', { name: '打开对话：讨论 1' })
  expect(dialog.getByRole('button', { name: '打开对话：讨论 0' })).toBeTruthy()
  fireEvent.click(dialog.getByRole('button', { name: '关闭' }))
  broken = true
  fireEvent.click(screen.getByRole('button', { name: '历史对话' }))
  await screen.findByRole('alert')
  broken = false
  fireEvent.click(screen.getByRole('button', { name: '重试加载' }))
  await waitFor(() => expect(screen.queryByRole('alert')).toBeNull())
  expect(input().value).toBe('正在写的草稿')
})

test('history refreshes a conflicting row version and permits retrying rename', async () => {
  let attempts = 0
  setup({ initial: [{ id: 'a' }, { id: 'b' }], patchPost: (body, c) => {
    if (++attempts === 1) { c.version++; return failure('CONVERSATION_VERSION_CONFLICT') }
    expect(body.expectedVersion).toBe(c.version)
    Object.assign(c, body); c.version++
    return reply({ ...c })
  } })
  await ready()
  fireEvent.click(screen.getByRole('button', { name: '历史对话' }))
  const dialog = within(screen.getByRole('dialog', { name: '历史对话' }))
  fireEvent.click(dialog.getByRole('button', { name: '更多操作：讨论 1' }))
  fireEvent.click(dialog.getByRole('button', { name: '重命名' }))
  fireEvent.change(dialog.getByRole('textbox', { name: '对话标题' }), { target: { value: '新的标题' } })
  fireEvent.click(dialog.getByRole('button', { name: '保存', exact: true }))
  await dialog.findByRole('alert')
  await waitFor(() => expect(dialog.getByRole('button', { name: '保存', exact: true }).disabled).toBe(false))
  fireEvent.click(dialog.getByRole('button', { name: '保存', exact: true }))
  await dialog.findByRole('button', { name: '打开对话：新的标题' })
  expect(attempts).toBe(2)
})

test('running history rows block management and history closes with Escape', async () => {
  setup({ initial: [{ id: 'a' }, { id: 'b', activeRunId: 'running' }] })
  await ready()
  fireEvent.click(screen.getByRole('button', { name: '历史对话' }))
  expect(screen.getByRole('button', { name: '更多操作：讨论 1' }).disabled).toBe(true)
  expect(screen.getByRole('button', { name: '打开对话：讨论 1' }).disabled).toBe(false)
  fireEvent.keyDown(document.activeElement, { key: 'Escape' })
  expect(screen.queryByRole('dialog', { name: '历史对话' })).toBeNull()
})

const characterSkill = { skill: { id: 's1', name: '人物检查', enabled: true, currentVersionId: 'v1' }, currentVersion: { status: 'READY', metadata: { description: '检查人物行为与设定的一致性' } } }

test('the picker inserts a message reference without pins, version selection, or a discovery switch', async () => {
  const state = setup({ skillItems: [characterSkill] })
  await ready('检查人物')
  fireEvent.click(screen.getByRole('button', { name: '引用 Skill', exact: true }))
  const dialog = within(screen.getByRole('dialog', { name: '引用 Skill' }))
  expect(dialog.queryByRole('checkbox')).toBeNull()
  expect(dialog.queryByRole('combobox')).toBeNull()
  expect(dialog.queryByRole('button', { name: 'Skill 库' })).toBeNull()
  fireEvent.click(await dialog.findByRole('button', { name: '引用 人物检查' }))
  expect(input().value).toContain('$人物检查')
  expect(screen.getByRole('button', { name: '移除 Skill 引用：人物检查' })).toBeTruthy()
  fireEvent.click(send())
  await screen.findByText('已核对正文')
  expect(state.writes[0].skillMentions).toEqual([{ skillId: 's1', name: '人物检查' }])
  expect(state.writes[0].selectedSkills).toBeUndefined()
  expect(screen.queryByRole('button', { name: '移除 Skill 引用：人物检查' })).toBeNull()
  expect(fetch.mock.calls.some(([url]) => /\/conversations\/[^/]+\/skills/.test(url))).toBe(false)
  expect(fetch.mock.calls.some(([, options]) => options?.method === 'PATCH')).toBe(false)
})

test('dollar autocomplete uses Enter to insert a reference before sending and removal clears the request reference', async () => {
  const state = setup({ skillItems: [characterSkill] })
  await ready('使用 $人物')
  await screen.findByRole('option', { name: /人物检查/ })
  fireEvent.keyDown(input(), { key: 'Enter' })
  expect(state.writes).toHaveLength(0)
  expect(input().value).toBe('使用 $人物检查 ')
  fireEvent.click(screen.getByRole('button', { name: '移除 Skill 引用：人物检查' }))
  expect(input().value).not.toContain('$人物检查')
  fireEvent.change(input(), { target: { value: '继续分析' } })
  fireEvent.click(send())
  await screen.findByText('已核对正文')
  expect(state.writes[0].skillMentions).toEqual([])
})

test('global settings preserves unsaved chapter and conversation drafts and focuses its opener on close', async () => {
  setup({ skillItems: [characterSkill] })
  await ready('保留对话草稿')
  const editor = screen.getByRole('textbox', { name: '章节正文', exact: true })
  fireEvent.change(editor, { target: { value: '保留正文草稿' } })
  const opener = screen.getByRole('button', { name: '设置', exact: true })
  opener.focus(); fireEvent.click(opener)
  const settings = within(screen.getByRole('dialog', { name: '设置', exact: true }))
  await settings.findByText('人物检查')
  expect(settings.getByRole('button', { name: '添加 Skill' })).toBeTruthy()
  expect(screen.queryByRole('checkbox', { name: '允许自动发现其他 Skill' })).toBeNull()
  fireEvent.click(settings.getByRole('button', { name: '关闭设置' }))
  expect(editor.value).toBe('保留正文草稿')
  expect(input().value).toBe('保留对话草稿')
  expect(document.activeElement).toBe(opener)
})
