// @vitest-environment jsdom
import React from 'react'
import { cleanup, render, screen, within } from '@testing-library/react'
import { afterEach, expect, test } from 'vitest'
import { Markdown } from './Markdown.jsx'
import { RunResult } from '../../features/agent/RunResult.jsx'

afterEach(cleanup)

test('renders analysis paragraphs, emphasis, evidence lists and quotations', () => {
  const content = '## 简要分析\n\n第二章末句与人物设定存在**明确冲突**。\n\n'
    + '- **角色档案（林舟）**：林舟不会游泳。\n- 第一章设定：遇到急流会走桥。\n\n'
    + '> 建议改为安全走到对岸。'
  const { container } = render(<Markdown content={content} />)
  expect(screen.getByRole('heading', { name: '简要分析', level: 2 })).toBeTruthy()
  expect(screen.getByText('明确冲突').tagName).toBe('STRONG')
  expect(screen.getAllByRole('listitem')).toHaveLength(2)
  expect(container.querySelector('blockquote').textContent).toContain('安全走到对岸')
  expect(container.querySelector('p p')).toBeNull()
  expect(container.textContent).not.toContain('**')
})

test('supports GFM tables, task lists, strikethrough and literal code', () => {
  const content = '| 人物 | 设定 |\n| --- | --- |\n| 林舟 | 不会游泳 |\n\n'
    + '- [x] 核对人物\n- [ ] 核对前文\n\n~~原句~~ 改为 `走到对岸`。\n\n'
    + '```html\n<script>alert("literal code")</script>\n```'
  const { container } = render(<Markdown content={content} />)
  expect(within(screen.getByRole('table')).getByRole('cell', { name: '不会游泳' })).toBeTruthy()
  const checkboxes = screen.getAllByRole('checkbox')
  expect(checkboxes.map(element => element.checked)).toEqual([true, false])
  expect(checkboxes.every(element => element.disabled)).toBe(true)
  expect(container.querySelector('del').textContent).toBe('原句')
  expect(screen.getByText('走到对岸').tagName).toBe('CODE')
  expect(screen.getByLabelText('代码块').textContent).toContain('<script>')
  expect(container.querySelector('script')).toBeNull()
})

test('skips raw HTML and rejects dangerous URLs while retaining safe links', () => {
  const content = '<script>alert("unsafe")</script>\n\n<img src=x onerror=alert(1)>\n\n'
    + '[资料](https://example.com/source) [章节](/chapters/c) [联系](mailto:author@example.com) '
    + '[危险](javascript:alert%281%29) [数据](data:text/html,attack) [脚本](vbscript:attack)'
  const { container } = render(<Markdown content={content} />)
  expect(container.querySelector('script, img, [onerror]')).toBeNull()
  const external = screen.getByRole('link', { name: '资料' })
  expect(external.getAttribute('href')).toBe('https://example.com/source')
  expect(external.getAttribute('target')).toBe('_blank')
  expect(external.getAttribute('rel')).toBe('noopener noreferrer')
  expect(screen.getByRole('link', { name: '章节' }).getAttribute('target')).toBeNull()
  expect(screen.getByRole('link', { name: '联系' }).getAttribute('href')).toBe('mailto:author@example.com')
  for (const name of ['危险', '数据', '脚本']) {
    expect(screen.queryByRole('link', { name })).toBeNull()
    expect(screen.getByText(name)).toBeTruthy()
  }
})

test('accepts empty content, plain text and updates without mutating the source', () => {
  const { container, rerender } = render(<Markdown content={null} />)
  expect(container.textContent).toBe('')
  const content = '已核对人物设定。\n\n尚未检查第三章。'
  rerender(<Markdown content={content} />)
  expect(container.querySelectorAll('p')).toHaveLength(2)
  expect(content).toBe('已核对人物设定。\n\n尚未检查第三章。')
  rerender(<Markdown content="**核对完成**" />)
  expect(screen.getByText('核对完成').tagName).toBe('STRONG')
  expect(screen.queryByText('尚未检查第三章。')).toBeNull()
})

test('renders both final analysis and proposal rationale within the writing results', () => {
  const task = { run: { status: 'SUCCEEDED', baseVersion: 3, proposalId: 'q',
    analysis: '**结论：** 存在冲突。\n\n- 林舟不会游泳。', steps: [], sources: [] } }
  const review = { proposal: { status: 'PENDING', reason: '### 修改理由\n\n改为**走桥**。' }, items: [] }
  const { container } = render(<RunResult task={task} review={review} />)
  expect(within(container.querySelector('.analysis')).getByText('结论：').tagName).toBe('STRONG')
  const proposal = within(container.querySelector('.proposal'))
  expect(proposal.getByRole('heading', { name: '修改理由' })).toBeTruthy()
  expect(proposal.getByText('走桥').tagName).toBe('STRONG')
})
