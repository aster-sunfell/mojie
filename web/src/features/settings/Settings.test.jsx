// @vitest-environment jsdom
import React from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { Settings } from './Settings.jsx'

const initial = [
  { id: 's1', type: 'CHARACTER', title: '林舟', content: '不会游泳。', version: 1 },
  { id: 's2', type: 'WORLD', title: '山谷', content: '河流湍急。', version: 1 },
]
const reply = data => ({ ok: true, status: 200, json: async () => ({ data }) })

function setup({ disabled = false, write } = {}) {
  const items = initial.map(item => ({ ...item }))
  const onClose = vi.fn()
  const writes = []
  vi.stubGlobal('fetch', vi.fn(async (url, options = {}) => {
    if (options.method === 'GET') {
      const params = new URL(url, 'http://localhost').searchParams
      return reply({ items: items.filter(item => (!params.get('type') || item.type === params.get('type'))
        && (!params.get('keyword') || `${item.title}${item.content}`.includes(params.get('keyword')))), nextCursor: null })
    }
    writes.push({ url, ...options })
    if (write) return write(url, options)
    const body = options.body ? JSON.parse(options.body) : null
    if (options.method === 'POST') {
      const created = { id: 's3', ...body }; items.push(created); return reply(created)
    }
    if (options.method === 'PATCH') {
      const selected = items.find(item => url.endsWith(`/${item.id}`))
      Object.assign(selected, body); return reply(selected)
    }
    if (options.method === 'DELETE') {
      items.splice(items.findIndex(item => url.endsWith(`/${item.id}`)), 1)
      return { ok: true, status: 204 }
    }
    throw new Error(`Unexpected request ${url}`)
  }))
  const result = render(<Settings projectId="p" disabled={disabled} onClose={onClose} />)
  return { ...result, onClose, writes }
}

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

async function editFirst() {
  await screen.findByRole('heading', { name: '林舟' })
  fireEvent.click(screen.getByRole('button', { name: '编辑设定' }))
  fireEvent.change(screen.getByLabelText('设定内容'), { target: { value: '新增的人物经历。' } })
}

test('closing, selecting and cancelling keep unsaved edits when discard is declined', async () => {
  const { onClose } = setup()
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  await editFirst()
  fireEvent.click(screen.getByRole('button', { name: '关闭作品设定' }))
  fireEvent.keyDown(window, { key: 'Escape' })
  fireEvent.click(within(screen.getByLabelText('设定列表')).getByRole('button', { name: /山谷/ }))
  fireEvent.click(screen.getByRole('button', { name: '取消', exact: true }))
  expect(confirm).toHaveBeenCalledTimes(4)
  expect(onClose).not.toHaveBeenCalled()
  expect(screen.getByLabelText('设定内容').value).toBe('新增的人物经历。')
  confirm.mockReturnValue(true)
  fireEvent.keyDown(window, { key: 'Escape' })
  expect(onClose).toHaveBeenCalledOnce()
})

test('save shortcut updates once, blocks closing during the request and leaves the dialog open', async () => {
  let finish
  const response = new Promise(resolve => { finish = resolve })
  const { onClose, writes } = setup({ write: () => response })
  await editFirst()
  fireEvent.keyDown(window, { key: 's', ctrlKey: true })
  fireEvent.keyDown(window, { key: 's', metaKey: true })
  fireEvent.keyDown(window, { key: 'Escape' })
  expect(writes).toHaveLength(1)
  expect(writes[0].url).toBe('/api/v1/settings/s1')
  expect(writes[0].method).toBe('PATCH')
  expect(JSON.parse(writes[0].body).content).toBe('新增的人物经历。')
  expect(JSON.parse(writes[0].body).expectedVersion).toBe(1)
  expect(onClose).not.toHaveBeenCalled()
  await act(async () => { finish(reply({ ...initial[0], content: '新增的人物经历。' })) })
  expect(await screen.findByText('设定已保存')).toBeTruthy()
  expect(screen.getByRole('dialog', { name: '作品设定' })).toBeTruthy()
  expect(screen.getByRole('heading', { name: '林舟' })).toBeTruthy()
  expect(onClose).not.toHaveBeenCalled()
})

test('failed saves preserve form values and allow retry', async () => {
  const { writes } = setup({ write: () => Promise.resolve({ ok: false, status: 409,
    json: async () => ({ code: 'RESOURCE_BUSY' }) }) })
  await editFirst()
  fireEvent.click(screen.getByRole('button', { name: '保存设定' }))
  await screen.findByRole('alert')
  expect(screen.getByLabelText('设定内容').value).toBe('新增的人物经历。')
  expect(screen.getByRole('button', { name: '保存设定' }).disabled).toBe(false)
  expect(writes).toHaveLength(1)
})

test('active tasks allow viewing and filtering while blocking modifications and shortcut saves', async () => {
  const { rerender, writes, onClose } = setup()
  await editFirst()
  rerender(<Settings projectId="p" disabled onClose={onClose} />)
  expect(screen.getByLabelText('设定内容').disabled).toBe(true)
  expect(screen.getByRole('button', { name: '新增设定' }).disabled).toBe(true)
  fireEvent.keyDown(window, { key: 's', ctrlKey: true })
  expect(writes).toHaveLength(0)
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  fireEvent.click(screen.getByRole('button', { name: '取消', exact: true }))
  expect(screen.getByRole('button', { name: '编辑设定' }).disabled).toBe(true)
  expect(screen.getByRole('button', { name: '删除设定' }).disabled).toBe(true)
  fireEvent.change(screen.getByLabelText('设定类型筛选'), { target: { value: 'WORLD' } })
  await waitFor(() => expect(within(screen.getByLabelText('设定列表')).queryByRole('button', { name: /林舟/ })).toBeNull())
  fireEvent.click(within(screen.getByLabelText('设定列表')).getByRole('button', { name: /山谷/ }))
  expect(screen.getByRole('heading', { name: '山谷' })).toBeTruthy()
  expect(fetch).toHaveBeenCalledWith('/api/v1/projects/p/settings?limit=50&type=WORLD', expect.any(Object))
})

test('creating and deleting refreshes the list without closing the dialog', async () => {
  const { writes, onClose } = setup()
  await screen.findByRole('heading', { name: '林舟' })
  fireEvent.click(screen.getByRole('button', { name: '新增设定' }))
  fireEvent.change(screen.getByLabelText('标题'), { target: { value: '写作风格' } })
  fireEvent.change(screen.getByLabelText('类型'), { target: { value: 'RULE' } })
  fireEvent.change(screen.getByLabelText('设定内容'), { target: { value: '保持第三人称叙述。' } })
  fireEvent.click(screen.getByRole('button', { name: '保存设定' }))
  await screen.findByRole('heading', { name: '写作风格' })
  expect(writes[0].method).toBe('POST')
  expect(JSON.parse(writes[0].body).type).toBe('RULE')
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  fireEvent.click(screen.getByRole('button', { name: '删除设定' }))
  await waitFor(() => expect(screen.queryByRole('heading', { name: '写作风格' })).toBeNull())
  expect(writes[1].url).toBe('/api/v1/settings/s3')
  expect(writes[1].method).toBe('DELETE')
  expect(onClose).not.toHaveBeenCalled()
})

function setupProposal({ version = 1, fail = false } = {}) {
  let setting = { id: 's1', projectId: 'p', type: 'CHARACTER', title: '林舟', content: '林舟不会游泳。', version }
  const proposal = { id: 'q', projectId: 'p', targetType: 'SETTING', targetSettingId: 's1', baseVersion: 1,
    baseType: 'CHARACTER', baseTitle: '林舟', baseContent: '林舟不会游泳。',
    proposedType: 'RULE', proposedTitle: '林舟的行动规则', proposedContent: '林舟谨慎，选择石桥过河。', reason: '调整行动动机', status: 'PENDING' }
  const writes = [], onClose = vi.fn()
  vi.stubGlobal('fetch', vi.fn(async (url, options) => {
    if (options.method === 'GET') {
      if (url.endsWith('/edit-proposals/q')) return reply(proposal)
      if (url.endsWith('/settings/s1')) return reply(setting)
      if (url.includes('/projects/p/settings')) return reply({ items: [setting], nextCursor: null })
    }
    writes.push({ url, method: options.method, body: options.body && JSON.parse(options.body) })
    if (fail) return { ok: false, status: 409, json: async () => ({ data: { status: 'CONFLICTED' } }) }
    if (url.endsWith('/setting')) {
      setting = { ...setting, ...JSON.parse(options.body), version: 2 }
      return reply({ status: 'APPLIED', appliedVersion: 2 })
    }
    if (url.endsWith('/reject')) return reply({ status: 'REJECTED' })
    throw new Error(`Unexpected request ${url}`)
  }))
  render(<Settings projectId="p" proposalId="q" onClose={onClose} />)
  return { writes, onClose }
}

test('setting proposals apply to an editable draft and save the reviewed fields once', async () => {
  const { writes, onClose } = setupProposal()
  await screen.findByRole('form', { name: '审阅设定修改' })
  expect(screen.getByLabelText('设定内容').value).toBe('林舟不会游泳。')
  fireEvent.click(screen.getByRole('button', { name: '应用全部修改' }))
  expect(writes).toHaveLength(0)
  fireEvent.change(screen.getByLabelText('设定内容'), { target: { value: '用户调整后的行动规则。' } })
  vi.spyOn(window, 'confirm').mockReturnValue(false)
  fireEvent.click(screen.getByRole('button', { name: '关闭作品设定' }))
  expect(onClose).not.toHaveBeenCalled()
  fireEvent.keyDown(window, { key: 's', ctrlKey: true })
  fireEvent.keyDown(window, { key: 's', ctrlKey: true })
  await screen.findByText('设定已保存')
  expect(writes).toEqual([{ url: '/api/v1/edit-proposals/q/setting', method: 'PUT', body: {
    type: 'RULE', title: '林舟的行动规则', content: '用户调整后的行动规则。', expectedVersion: 1 } }])
  expect(screen.getByRole('heading', { name: '林舟的行动规则' })).toBeTruthy()
})

test('ignoring an applied field restores the saved field and permits saving a partial review', async () => {
  const { writes } = setupProposal()
  await screen.findByRole('form', { name: '审阅设定修改' })
  fireEvent.click(screen.getByRole('button', { name: '应用全部修改' }))
  fireEvent.click(screen.getByRole('button', { name: '忽略类型修改' }))
  fireEvent.click(screen.getByRole('button', { name: '忽略标题修改' }))
  expect(screen.getByLabelText('类型').value).toBe('CHARACTER')
  expect(screen.getByLabelText('标题').value).toBe('林舟')
  fireEvent.click(screen.getByRole('button', { name: '保存设定' }))
  await screen.findByText('设定已保存')
  expect(writes[0].body).toEqual({ type: 'CHARACTER', title: '林舟', content: '林舟谨慎，选择石桥过河。', expectedVersion: 1 })
})

test('a stale setting proposal cannot apply or save but can be rejected without changing the setting', async () => {
  const { writes } = setupProposal({ version: 2 })
  await screen.findByRole('form', { name: '审阅设定修改' })
  expect(screen.getByRole('button', { name: '应用全部修改' }).disabled).toBe(true)
  expect(screen.getByRole('button', { name: '保存设定' }).disabled).toBe(true)
  expect(screen.getByRole('alert').textContent).toContain('设定已发生变化')
  fireEvent.click(screen.getByRole('button', { name: '忽略建议' }))
  await screen.findByText('设定建议已忽略')
  expect(writes).toEqual([{ url: '/api/v1/edit-proposals/q/reject', method: 'POST', body: undefined }])
})

test('a server conflict preserves the setting draft and blocks repeated overwrites', async () => {
  const { writes } = setupProposal({ fail: true })
  await screen.findByRole('form', { name: '审阅设定修改' })
  fireEvent.click(screen.getByRole('button', { name: '应用全部修改' }))
  fireEvent.click(screen.getByRole('button', { name: '保存设定' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '保存设定' }).disabled).toBe(true))
  expect(screen.getByLabelText('设定内容').value).toBe('林舟谨慎，选择石桥过河。')
  expect(writes).toHaveLength(1)
})

test('a CREATE proposal opens without fetching a missing target and saves reviewed new content', async () => {
  const writes = [], onProposalChange = vi.fn()
  let created = null
  vi.stubGlobal('fetch', vi.fn(async (url, options = {}) => {
    if (options.method === 'GET') {
      if (url === '/api/v1/edit-proposals/new') return reply({ id: 'new', projectId: 'p', operation: 'CREATE', targetType: 'SETTING', status: 'PENDING', baseVersion: 0,
        proposedType: 'WORLD', proposedTitle: '末法世界', proposedContent: '世界观草稿', targetSettingId: null })
      if (url.startsWith('/api/v1/projects/p/settings')) return reply({ items: created ? [created] : [], nextCursor: null })
      if (url === '/api/v1/settings/created') return reply(created)
      throw new Error(`Unexpected read ${url}`)
    }
    const body = JSON.parse(options.body)
    writes.push({ url, body })
    created = { id: 'created', ...body, version: 1 }
    return reply({ status: 'APPLIED', targetSettingId: 'created', appliedVersion: 1 })
  }))
  render(<Settings projectId="p" proposalId="new" onProposalChange={onProposalChange} onClose={vi.fn()} />)
  await screen.findByRole('heading', { name: '新建设定草稿' })
  expect(screen.getByLabelText('设定内容').value).toBe('世界观草稿')
  fireEvent.change(screen.getByLabelText('设定内容'), { target: { value: '作者审阅后的世界观' } })
  fireEvent.click(screen.getByRole('button', { name: '保存设定', exact: true }))
  await screen.findByRole('heading', { name: '末法世界' })
  expect(writes).toEqual([{ url: '/api/v1/edit-proposals/new/setting', body: { type: 'WORLD', title: '末法世界', content: '作者审阅后的世界观' } }])
  expect(onProposalChange).toHaveBeenCalledWith('new', 'APPLIED')
})
