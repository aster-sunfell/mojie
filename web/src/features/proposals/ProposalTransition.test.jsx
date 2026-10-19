// @vitest-environment jsdom
import React from 'react'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { Proposal } from './Proposal.jsx'

const base = '前文。\n旧文。\n尾声。'
const start = base.indexOf('旧文')
const item = { id: 0, start, end: start + 2, before: '旧文', after: '新文', lineNumber: 2,
  contextBefore: base.slice(0, start), contextAfter: base.slice(start + 2) }
function props({ status = 'PENDING', unsaved = false, failed = false, draft, notice = '', ...overrides } = {}) {
  return { draft: draft ?? (status === 'APPLIED' ? base.replace('旧文', '新文') : base), busy: false,
    onDraftChange: vi.fn(), review: { proposal: { id: 'q', baseContent: base, status },
      items: [{ ...item, status, unsaved, savedVersion: unsaved ? null : 2 }],
      saveFailed: failed, saving: unsaved && !failed, notice, loading: false,
      apply: vi.fn(), ignore: vi.fn(), retrySave: vi.fn(), ...overrides } }
}
const row = () => document.querySelector('[data-change-id="0"]')
afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

test('only a committed change collapses, keeps the same input and fades into ordinary text', () => {
  vi.useFakeTimers()
  const view = render(<Proposal {...props()} />)
  view.rerender(<Proposal {...props({ status: 'APPLIED', unsaved: true })} />)
  const input = screen.getByRole('textbox', { name: '修改 1 后的正文' })
  input.focus(); input.setSelectionRange(1, 1)
  act(() => { vi.advanceTimersByTime(1500) })
  expect(row().dataset.phase).toBe('review')
  expect(document.querySelector('.diffBefore')).not.toBeNull()
  const saved = props({ status: 'APPLIED', notice: '1 处已应用。' })
  view.rerender(<Proposal {...saved} />)
  expect(row().dataset.phase).toBe('collapsing')
  fireEvent.change(input, { target: { value: '新文。继续输入。', selectionStart: 9, selectionEnd: 9 } })
  expect(saved.onDraftChange).toHaveBeenCalledWith('前文。\n新文。继续输入。\n尾声。')
  act(() => { vi.advanceTimersByTime(200) })
  expect(row().dataset.phase).toBe('highlight')
  expect(document.querySelector('.diffBefore')).toBeNull()
  expect(document.querySelector('.changeHeader')).toBeNull()
  expect(screen.getByRole('textbox', { name: '修改 1 后的正文' })).toBe(input)
  expect(document.activeElement).toBe(input)
  act(() => { vi.advanceTimersByTime(1000) })
  expect(row().dataset.phase).toBe('plain')
  expect(document.querySelector('.inlineChange')).toBeNull()
  expect(document.querySelector('.diffAfter')).toBeNull()
  expect(document.querySelector('.appliedHighlight')).toBeNull()
  act(() => { vi.advanceTimersByTime(1000) })
  expect(screen.queryByRole('status')).toBeNull()
  expect(screen.getByRole('button', { name: '查看修改记录（1）' })).toBeTruthy()
})

test('failed applications retain the comparison and retry action regardless of elapsed time', () => {
  vi.useFakeTimers()
  const failed = props({ status: 'APPLIED', unsaved: true, failed: true })
  render(<Proposal {...failed} />)
  act(() => { vi.advanceTimersByTime(5000) })
  expect(row().dataset.phase).toBe('review')
  expect(within(row()).getByText('保存失败')).toBeTruthy()
  expect(document.querySelector('.diffBefore')).not.toBeNull()
  fireEvent.click(screen.getByRole('button', { name: '重试保存修改' }))
  expect(failed.review.retrySave).toHaveBeenCalledTimes(1)
  expect(screen.queryByRole('button', { name: /查看修改记录/ })).toBeNull()
})

test('ignoring a change collapses to the original text without a green highlight', () => {
  vi.useFakeTimers()
  const view = render(<Proposal {...props()} />)
  view.rerender(<Proposal {...props({ status: 'IGNORED', notice: '1 处已忽略。' })} />)
  expect(row().dataset.phase).toBe('collapsing')
  act(() => { vi.advanceTimersByTime(200) })
  expect(row().dataset.phase).toBe('plain')
  expect(document.querySelector('.appliedHighlight')).toBeNull()
  expect(screen.getByRole('textbox', { name: '修改 1 当前正文' }).value).toBe('旧文。')
  expect(document.querySelector('.changeHeader')).toBeNull()
})

test('reopened saved proposals show ordinary text immediately and comparisons only in the history dialog', () => {
  const saved = props({ status: 'APPLIED', draft: base.replace('旧文', '新文和后续编辑') })
  render(<Proposal {...saved} />)
  expect(row().dataset.phase).toBe('plain')
  expect(document.querySelector('.diffBefore')).toBeNull()
  const opener = screen.getByRole('button', { name: '查看修改记录（1）' })
  opener.focus(); fireEvent.click(opener)
  const dialog = within(screen.getByRole('dialog', { name: '修改记录' }))
  expect(dialog.getByLabelText('记录 1 原文').textContent).toBe('旧文。')
  expect(dialog.getByLabelText('记录 1 建议').textContent).toBe('新文。')
  expect(dialog.queryByRole('textbox')).toBeNull()
  fireEvent.click(dialog.getByRole('button', { name: '关闭' }))
  expect(document.activeElement).toBe(opener)
  expect(screen.getByRole('textbox', { name: '修改 1 后的正文' }).value).toBe('新文和后续编辑。')
  expect(row().dataset.phase).toBe('plain')
})

test('reduced motion switches directly to ordinary text and still announces success', () => {
  vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener() {}, removeEventListener() {} }))
  const view = render(<Proposal {...props()} />)
  view.rerender(<Proposal {...props({ status: 'APPLIED', notice: '1 处已应用。' })} />)
  expect(row().dataset.phase).toBe('plain')
  expect(document.querySelector('.diffBefore')).toBeNull()
  expect(document.querySelector('.appliedHighlight')).toBeNull()
  expect(screen.getByRole('status').textContent).toBe('1 处已应用。')
})

test('deleting the whole chapter leaves an editable empty document and a readable deletion record', () => {
  const deleted = props({ status: 'APPLIED', draft: '' })
  deleted.review.proposal.baseContent = '删除全文。'
  deleted.review.items = [{ id: 0, start: 0, end: 5, before: '删除全文。', after: '',
    contextBefore: '', contextAfter: '', lineNumber: 1, status: 'APPLIED', savedVersion: 2 }]
  render(<Proposal {...deleted} />)
  const input = screen.getByRole('textbox', { name: '修改 1 后的正文' })
  expect(input.value).toBe('')
  expect(row().dataset.phase).toBe('plain')
  fireEvent.change(input, { target: { value: '重新写作。' } })
  expect(deleted.onDraftChange).toHaveBeenCalledWith('重新写作。')
  fireEvent.click(screen.getByRole('button', { name: '查看修改记录（1）' }))
  expect(within(screen.getByRole('dialog')).getByLabelText('记录 1 建议').textContent).toBe('（删除此处文字）')
})
