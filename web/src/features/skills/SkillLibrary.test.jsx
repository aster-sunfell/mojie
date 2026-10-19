// @vitest-environment jsdom
import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { SkillLibrary } from './SkillLibrary.jsx'

const reply = data => ({ ok: true, status: 200, json: async () => ({ data }) })
const body = '---\nname: 人物检查\ndescription: 检查人物一致性\n---\n先读取人物设定。'

function setup() {
  const item = { skill: { id: 's', name: '人物检查', enabled: false, currentVersionId: 'v', version: 1 },
    currentVersion: { id: 'v', metadata: { name: '人物检查', description: '检查人物一致性' } } }
  const onDirtyChange = vi.fn()
  vi.stubGlobal('fetch', vi.fn(async (url, options = {}) => {
    if (url === '/api/v1/skills/import') return reply({ id: 'upload', skillId: 's', status: 'READY' })
    if (url === '/api/v1/skills/capabilities') return reply({ maxRawUploadBytes: 20971520, maxFiles: 500, mode: 'CONTEXT_ONLY', scriptExecution: false })
    if (url.startsWith('/api/v1/skills?')) return reply({ items: [item], nextCursor: null })
    if (url === '/api/v1/skills/s') return reply(item)
    if (url.endsWith('/files/body')) return { ok: true, arrayBuffer: async () => new TextEncoder().encode(body).buffer }
    if (url.endsWith('/versions?limit=50')) return reply({ items: [{ ...item.currentVersion, createdAt: '2026-10-19' }], nextCursor: null })
    if (url === '/api/v1/skills/s/versions/v') return reply({ version: { ...item.currentVersion,
      compatibility: { warnings: ['本项目不执行脚本，文件仅保留供预览和导出。'], scripts: ['scripts/check.py'] } },
      files: [{ id: 'body', relativePath: 'SKILL.md', mime: 'text/markdown', sizeBytes: 100 }] })
    throw new Error(`Unexpected request ${url} ${options.method}`)
  }))
  render(<SkillLibrary onDirtyChange={onDirtyChange} />)
  return { onDirtyChange }
}

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

test('guide shares the published template and returning preserves search, import and unsaved edits', async () => {
  const writeText = vi.fn().mockResolvedValue(undefined)
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
  vi.stubGlobal('requestAnimationFrame', callback => { callback(); return 1 })
  const { onDirtyChange } = setup()
  fireEvent.click(await screen.findByRole('button', { name: /人物检查/ }))
  await screen.findByText('先读取人物设定。')
  fireEvent.change(screen.getByRole('searchbox'), { target: { value: '人物' } })
  fireEvent.click(screen.getByRole('button', { name: '添加 Skill' }))
  fireEvent.click(screen.getByRole('button', { name: '编辑并发布新版本' }))
  fireEvent.change(screen.getByLabelText('Skill 文件内容'), { target: { value: body + '\n未保存修改' } })
  const opener = screen.getByRole('button', { name: '如何创建 Skill →' })
  fireEvent.click(opener)
  expect(screen.getByRole('heading', { name: '如何创建 Skill' })).toBeTruthy()
  expect(screen.queryByRole('searchbox')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: '复制示例 Skill' }))
  await screen.findByRole('status')
  expect(writeText.mock.calls[0][0]).toMatch(/^---\nname: 人物一致性审稿/)
  expect(writeText.mock.calls[0][0]).toContain('read_chapter')
  fireEvent.click(screen.getByRole('button', { name: '返回 Skill 库' }))
  expect(screen.getByRole('searchbox').value).toBe('人物')
  expect(screen.getByLabelText('导入 Markdown / ZIP')).toBeTruthy()
  expect(screen.getByLabelText('Skill 文件内容').value).toBe(body + '\n未保存修改')
  expect(onDirtyChange).toHaveBeenLastCalledWith(true)
  expect(document.activeElement).toBe(opener)
})

test('import shows package limitations without requiring another confirmation', async () => {
  setup()
  const confirm = vi.spyOn(window, 'confirm')
  fireEvent.click(screen.getByRole('button', { name: '添加 Skill' }))
  fireEvent.change(screen.getByLabelText('导入 Markdown / ZIP'), { target: { files: [new File([body], 'SKILL.md', { type: 'text/markdown' })] } })
  const limitation = await screen.findByRole('note', { name: '此 Skill 的能力限制' })
  expect(limitation.textContent).toContain('scripts/check.py')
  expect(limitation.textContent).toContain('不执行脚本')
  expect(confirm).not.toHaveBeenCalled()
  await waitFor(() => expect(screen.getByRole('button', { name: '启用', exact: true }).disabled).toBe(false))
})
