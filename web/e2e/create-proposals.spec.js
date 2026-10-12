import { test, expect } from '@playwright/test'
const api = '/api/v1'

test('empty work generates multiple drafts, saves new content, and restores all proposal states', async ({ page, request }) => {
  const project = (await (await request.post(`${api}/projects`, { data: { title: '空作品新建验收' } })).json()).data
  await page.goto(`/projects/${project.id}`)
  await page.getByRole('textbox', { name: '对话消息' }).fill('E2E_CREATE_DRAFTS 创建任务、世界观和写作约束，再写第一章。')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.getByRole('button', { name: '在设定中查看' })).toHaveCount(3)
  await expect(page.getByRole('button', { name: '审阅新章节' })).toBeVisible()
  const emptySettings = (await (await request.get(`${api}/projects/${project.id}/settings`)).json()).data
  const emptyChapters = (await (await request.get(`${api}/projects/${project.id}/chapters`)).json()).data
  expect(emptySettings.items).toHaveLength(0); expect(emptyChapters.items).toHaveLength(0)
  await page.getByRole('button', { name: '在设定中查看' }).nth(1).click()
  const settings = page.getByRole('dialog', { name: '作品设定' })
  await expect(settings.getByRole('heading', { name: '新建设定草稿' })).toBeVisible()
  await settings.getByLabel('设定内容', { exact: true }).fill('作者审阅后的末法世界。')
  await settings.getByRole('button', { name: '保存设定', exact: true }).click()
  await expect(settings.getByRole('heading', { name: '世界观', exact: true })).toBeVisible()
  await settings.getByRole('button', { name: '关闭作品设定' }).click()
  await page.getByRole('button', { name: '审阅新章节' }).click()
  const chapter = page.getByRole('dialog', { name: '审阅新章节' })
  await chapter.getByLabel('章节正文', { exact: true }).fill('作者审阅后的完整开篇。')
  await chapter.getByRole('button', { name: '保存新章节' }).click()
  await expect(chapter).not.toBeVisible()
  const saved = (await (await request.get(`${api}/projects/${project.id}/chapters`)).json()).data.items
  expect(saved).toHaveLength(1)
  expect(saved[0].version).toBe(1)
  const body = (await (await request.get(`${api}/chapters/${saved[0].id}`)).json()).data
  expect(body.content).toBe('作者审阅后的完整开篇。')
  await page.reload()
  await expect(page.getByRole('button', { name: '在设定中查看' })).toHaveCount(3)
  await page.getByRole('button', { name: '审阅新章节' }).click()
  await expect(page.getByText('这份章节建议已保存。')).toBeVisible()
  await page.getByRole('button', { name: '关闭', exact: true }).click()
  const runs = (await (await request.get(`${api}/agent/runs?projectId=${project.id}`)).json()).data.items
  expect(runs).toHaveLength(1)
  const detail = (await (await request.get(`${api}/agent/runs/${runs[0].id}`)).json()).data
  expect(detail.proposals).toHaveLength(4)
  expect(detail.proposals.filter(p => p.proposalStatus === 'APPLIED')).toHaveLength(2)
  for (const proposal of detail.proposals.filter(p => p.proposalStatus === 'PENDING')) await request.post(`${api}/edit-proposals/${proposal.proposalId}/reject`)
  expect((await request.delete(`${api}/projects/${project.id}`)).status()).toBe(204)
})
