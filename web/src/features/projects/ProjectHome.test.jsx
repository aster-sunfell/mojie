// @vitest-environment jsdom
import React from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { ProjectHome } from './ProjectHome.jsx'

const project = (id, title) => ({ id, title, updatedAt: '2026-09-01T00:00:00Z' })
const reply = data => ({ ok: true, status: 200, json: async () => ({ data }) })
const page = (items, nextCursor = null) => reply({ items, nextCursor })

afterEach(() => { cleanup(); vi.restoreAllMocks() })

test('search queries all projects independently of creation and keeps its keyword during pagination', async () => {
  const open = vi.fn()
  globalThis.fetch = vi.fn(url => {
    if (url === '/api/v1/projects?limit=20') return Promise.resolve(page([project('p', '当前页作品')], 'all-page-two'))
    if (url === '/api/v1/projects?limit=20&keyword=%E6%9E%97%E8%88%9F')
      return Promise.resolve(page([project('match', '林舟第一卷')], 'search-page-two'))
    if (url === '/api/v1/projects?limit=20&keyword=%E6%9E%97%E8%88%9F&cursor=search-page-two')
      return Promise.resolve(page([project('match2', '林舟第二卷')]))
    throw new Error(`Unexpected request ${url}`)
  })
  render(<ProjectHome open={open} />)
  await screen.findByText('当前页作品')
  const search = screen.getByRole('searchbox', { name: '搜索作品名称' })
  expect(search.closest('form')).toBeNull()
  fireEvent.change(search, { target: { value: '林舟' } })
  fireEvent.keyDown(search, { key: 'Enter' })
  await screen.findByText('林舟第一卷')
  expect(screen.queryByText('当前页作品')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: '加载更多作品' }))
  await screen.findByText('林舟第二卷')
  expect(screen.getByText('林舟第一卷')).toBeTruthy()
  expect(open).not.toHaveBeenCalled()
  expect(fetch.mock.calls.every(([, options]) => options.method === 'GET')).toBe(true)
})

test('a late previous search cannot replace the current results', async () => {
  let finishOldSearch
  const oldSearch = new Promise(resolve => { finishOldSearch = resolve })
  globalThis.fetch = vi.fn(url => {
    const keyword = new URL(url, 'http://localhost').searchParams.get('keyword')
    if (!keyword) return Promise.resolve(page([]))
    if (keyword === '旧') return oldSearch
    if (keyword === '新') return Promise.resolve(page([project('new', '新作品')]))
    throw new Error(`Unexpected request ${url}`)
  })
  render(<ProjectHome open={vi.fn()} />)
  const search = screen.getByRole('searchbox')
  fireEvent.change(search, { target: { value: '旧' } })
  await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes('keyword=%E6%97%A7'))).toBe(true))
  fireEvent.change(search, { target: { value: '新' } })
  await screen.findByText('新作品')
  await act(async () => { finishOldSearch(page([project('old', '旧作品')], 'old-cursor')) })
  expect(screen.queryByText('旧作品')).toBeNull()
  expect(screen.queryByRole('button', { name: '加载更多作品' })).toBeNull()
})

test('creation uses its own title, blocks repeated submits, and opens the new project', async () => {
  let finishCreate
  const pending = new Promise(resolve => { finishCreate = resolve })
  const open = vi.fn()
  globalThis.fetch = vi.fn((url, options) => options.method === 'POST'
    ? pending : Promise.resolve(page([project('p', '现有作品')])))
  render(<ProjectHome open={open} />)
  await screen.findByText('现有作品')
  const search = screen.getByRole('searchbox')
  fireEvent.change(search, { target: { value: '搜索词' } })
  fireEvent.click(screen.getByRole('button', { name: '新建作品' }))
  const dialog = screen.getByRole('dialog', { name: '新建作品' })
  const title = within(dialog).getByRole('textbox', { name: '作品名称' })
  expect(title.value).toBe('')
  expect(document.activeElement).toBe(title)
  fireEvent.change(title, { target: { value: '  新作品  ' } })
  fireEvent.submit(title.closest('form'))
  fireEvent.submit(title.closest('form'))
  fireEvent.keyDown(window, { key: 'Escape' })
  expect(screen.getByRole('dialog')).toBeTruthy()
  const writes = fetch.mock.calls.filter(([, options]) => options.method === 'POST')
  expect(writes).toHaveLength(1)
  expect(JSON.parse(writes[0][1].body)).toEqual({ title: '新作品' })
  await act(async () => { finishCreate(reply(project('new', '新作品'))) })
  expect(open).toHaveBeenCalledWith('new')
  expect(search.value).toBe('搜索词')
})

test('creation failure keeps the entered title and allows retry', async () => {
  const open = vi.fn()
  let writes = 0
  globalThis.fetch = vi.fn((url, options) => {
    if (options.method !== 'POST') return Promise.resolve(page([project('p', '现有作品')]))
    writes++
    return Promise.resolve(writes === 1
      ? { ok: false, status: 500, json: async () => ({ message: '创建失败' }) }
      : reply(project('new', '新作品')))
  })
  render(<ProjectHome open={open} />)
  await screen.findByText('现有作品')
  fireEvent.click(screen.getByRole('button', { name: '新建作品' }))
  const title = screen.getByRole('textbox', { name: '作品名称' })
  fireEvent.change(title, { target: { value: '新作品' } })
  fireEvent.click(screen.getByRole('button', { name: '创建作品' }))
  await screen.findByRole('alert')
  expect(title.value).toBe('新作品')
  fireEvent.click(screen.getByRole('button', { name: '创建作品' }))
  await waitFor(() => expect(open).toHaveBeenCalledWith('new'))
  expect(writes).toBe(2)
})

test('cancelling creation restores focus and does not send a write', async () => {
  globalThis.fetch = vi.fn(() => Promise.resolve(page([project('p', '现有作品')])))
  render(<ProjectHome open={vi.fn()} />)
  await screen.findByText('现有作品')
  const trigger = screen.getByRole('button', { name: '新建作品' })
  trigger.focus()
  fireEvent.click(trigger)
  fireEvent.keyDown(window, { key: 'Escape' })
  expect(screen.queryByRole('dialog')).toBeNull()
  expect(document.activeElement).toBe(trigger)
  expect(fetch.mock.calls.every(([, options]) => options.method === 'GET')).toBe(true)
})
