import { expect, test } from '@playwright/test'

const api = '/api/v1'
let projectId
let chapterId

test.beforeEach(async ({ request }) => {
  projectId = null
  chapterId = null
  projectId = (await (await request.post(`${api}/projects`, { data: { title: '浏览器 Agent 验收' } })).json()).data.id
  chapterId = (await (await request.post(`${api}/projects/${projectId}/chapters`,
    { data: { title: '目标章节' } })).json()).data.id
  await request.put(`${api}/chapters/${chapterId}/content`,
    { data: { content: '林舟游到了对岸。但人物设定说他不会游泳。', expectedVersion: 1 } })
})

test.afterEach(async ({ request }) => {
  if (projectId) expect((await request.delete(`${api}/projects/${projectId}`)).status()).toBe(204)
})

test('reload and broken event stream recover the completed run', async ({ page, request }) => {
  await page.route('**/agent/runs/*/events*', route => route.abort())
  await page.goto(`/projects/${projectId}/chapters/${chapterId}`)
  await expect(page.locator('.editor')).toHaveValue(/林舟游到了对岸/)
  await page.locator('#instruction').fill('E2E_SLOW 检查目标章节。')
  await page.getByRole('button', { name: '开始检查' }).click()
  await expect(page.locator('.runDetail')).toContainText('运行中')
  await page.reload()
  await expect(page.locator('.runDetail')).toContainText('已完成', { timeout: 30_000 })
  await expect(page.locator('.analysis')).toContainText('已核对目标章完整正文')
  const runs = (await (await request.get(`${api}/agent/runs?projectId=${projectId}&chapterId=${chapterId}`)).json()).data.items
  expect(runs).toHaveLength(1)
  expect(runs[0].status).toBe('SUCCEEDED')
  expect(page.locator('.sources button')).toHaveCount(1)
})

test('source view keeps a draft and proposal acceptance writes once', async ({ page, request }) => {
  await page.goto(`/projects/${projectId}/chapters/${chapterId}`)
  await expect(page.locator('.editor')).toHaveValue(/林舟游到了对岸/)
  await page.locator('#instruction').fill('E2E_EDIT 检查设定并提交候选稿。')
  await page.getByRole('button', { name: '开始检查' }).click()
  await expect(page.locator('.proposal')).toContainText('PENDING')
  const original = await page.locator('.editor').inputValue()
  await page.locator('.editor').fill(`${original}草稿`)
  await expect(page.getByRole('button', { name: '整体采纳' })).toBeDisabled()
  await page.locator('.sources button').first().click()
  await expect(page.locator('.drawer')).toContainText('历史来源')
  await expect(page.locator('.drawer')).toContainText('林舟游到了对岸')
  await page.locator('.drawer').getByRole('button', { name: '关闭' }).click()
  await expect(page.locator('.editor')).toHaveValue(`${original}草稿`)
  await page.locator('.editor').fill(original)
  await page.getByRole('button', { name: '整体采纳' }).click()
  await expect(page.locator('.proposal')).toContainText('APPLIED')
  await expect(page.locator('.editor')).toHaveValue(/林舟沿桥走到了对岸/)
  await page.reload()
  await expect(page.locator('.editor')).toHaveValue(/林舟沿桥走到了对岸/)
  const revisions = (await (await request.get(`${api}/chapters/${chapterId}/revisions`)).json()).data.items
  expect(revisions.filter(revision => revision.source === 'AGENT')).toHaveLength(1)
})

test('browser cancellation reaches a terminal state without a proposal', async ({ page, request }) => {
  await page.goto(`/projects/${projectId}/chapters/${chapterId}`)
  await expect(page.locator('.editor')).toHaveValue(/林舟游到了对岸/)
  await page.locator('#instruction').fill('E2E_BLOCK 等待取消。')
  await page.getByRole('button', { name: '开始检查' }).click()
  await expect(page.locator('.runDetail')).toContainText('运行中')
  await page.getByRole('button', { name: '停止', exact: true }).click()
  await expect(page.locator('.runDetail')).toContainText('正在停止')
  await expect(page.locator('.runDetail')).toContainText('已停止', { timeout: 40_000 })
  const runs = (await (await request.get(`${api}/agent/runs?projectId=${projectId}&chapterId=${chapterId}`)).json()).data.items
  expect(runs[0].status).toBe('CANCELLED')
  expect(runs[0].proposalId).toBeFalsy()
})

test('two tabs keep the losing draft when the chapter version changes', async ({ page }) => {
  const second = await page.context().newPage()
  try {
    const path = `/projects/${projectId}/chapters/${chapterId}`
    await Promise.all([page.goto(path), second.goto(path)])
    await expect(page.locator('.editor')).toHaveValue(/林舟游到了对岸/)
    await expect(second.locator('.editor')).toHaveValue(/林舟游到了对岸/)
    await page.locator('.editor').fill('第一个标签页的新正文。')
    await second.locator('.editor').fill('第二个标签页保留的草稿。')
    await page.getByRole('button', { name: '保存正文' }).click()
    await expect(page.locator('.editorHeader')).toContainText('版本 3')
    await second.getByRole('button', { name: '保存正文' }).click()
    await expect(second.locator('.editorHeader')).toContainText('版本冲突')
    await expect(second.locator('.editor')).toHaveValue('第二个标签页保留的草稿。')
  } finally { await second.close() }
})
