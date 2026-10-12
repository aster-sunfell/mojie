// @vitest-environment jsdom
import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { NewChapterProposal } from './NewChapterProposal.jsx'
const reply = data => ({ ok: true, status: 200, json: async () => ({ data }) })
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

test('new chapter can be edited before saving and closing protects unsaved changes', async () => {
  const writes = [], onClose = vi.fn(), onSaved = vi.fn(), onStatusChange = vi.fn()
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  vi.stubGlobal('fetch', vi.fn(async (url, options) => {
    if (options.method === 'GET') return reply({ id: 'd', operation: 'CREATE', targetType: 'CHAPTER', status: 'PENDING', proposedTitle: '出关', proposedContent: '草稿正文' })
    writes.push({ url, body: JSON.parse(options.body) }); return reply({ status: 'APPLIED', targetChapterId: 'chapter', appliedVersion: 1 })
  }))
  render(<NewChapterProposal proposalId="d" onClose={onClose} onSaved={onSaved} onStatusChange={onStatusChange} />)
  await screen.findByLabelText('章节正文')
  expect(writes).toHaveLength(0)
  fireEvent.change(screen.getByLabelText('章节正文'), { target: { value: '作者调整后的首版正文' } })
  fireEvent.click(screen.getByRole('button', { name: '关闭', exact: true }))
  expect(confirm).toHaveBeenCalled(); expect(onClose).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '保存新章节' }))
  await waitFor(() => expect(onSaved).toHaveBeenCalledWith('chapter'))
  expect(writes).toEqual([{ url: '/api/v1/edit-proposals/d/chapter', body: { title: '出关', content: '作者调整后的首版正文' } }])
  expect(onStatusChange).toHaveBeenCalledWith('d', 'APPLIED')
})
