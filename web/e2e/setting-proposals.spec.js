import { test, expect } from '@playwright/test'

const api = '/api/v1'
let project, chapter, setting

test.beforeEach(async ({ request }) => {
  project = (await (await request.post(`${api}/projects`, { data: { title: '设定修改验收' } })).json()).data
  chapter = (await (await request.post(`${api}/projects/${project.id}/chapters`, { data: { title: '第一章' } })).json()).data
  await request.put(`${api}/chapters/${chapter.id}/content`, { data: { content: '已经保存的章节正文。', expectedVersion: 1 } })
  setting = (await (await request.post(`${api}/projects/${project.id}/settings`,
    { data: { type: 'CHARACTER', title: '林舟', content: '林舟不会游泳。' } })).json()).data
})

test.afterEach(async ({ request }) => {
  const runs = (await (await request.get(`${api}/agent/runs?projectId=${project.id}`)).json()).data.items
  for (const run of runs) {
    if (!run.proposalId) continue
    const proposal = (await (await request.get(`${api}/edit-proposals/${run.proposalId}`)).json()).data
    if (['PENDING', 'PARTIALLY_APPLIED', 'CONFLICTED'].includes(proposal.status))
      await request.post(`${api}/edit-proposals/${run.proposalId}/reject`)
  }
  expect((await request.delete(`${api}/projects/${project.id}`)).status()).toBe(204)
})

async function propose(page) {
  await page.getByRole('textbox', { name: '对话消息' }).fill(`E2E_SETTING_EDIT SETTING=${setting.id} 修改林舟的人物设定。`)
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.getByRole('button', { name: '在设定中查看' })).toBeVisible()
}

test('setting edits preserve the chapter draft and save reviewed content with history across reload', async ({ page, request }) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.goto(`/projects/${project.id}/chapters/${chapter.id}`)
  await page.locator('.editor').fill('用户未保存的章节草稿。')
  await propose(page)
  const runs = (await (await request.get(`${api}/agent/runs?projectId=${project.id}`)).json()).data.items
  expect(runs[0].targetType).toBe('SETTING')
  expect(runs[0].targetSettingId).toBe(setting.id)
  expect(runs[0].targetChapterId).toBeNull()
  expect((await (await request.get(`${api}/settings/${setting.id}`)).json()).data.version).toBe(1)
  await page.getByRole('button', { name: '在设定中查看' }).click()
  const dialog = page.getByRole('dialog', { name: '作品设定' })
  await expect(dialog.getByRole('form', { name: '审阅设定修改' })).toBeVisible()
  await dialog.getByRole('button', { name: '应用全部修改' }).click()
  await dialog.getByLabel('设定内容', { exact: true }).fill('用户审阅后调整：林舟谨慎，会选择石桥。')
  await page.screenshot({ path: 'test-results/setting-proposal-desktop.png' })
  await dialog.getByRole('button', { name: '保存设定' }).click()
  await expect(dialog).toContainText('设定已保存')
  await dialog.getByText('设定历史 · v2', { exact: true }).click()
  await expect(dialog.locator('.settingRevision')).toHaveCount(2)
  await dialog.getByRole('button', { name: '关闭作品设定' }).click()
  await expect(page.locator('.messageProposal')).toContainText('处理完成')
  await expect(page.locator('.editor')).toHaveValue('用户未保存的章节草稿。')
  const saved = (await (await request.get(`${api}/settings/${setting.id}`)).json()).data
  expect(saved).toMatchObject({ type: 'RULE', title: '林舟的行动规则', version: 2, content: '用户审阅后调整：林舟谨慎，会选择石桥。' })
  const revisions = (await (await request.get(`${api}/settings/${setting.id}/revisions`)).json()).data
  expect(revisions[0]).toMatchObject({ source: 'AGENT', proposalId: runs[0].proposalId })
  await request.put(`${api}/edit-proposals/${runs[0].proposalId}/setting`, { data: { ...saved, expectedVersion: 1 } })
  expect((await (await request.get(`${api}/settings/${setting.id}/revisions`)).json()).data).toHaveLength(2)
  await page.getByRole('button', { name: '保存正文' }).click()
  await expect(page.locator('.editorHeader')).toContainText('已保存')
  await page.reload()
  await page.getByRole('button', { name: '在设定中查看' }).click()
  await expect(dialog).toContainText('这份设定建议已处理并保存。')
  await expect(dialog.getByRole('heading', { name: '林舟的行动规则' })).toBeVisible()
})

test('a stale setting proposal cannot overwrite a newer manual save', async ({ page, request }) => {
  await page.goto(`/projects/${project.id}`)
  await propose(page)
  expect((await request.patch(`${api}/settings/${setting.id}`, { data: {
    type: 'CHARACTER', title: '林舟', content: '另一个窗口保存的新设定。', expectedVersion: 1 } })).status()).toBe(200)
  await page.getByRole('button', { name: '在设定中查看' }).click()
  const dialog = page.getByRole('dialog', { name: '作品设定' })
  await expect(dialog.getByRole('alert')).toContainText('设定已发生变化')
  await expect(dialog.getByRole('button', { name: '应用全部修改' })).toBeDisabled()
  await expect(dialog.getByRole('button', { name: '保存设定' })).toBeDisabled()
  await expect(dialog.getByLabel('设定内容', { exact: true })).toHaveValue('另一个窗口保存的新设定。')
  await dialog.getByRole('button', { name: '忽略建议' }).click()
  await expect(dialog).toContainText('设定建议已忽略')
  expect((await (await request.get(`${api}/settings/${setting.id}`)).json()).data.version).toBe(2)
})

test('mobile natural-language analysis reads settings without proposals and editing works with no chapter reference', async ({ page, request }) => {
  await page.setViewportSize({ width: 390, height: 640 })
  await page.goto(`/projects/${project.id}`)
  await expect(page.getByRole('button', { name: /仅分析/ })).toHaveCount(0)
  await page.getByRole('textbox', { name: '对话消息' }).fill(`E2E_SETTING_EDIT SETTING=${setting.id} 只分析人物，不要生成修改建议。`)
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.locator('.conversationMessage.assistant')).toContainText('仅分析设定，未生成修改建议。')
  let runs = (await (await request.get(`${api}/agent/runs?projectId=${project.id}`)).json()).data.items
  expect(runs[0].proposalId).toBeNull()
  expect(runs[0].targetChapterId).toBeNull()
  await propose(page)
  await page.getByRole('button', { name: '在设定中查看' }).click()
  const dialog = page.getByRole('dialog', { name: '作品设定' })
  await expect(dialog.getByRole('button', { name: '应用全部修改' })).toBeVisible()
  await dialog.getByRole('button', { name: '应用全部修改' }).click()
  const save = dialog.getByRole('button', { name: '保存设定' })
  await expect(save).toBeInViewport()
  await page.screenshot({ path: 'test-results/setting-proposal-mobile.png' })
  await save.click()
  await expect(dialog).toContainText('设定已保存')
  runs = (await (await request.get(`${api}/agent/runs?projectId=${project.id}`)).json()).data.items
  expect(runs[0].targetType).toBe('SETTING')
})
