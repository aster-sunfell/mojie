import { expect, test } from '@playwright/test'
import { mockWorkspace } from './fixtures/workspace.mjs'

async function mockInbox(page) {
  const state = await mockWorkspace(page, { history: false })
  const old = { id: 'archive', title: '很早的情节检查', lastSeq: 2, version: 1, pendingProposalCount: 2, conflictedProposalCount: 1 }
  state.conversations.push(old)
  state.runs.push({ id: 'old-run', status: 'SUCCEEDED', taskType: 'ASSISTANT', targetChapterId: 'c2',
    targetType: 'CHAPTER', proposalId: 'q1', baseVersion: 1, lastEventSeq: 1, steps: [], sources: [] })
  state.messages.archive = [{ id: 'old-answer', role: 'ASSISTANT', runId: 'old-run', content: '旧对话提出了章节修改。', seq: 2 }]
  const rows = [
    { id: 'q1', runId: 'old-run', conversationId: 'archive', conversationTitle: old.title, targetType: 'CHAPTER', operation: 'UPDATE',
      targetChapterId: 'c2', targetTitle: '第二章 · 故人', chapterOrder: 2, baseVersion: 1, status: 'PENDING', reason: '补充桥边重逢的动机', remainingItemCount: 2, createdAt: '2026-01-01T00:00:00Z' },
    { id: 'q2', runId: 'old-run', conversationId: 'archive', conversationTitle: old.title, targetType: 'CHAPTER', operation: 'CREATE',
      targetTitle: '第三章 · 渡口', status: 'PENDING', reason: '承接桥边的情节', remainingItemCount: null, createdAt: '2026-01-02T00:00:00Z' },
    { id: 'q3', runId: 'old-run', conversationId: 'archive', conversationTitle: old.title, targetType: 'SETTING', operation: 'UPDATE',
      targetSettingId: 's', targetTitle: '林舟', status: 'CONFLICTED', reason: '人物设定已有新版本', remainingItemCount: null, createdAt: '2026-01-03T00:00:00Z' },
  ]
  await page.route('**/api/v1/projects/p/edit-proposals**', route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/summary')) return route.fulfill({ json: { data: { pendingCount: 2, conflictedCount: 1, targetCount: 3 } } })
    const filter = url.searchParams.get('filter')
    const items = rows.filter(item => filter === 'all' || (filter === 'conflicted' ? item.status === 'CONFLICTED' : item.status !== 'CONFLICTED'))
    return route.fulfill({ json: { data: { items, nextCursor: null } } })
  })
  await page.route('**/api/v1/edit-proposals/*', route => route.fulfill({ json: { data: { ...rows[0], items: [] } } }))
}

for (const width of [1280, 390]) {
  test(`work-wide suggestions are visible on entry and link back to the exact source at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 })
    await mockInbox(page)
    const messages = []
    page.on('request', request => { if (request.url().includes('/conversations/archive/messages')) messages.push(request.url()) })
    await page.goto('/projects/p/chapters/c')
    const entry = page.getByRole('button', { name: '待处理建议 3' })
    await expect(entry).toBeVisible()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await entry.click()
    const dialog = page.getByRole('dialog', { name: '待处理建议' })
    await expect(dialog).toContainText('待处理 2 份 · 需核对 1 份')
    await expect(dialog.getByRole('heading', { name: /章节 · 第二章/ })).toBeVisible()
    await expect(dialog).toContainText('剩余 2 处修改')
    expect(messages).toHaveLength(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
    await page.screenshot({ path: `test-results/proposal-inbox-${width}.png` })
    await dialog.getByRole('button', { name: '需核对', exact: true }).click()
    await expect(dialog.locator('[data-proposal-id]')).toHaveCount(1)
    await expect(dialog).toContainText('人物设定已有新版本')
    await dialog.getByRole('button', { name: '全部', exact: true }).click()
    await dialog.locator('[data-proposal-id="q1"]').getByRole('button', { name: '查看对话' }).click()
    const turn = page.locator('[data-run-id="old-run"]')
    await expect(turn).toContainText('旧对话提出了章节修改')
    await expect(turn).toBeFocused()
    await expect(entry).toHaveText('待处理建议3')
    await page.getByRole('button', { name: '历史对话' }).click()
    const history = page.getByRole('dialog', { name: '历史对话' })
    await expect(history).toContainText('2 份建议待处理 · 1 份需核对')
    await history.getByRole('button', { name: '关闭' }).click()
  })
}
