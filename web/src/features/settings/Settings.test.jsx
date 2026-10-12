// @vitest-environment jsdom
import React from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { Settings } from './Settings.jsx'

const initial = [
  { id: 's1', type: 'CHARACTER', title: '林舟', content: '不会游泳。' },
  { id: 's2', type: 'WORLD', title: '山谷', content: '河流湍急。' },
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
