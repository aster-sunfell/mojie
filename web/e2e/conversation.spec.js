import { test, expect } from '@playwright/test'
import { readFileSync } from 'node:fs'

test('persistent conversations isolate history and manage complete Skills', async ({ page, request }) => {
  const project = (await (await request.post('http://127.0.0.1:18080/api/v1/projects', { data: { title: `对话测试-${Date.now()}` } })).json()).data
  await page.goto(`/projects/${project.id}`)
  await page.getByRole('textbox', { name: '对话消息' }).fill('记住林舟怕火')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.locator('.conversationMessages')).toContainText('林舟怕火，因此避开火光。')
  const first = await page.evaluate(id => localStorage.getItem(`mojie:conversation:${id}`), project.id)
  await page.getByRole('textbox', { name: '对话消息' }).fill('他为什么避开火光？')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.locator('.conversationMessage.assistant .assistantAnswer')).toHaveCount(2)
  await page.getByRole('button', { name: '新对话', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '对话消息' })).toHaveValue('')
  await page.getByRole('textbox', { name: '对话消息' }).fill('讨论大纲')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.locator('.conversationMessages')).toContainText('可以一起讨论大纲。')
  const second = await page.evaluate(id => localStorage.getItem(`mojie:conversation:${id}`), project.id)
  expect(second).not.toBe(first)
  const history = (await (await request.get(`http://127.0.0.1:18080/api/v1/projects/${project.id}/conversations`)).json()).data
  expect(history.items.map(item => item.id)).toEqual([second, first])
  expect(history.items.every(item => !Object.hasOwn(item, 'status'))).toBe(true)
  await page.getByRole('button', { name: '历史对话' }).click()
  const dialog = page.getByRole('dialog', { name: '历史对话' })
  await expect(dialog.getByRole('tab')).toHaveCount(0)
  await dialog.locator(`[data-conversation-id="${first}"]`).click()
  await expect(page.locator('.conversationMessage.assistant .assistantAnswer')).toHaveCount(2)
  await page.reload()
  expect(await page.evaluate(id => localStorage.getItem(`mojie:conversation:${id}`), project.id)).toBe(first)
  await expect(page.locator('.conversationMessage.assistant .assistantAnswer')).toHaveCount(2)
  await page.getByRole('button', { name: '设置', exact: true }).click()
  const settings = page.getByRole('dialog', { name: '设置', exact: true })
  await settings.getByRole('button', { name: '添加 Skill', exact: true }).click()
  const unique = `character-${Date.now()}`
  await settings.getByLabel('导入 Markdown / ZIP', { exact: true }).setInputFiles({ name: 'SKILL.md', mimeType: 'text/markdown', buffer: Buffer.from(`---\nname: ${unique}\ndescription: 人物检查\n---\n检查人物与章节。\n`) })
  await expect(settings.locator('.skillDetail')).toContainText(unique)
  await expect(settings.locator('.skillDetail')).toContainText('检查人物与章节。')
  const card = settings.locator('.skillCard').filter({ hasText: unique })
  await expect(card).toContainText('已停用')
  await settings.getByRole('button', { name: '启用', exact: true }).click()
  await expect(card).toContainText('已启用')
  await settings.getByRole('button', { name: '关闭设置', exact: true }).click()
  await page.getByRole('textbox', { name: '对话消息' }).fill('检查人物')
  await page.getByRole('button', { name: '引用 Skill', exact: true }).click()
  await page.getByRole('button', { name: `引用 ${unique}`, exact: true }).click()
  await expect(page.getByRole('textbox', { name: '对话消息' })).toHaveValue(`检查人物 $${unique} `)
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.locator('.conversationMessage.assistant .assistantAnswer')).toHaveCount(3)
  const messages = (await (await request.get(`http://127.0.0.1:18080/api/v1/conversations/${first}/messages`)).json()).data.items
  const mentioned = messages.find(message => message.content.includes(`$${unique}`))
  expect(mentioned.skillMentions).toHaveLength(1)
  const resources = (await (await request.get(`http://127.0.0.1:18080/api/v1/agent/runs/${mentioned.runId}/resources`)).json()).data
  expect(resources.skills.find(skill => skill.source === 'EXPLICIT').providedToModel).toBe(true)
  await page.getByRole('textbox', { name: '对话消息' }).fill('继续讨论')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.locator('.conversationMessage.assistant .assistantAnswer')).toHaveCount(4)
  const nextMessages = (await (await request.get(`http://127.0.0.1:18080/api/v1/conversations/${first}/messages`)).json()).data.items
  expect(nextMessages.find(message => message.content === '继续讨论').skillMentions).toEqual([])
})


test('context-only Skill guidance works with project reads and preserves unsupported files', async ({ page, request }) => {
  const api = 'http://127.0.0.1:18080/api/v1'
  const project = (await (await request.post(`${api}/projects`, { data: { title: '上下文 Skill 验证' } })).json()).data
  const chapter = (await (await request.post(`${api}/projects/${project.id}/chapters`, { data: { title: '人物行为' } })).json()).data
  const setting = (await (await request.post(`${api}/projects/${project.id}/settings`, { data: { type: 'CHARACTER', title: '林舟', content: '林舟不会游泳。' } })).json()).data
  const content = '林舟沿桥走到了对岸。'
  await request.put(`${api}/chapters/${chapter.id}/content`, { data: { content, expectedVersion: chapter.version } })
  const name = `context-${Date.now()}`
  const guide = readFileSync(new URL('../../docs/user-guide/skills.md', import.meta.url), 'utf8')
  const template = guide.match(/```markdown\n([\s\S]*?)\n```/)[1].replace('name: 人物一致性审稿', `name: ${name}`)
  const form = new FormData()
  for (const [path, text] of [['SKILL.md', template], ['scripts/check.py', "raise Exception('must never run')"], ['references/r.md', '人物行为检查清单']]) {
    form.append('files', new Blob([text], { type: 'text/plain' }), path.split('/').at(-1))
    form.append('paths', path)
  }
  const imported = (await (await request.post(`${api}/skills/import`, { multipart: form })).json()).data
  expect(imported.status).toBe('READY')
  const detail = (await (await request.get(`${api}/skills/${imported.skillId}`)).json()).data
  expect(detail.currentVersion.compatibility).toMatchObject({ mode: 'CONTEXT_ONLY', scriptExecution: false, scripts: ['scripts/check.py'] })
  expect(detail.currentVersion.compatibility.warnings).toHaveLength(1)
  await request.patch(`${api}/skills/${detail.skill.id}`, { data: { expectedVersion: detail.skill.version, enabled: true } })
  await page.goto(`/projects/${project.id}/chapters/${chapter.id}`)
  await page.getByRole('textbox', { name: '对话消息' }).fill(`E2E_SKILL_CONTEXT TARGET=${chapter.id} SETTING=${setting.id} 使用 $${name} 分析这一章，不要修改。`)
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.locator('.conversationMessage.assistant .assistantAnswer')).toContainText('已核对目标章完整正文')
  const runs = (await (await request.get(`${api}/agent/runs?projectId=${project.id}`)).json()).data.items
  expect(runs[0].status).toBe('SUCCEEDED')
  const run = (await (await request.get(`${api}/agent/runs/${runs[0].id}`)).json()).data
  expect(run.steps.map(step => step.toolName)).toEqual(['read_skill', 'read_skill_file', 'read_settings', 'read_chapter'])
  const resources = (await (await request.get(`${api}/agent/runs/${run.id}/resources`)).json()).data
  expect(resources.skills.find(skill => skill.skillId === imported.skillId).providedToModel).toBe(true)
  expect(resources.artifacts).toEqual([])
  expect((await (await request.get(`${api}/chapters/${chapter.id}`)).json()).data.content).toBe(content)
  expect((await request.delete(`${api}/projects/${project.id}`)).status()).toBe(204)
})
