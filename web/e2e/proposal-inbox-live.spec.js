import { expect, test } from '@playwright/test'
import { randomUUID } from 'node:crypto'

const api = '/api/v1'
let projectId
test.afterEach(async ({ request }) => {
  if (!projectId) return
  const result = await request.get(`${api}/projects/${projectId}/edit-proposals?limit=50`)
  if (result.ok()) for (const item of (await result.json()).data.items)
    await request.post(`${api}/edit-proposals/${item.id}/reject`)
  expect((await request.delete(`${api}/projects/${projectId}`)).status()).toBe(204)
  projectId = null
})

test('suggestions from separate old conversations are discoverable, reviewable and removed from counts after saving', async ({ page, request }) => {
  projectId = (await (await request.post(`${api}/projects`, { data: { title: '待处理建议真实接口验收' } })).json()).data.id
  const chapter = (await (await request.post(`${api}/projects/${projectId}/chapters`, { data: { title: '第二章 · 旧桥' } })).json()).data
  await request.put(`${api}/chapters/${chapter.id}/content`, { data: { content: '林舟游到了对岸。但人物设定说他不会游泳。', expectedVersion: 1 } })
  for (let i = 0; i < 2; i++) {
    const conversation = (await (await request.post(`${api}/projects/${projectId}/conversations`, { data: {} })).json()).data
    const response = await request.post(`${api}/conversations/${conversation.id}/messages`, { data: {
      content: `E2E_EDIT 第 ${i + 1} 次检查设定并提交候选稿。`, clientRequestId: randomUUID(),
      expectedConversationSeq: 0, contextChapterId: chapter.id, contextVersion: 2,
    } })
    expect(response.status()).toBe(202)
    const run = (await response.json()).data
    await expect.poll(async () => (await (await request.get(`${api}/agent/runs/${run.runId}`)).json()).data.status).toBe('SUCCEEDED')
  }
  await page.goto('/')
  const card = page.getByRole('article', { name: '作品：待处理建议真实接口验收', exact: true })
  await expect(card).toContainText('2 份建议待处理')
  await card.getByRole('button', { name: '打开作品《待处理建议真实接口验收》' }).click()
  const entry = page.getByRole('button', { name: '待处理建议 2' })
  await expect(entry).toBeVisible()
  await expect(page.locator('.conversationMessage.assistant')).toHaveCount(0)
  await entry.click()
  const dialog = page.getByRole('dialog', { name: '待处理建议' })
  await expect(dialog.locator('[data-proposal-id]')).toHaveCount(2)
  const first = (await (await request.get(`${api}/projects/${projectId}/edit-proposals`)).json()).data.items[0]
  await dialog.locator(`[data-proposal-id="${first.id}"]`).getByRole('button', { name: '查看对话' }).click()
  await expect(page.locator(`[data-run-id="${first.runId}"]`)).toBeFocused()
  await page.getByRole('button', { name: '历史对话' }).click()
  await expect(page.getByRole('dialog', { name: '历史对话' }).getByText('1 份建议待处理', { exact: true })).toHaveCount(2)
  await page.getByRole('dialog').getByRole('button', { name: '关闭' }).click()
  await entry.click()
  await page.getByRole('dialog', { name: '待处理建议' }).locator(`[data-proposal-id="${first.id}"]`).getByRole('button', { name: '审阅' }).click()
  await expect(page.locator('.editorHeader h1')).toHaveText('第二章 · 旧桥')
  await page.getByRole('button', { name: '应用', exact: true }).click()
  await expect(page.getByRole('button', { name: '待处理建议 1' })).toBeVisible()
  await page.getByRole('button', { name: '← 全部作品' }).click()
  await expect(card).toContainText('1 份建议待处理')
  await card.hover()
  await card.getByRole('button', { name: '删除' }).click()
  await page.getByRole('button', { name: '确认删除' }).click()
  await page.getByRole('button', { name: '查看待处理建议' }).click()
  await expect(page.getByRole('dialog', { name: '待处理建议' }).locator('[data-proposal-id]')).toHaveCount(1)
  await page.getByRole('dialog', { name: '待处理建议' }).getByRole('button', { name: '审阅' }).click()
  await page.getByRole('button', { name: '忽略', exact: true }).click()
  await expect(page.getByRole('button', { name: '待处理建议 0' })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('button', { name: '待处理建议 0' })).toBeVisible()
  await page.getByRole('button', { name: '← 全部作品' }).click()
  await expect(card.getByText('1 份建议待处理')).toHaveCount(0)
  await card.hover()
  await card.getByRole('button', { name: '删除' }).click()
  await page.getByRole('button', { name: '确认删除' }).click()
  await expect(card).toHaveCount(0)
  projectId = null
})
