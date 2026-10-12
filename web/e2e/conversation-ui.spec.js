import { expect, test } from '@playwright/test'
import { mockWorkspace } from './fixtures/workspace.mjs'

for (const mobile of [false, true]) {
  test(`assistant process survives refresh and completes in place on ${mobile ? 'mobile' : 'desktop'}`, async ({ page }) => {
    if (mobile) await page.setViewportSize({ width: 390, height: 844 })
    const state = await mockWorkspace(page, { history: false })
    await page.goto('/projects/p/chapters/c')
    if (mobile) await page.getByRole('button', { name: '专注对话', exact: true }).click()
    await page.getByRole('textbox', { name: '对话消息' }).fill('检查林舟的人物动机')
    await page.getByRole('button', { name: '发送', exact: true }).click()
    const assistant = page.locator('.conversationMessage.assistant')
    await expect(assistant).toBeVisible()
    const run = state.runs[0], conversation = state.conversations[0]
    run.activities = [{ id: 'reading', modelCallIndex: 1, text: '我先阅读本章，再核对人物设定，重点检查林舟的动机是否一致。' },
      { id: 'checking', modelCallIndex: 2, text: '已读完本章，接下来查阅人物设定。' }]
    run.steps = [{ toolExecutionId: 'read-chapter', modelCallIndex: 1, toolName: 'read_chapter', args: { chapterId: 'c' },
      status: 'SUCCEEDED', result: { success: true, data: { title: '第一章 · 雨夜' } } },
    { toolExecutionId: 'read-settings', modelCallIndex: 2, toolName: 'read_settings', args: { settingIds: ['character'] }, status: 'STARTED' }]
    run.lastEventSeq = 2
    await expect(assistant).toContainText(run.activities[0].text)
    await expect(assistant).toContainText('正在查阅设定')
    await expect(page.locator('.conversationMessage.user')).not.toContainText('查阅设定')
    await expect(page.getByText('执行详情', { exact: true })).toHaveCount(0)
    await page.reload()
    if (mobile) await page.getByRole('button', { name: /^专注对话/ }).click()
    await expect(assistant).toContainText(run.activities[0].text)
    await expect(assistant).toContainText('正在查阅设定')
    await assistant.evaluate(element => { element.dataset.testIdentity = 'original-assistant' })
    const firstTool = assistant.locator('.conversationToolActivity').first()
    await firstTool.locator('summary').click()
    await expect(firstTool).toHaveAttribute('open', '')
    await page.screenshot({ path: `test-results/conversation-process-${mobile ? 'mobile' : 'desktop'}.png` })
    run.steps[1].status = 'SUCCEEDED'
    run.status = 'SUCCEEDED'; run.analysis = '林舟的动机与设定一致，可以补充一处行动前的犹豫，使转折更自然。'; run.lastEventSeq = 3
    conversation.activeRunId = null
    state.messages[conversation.id][0].status = 'SUCCEEDED'
    state.messages[conversation.id].push({ id: 'answer', role: 'ASSISTANT', content: run.analysis, runId: run.id, status: 'SUCCEEDED', seq: ++conversation.lastSeq })
    await expect(assistant).toContainText(run.analysis)
    await expect(assistant).toHaveCount(1)
    await expect(assistant).toHaveAttribute('data-test-identity', 'original-assistant')
    await expect(firstTool).toHaveAttribute('open', '')
    await expect(page.getByText(run.analysis, { exact: true })).toHaveCount(1)
    await expect(assistant).toContainText(run.activities[0].text)
    await expect(assistant.locator('.assistantProgress')).toHaveCount(0)
    await firstTool.locator('summary').click()
    await page.locator('.conversationMessages').evaluate(element => { element.scrollTop = 0 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(mobile ? 390 : 1280)
    await page.screenshot({ path: `test-results/conversation-completed-${mobile ? 'mobile' : 'desktop'}.png` })
  })
}

test('compact composer references a Skill in its message', async ({ page }) => {
  await mockWorkspace(page, { history: false })
  await page.route('**/api/v1/skills**', route => route.fulfill({ json: { data: new URL(route.request().url()).pathname.endsWith('/versions')
    ? { items: [{ id: 'v1' }], nextCursor: null }
    : { items: [{ skill: { id: 's1', name: '人物检查', enabled: true, currentVersionId: 'v1' } }], nextCursor: null } } }))
  await page.goto('/projects/p/chapters/c')
  const skill = page.getByRole('button', { name: '引用 Skill', exact: true })
  await expect(skill).toBeVisible()
  await expect(page.locator('.assistantExamples')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '展开消息' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '新对话', exact: true })).toHaveCount(1)
  expect((await page.locator('.conversationComposer').boundingBox()).height).toBeLessThan(130)
  expect((await page.locator('.conversationMessages').boundingBox()).height).toBeGreaterThan(400)
  await skill.hover()
  await expect(page.getByRole('tooltip')).toHaveText('引用 Skill')
  await page.screenshot({ path: 'test-results/conversation-compact.png' })
  await skill.click()
  await page.getByRole('button', { name: '引用 人物检查', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '对话消息' })).toHaveValue('$人物检查 ')
  await expect(page.getByRole('button', { name: '移除 Skill 引用：人物检查' })).toBeVisible()
  await skill.hover()
  await expect(page.getByRole('tooltip')).toHaveText('引用 Skill')
  await page.mouse.move(1, 1)
  const input = page.getByRole('textbox', { name: '对话消息' })
  await input.fill('继续分析人物')
  await expect(page.locator('.assistantHelp')).toContainText('6/8000')
  await page.getByRole('heading', { name: '写作助手', exact: true }).click()
  await expect(page.locator('.assistantHelp')).not.toContainText('/8000')
})

for (const mobile of [false, true]) {
  test(`history dialog manages individual rows and contains long titles on ${mobile ? 'mobile' : 'desktop'}`, async ({ page }) => {
    if (mobile) await page.setViewportSize({ width: 390, height: 844 })
    const state = await mockWorkspace(page)
    state.chapters[0].title = '第一章 · 雨夜与故人重逢以及许多尚未揭开的旧事'
    const other = { id: 'other', title: '人物动机与章节细节的讨论'.repeat(5), version: 1, lastSeq: 0, updatedAt: '2026-10-12T03:00:00Z' }
    state.conversations.push(other)
    for (let i = 0; i < 14; i++) state.conversations.push({ id: `history-${i}`, title: `之前的讨论 ${i + 1}`, version: 1, lastSeq: 0, updatedAt: '2026-10-11T03:00:00Z' })
    const input = page.getByRole('textbox', { name: '对话消息' })
    await page.goto('/projects/p/chapters/c')
    if (mobile) await page.getByRole('button', { name: '专注对话', exact: true }).click()
    await input.fill('当前对话的草稿')
    await page.getByRole('button', { name: '历史对话', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '历史对话', exact: true })
    await expect(dialog).toBeVisible()
    await expect(dialog.locator('.conversationHistoryRow')).toHaveCount(16)
    await expect(dialog.locator('.conversationHistorySelect[aria-current=true]')).toContainText('雨夜章节讨论')
    expect(await dialog.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(mobile ? 390 : 1280)
    expect(await dialog.locator('.conversationHistoryList').evaluate(element => element.scrollHeight > element.clientHeight)).toBe(true)
    await page.screenshot({ path: `test-results/conversation-history-${mobile ? 'mobile' : 'desktop'}.png` })
    await dialog.getByRole('button', { name: `更多操作：${other.title}`, exact: true }).click()
    await dialog.getByRole('button', { name: '重命名', exact: true }).click()
    await dialog.getByRole('textbox', { name: '对话标题' }).fill('人物讨论')
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await expect(dialog.getByRole('button', { name: '打开对话：人物讨论', exact: true })).toBeVisible()
    await expect(input).toHaveValue('当前对话的草稿')
    await expect(dialog.getByRole('tab')).toHaveCount(0)
    await expect(dialog.getByRole('region', { name: '历史对话列表' })).toBeVisible()
    await expect(dialog.locator('.conversationHistoryRow')).toHaveCount(16)
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
    await expect(page.getByRole('button', { name: '历史对话', exact: true })).toBeFocused()
    await expect(input).toHaveValue('当前对话的草稿')
  })
}
