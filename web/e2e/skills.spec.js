import { expect, test } from '@playwright/test'
import { mockWorkspace } from './fixtures/workspace.mjs'

async function mockSkills(page) {
  const items = [
    { skill: { id: 'character', name: '人物检查', enabled: true, version: 1, currentVersionId: 'v-character' }, currentVersion: { id: 'v-character', status: 'READY', metadata: { description: '检查人物行为与设定是否一致' } } },
    { skill: { id: 'outline', name: '大纲整理', enabled: false, version: 1, currentVersionId: 'v-outline' }, currentVersion: { id: 'v-outline', status: 'READY', metadata: { description: '梳理故事结构与章节节奏' } } },
  ]
  const body = item => `---\nname: ${item.skill.name}\ndescription: ${item.currentVersion.metadata.description}\n---\n\n按需检查，不读取无关章节。`
  await page.route('**/api/v1/skills**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname.replace('/api/v1', '')
    const data = value => route.fulfill({ json: { data: value } })
    if (path === '/skills/capabilities') return data({ maxRawUploadBytes: 10485760, maxFiles: 500, executor: { configured: false } })
    if (path === '/skills') return data({ items, nextCursor: null })
    if (path === '/skills/import') {
      const imported = { skill: { id: 'imported', name: '表达润色', enabled: false, version: 1, currentVersionId: 'v-imported' }, currentVersion: { id: 'v-imported', status: 'READY', metadata: { description: '调整文字表达' } } }
      items.push(imported)
      return data({ id: 'upload', skillId: 'imported', versionId: 'v-imported', status: 'READY' })
    }
    const item = items.find(item => path.startsWith(`/skills/${item.skill.id}`))
    if (!item) return route.fulfill({ status: 404, json: { code: 'RESOURCE_NOT_FOUND' } })
    if (path.endsWith('/files/body')) return route.fulfill({ contentType: 'text/markdown', body: body(item) })
    if (path.endsWith('/versions')) return data({ items: [{ ...item.currentVersion, createdAt: '2026-10-12T00:00:00Z' }], nextCursor: null })
    if (path.includes('/versions/')) return data({ version: { ...item.currentVersion, compatibility: { partialExecution: false } }, files: [{ id: 'body', relativePath: 'SKILL.md', sizeBytes: 200, mime: 'text/markdown' }] })
    if (request.method() === 'PATCH') { Object.assign(item.skill, request.postDataJSON()); item.skill.version++; return data(item.skill) }
    return data(item)
  })
  return items
}

for (const mobile of [false, true]) {
  test(`settings manages Skills while preserving drafts on ${mobile ? 'mobile' : 'desktop'}`, async ({ page }) => {
    if (mobile) await page.setViewportSize({ width: 390, height: 844 })
    await mockWorkspace(page, { history: false })
    await mockSkills(page)
    await page.goto('/projects/p/chapters/c')
    const editor = page.getByRole('textbox', { name: '章节正文', exact: true }), input = page.getByRole('textbox', { name: '对话消息' })
    await editor.fill('未保存的正文')
    await input.fill('未发送的消息')
    const opener = page.getByRole('button', { name: '设置', exact: true })
    await opener.click()
    const settings = page.getByRole('dialog', { name: '设置', exact: true })
    await expect(settings.getByRole('button', { name: /人物检查/ })).toBeVisible()
    await settings.getByRole('button', { name: /人物检查/ }).click()
    await expect(settings.getByText('按需检查，不读取无关章节。')).toBeVisible()
    await expect(settings.getByRole('checkbox')).toHaveCount(0)
    expect(await settings.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    await page.screenshot({ path: `test-results/skill-settings-${mobile ? 'mobile' : 'desktop'}.png` })
    await settings.getByRole('button', { name: '停用', exact: true }).click()
    await expect(settings.getByRole('button', { name: '启用', exact: true })).toBeVisible()
    await settings.getByRole('button', { name: '关闭设置' }).click()
    await expect(editor).toHaveValue('未保存的正文')
    await expect(input).toHaveValue('未发送的消息')
    await expect(opener).toBeFocused()
    await page.getByRole('button', { name: '引用 Skill', exact: true }).click()
    await expect(page.getByRole('dialog', { name: '引用 Skill' }).getByRole('button', { name: '引用 人物检查' })).toHaveCount(0)
    await page.keyboard.press('Escape')
    await expect(page.getByRole('dialog')).toHaveCount(0)
  })
}

test('dollar completion sends stable references and the next task has no sticky Skill choice', async ({ page }) => {
  const state = await mockWorkspace(page, { history: false })
  await mockSkills(page)
  await page.goto('/projects/p/chapters/c')
  const input = page.getByRole('textbox', { name: '对话消息' })
  await input.fill('使用 $人物')
  await expect(page.getByRole('listbox', { name: 'Skill 引用建议' })).toBeVisible()
  await input.press('Enter')
  await expect(input).toHaveValue('使用 $人物检查 ')
  expect(state.writes).toHaveLength(0)
  await input.press('End')
  await input.press('Enter')
  await expect.poll(() => state.writes.length).toBe(1)
  expect(state.writes[0].skillMentions).toEqual([{ skillId: 'character', name: '人物检查' }])
  await expect(input).toHaveValue('')
  // Finish the fixture's asynchronous task before submitting the next message.
  const conversation = state.conversations[0], run = state.runs[0]
  run.status = 'SUCCEEDED'; conversation.activeRunId = null
  state.messages[conversation.id].push({ id: 'answer', role: 'ASSISTANT', content: '已检查', seq: ++conversation.lastSeq, runId: run.id, status: 'SUCCEEDED' })
  await input.fill('讨论故事节奏')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect.poll(() => state.writes.length).toBe(2)
  expect(state.writes[1].skillMentions).toEqual([])
})

test('home settings imports a Skill and exposes it for management', async ({ page }) => {
  await page.route('**/api/v1/system/maintenance', route => route.fulfill({ json: { data: { active: false } } }))
  await page.route('**/api/v1/projects?**', route => route.fulfill({ json: { data: { items: [], nextCursor: null } } }))
  await mockSkills(page)
  await page.goto('/')
  await page.getByRole('button', { name: '设置', exact: true }).click()
  const settings = page.getByRole('dialog', { name: '设置', exact: true })
  await settings.getByRole('button', { name: '添加 Skill' }).click()
  await settings.getByLabel('导入 Markdown / ZIP', { exact: true }).setInputFiles({ name: 'SKILL.md', mimeType: 'text/markdown', buffer: Buffer.from('---\nname: 表达润色\ndescription: 调整文字表达\n---\n润色文字。') })
  await expect(settings.getByRole('heading', { name: '表达润色', exact: true })).toBeVisible()
  await expect(settings.getByRole('button', { name: '启用', exact: true })).toBeVisible()
  await settings.getByRole('button', { name: '启用', exact: true }).click()
  await expect(settings.getByRole('button', { name: '停用', exact: true })).toBeVisible()
  await settings.getByRole('searchbox', { name: '搜索 Skill' }).fill('表达')
  await expect(settings.locator('.skillCard')).toHaveCount(1)
})
