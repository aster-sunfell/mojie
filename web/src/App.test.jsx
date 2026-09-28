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

test('chapter navigation loads the next page without losing the first page', async () => {
  window.history.replaceState({}, '', '/projects/p/chapters/c')
  const later = { ...chapter, id: 'c51', chapterOrder: 51, title: '第五十一章' }
  globalThis.fetch = vi.fn((url) => {
    if (url === '/api/v1/projects/p') return Promise.resolve(reply(project))
    if (url === '/api/v1/projects/p/chapters?limit=50')
      return Promise.resolve(reply({ items: [chapter], nextCursor: 'page-two' }))
    if (url === '/api/v1/projects/p/chapters?limit=50&cursor=page-two')
      return Promise.resolve(reply({ items: [later], nextCursor: null }))
    if (url === '/api/v1/chapters/c') return Promise.resolve(reply(chapter))
    if (url.startsWith('/api/v1/agent/runs?')) return Promise.resolve(reply({ items: [], nextCursor: null }))
    if (url === '/api/v1/system/capabilities') return Promise.resolve(reply({ agentConfigured: false }))
    throw new Error(`Unexpected request ${url}`)
  })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: '加载更多章节' }))
  await screen.findByRole('button', { name: /第五十一章/ })
  expect(screen.getByRole('button', { name: /第一章/ })).toBeTruthy()
  expect(screen.queryByRole('button', { name: '加载更多章节' })).toBeNull()
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
