import { expect, test } from '@playwright/test'

const base = '林舟游过河。\n\n他拿起红灯。\n'
const proposed = base.replace('游过河', '走过桥').replace('红', '蓝')

async function mockReview(page) {
  const chapter = { id: 'c', projectId: 'p', title: '第二章 · 暴雨', chapterOrder: 2, version: 1, content: base }
  const run = { id: 'r', projectId: 'p', targetChapterId: 'c', status: 'SUCCEEDED', proposalId: 'q',
    baseVersion: 1, queuedAt: '2026-10-01T00:00:00Z', lastEventSeq: 1, steps: [], sources: [],
    analysis: '人物设定中林舟不会游泳，建议改为过桥；灯笼颜色应与前文一致。' }
  const items = [['游过河', '走过桥'], ['红', '蓝']].map(([before, after], id) => {
    const start = base.indexOf(before), end = start + before.length
    return { id, before, after, start, end, lineNumber: id === 0 ? 1 : 3,
      contextBefore: base.slice(0, start), contextAfter: base.slice(end), status: 'PENDING', savedVersion: null }
  })
  const proposal = { id: 'q', runId: 'r', targetChapterId: 'c', baseVersion: 1, baseContent: base,
    proposedContent: proposed, reason: '修正游泳设定与灯笼颜色。', status: 'PENDING', items }
  const writes = []
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname.replace('/api/v1', '')
    const data = value => route.fulfill({ json: { data: value } })
    if (path === '/projects/p') return data({ id: 'p', title: '林舟演示' })
    if (path === '/projects/p/chapters') return data({ items: [chapter], nextCursor: null })
    if (path === '/chapters/c') return data(chapter)
    if (path === '/chapters/c/content') {
      const body = request.postDataJSON(); writes.push(body)
      if (body.expectedVersion !== chapter.version) return route.fulfill({ status: 409, json: { code: 'VERSION_CONFLICT' } })
      if (body.content !== chapter.content) { chapter.content = body.content; chapter.version++ }
      for (const review of body.proposalReviews || []) for (const choice of review.decisions)
        Object.assign(items[choice.itemId], { status: choice.status, savedVersion: chapter.version })
      const applied = items.some(item => item.status === 'APPLIED')
      proposal.status = items.every(item => item.status !== 'PENDING') ? applied ? 'APPLIED' : 'REJECTED'
        : applied ? 'PARTIALLY_APPLIED' : 'PENDING'
      return data(chapter)
    }
    if (path === '/system/capabilities') return data({ agentConfigured: true })
    if (path === '/agent/runs') return data({ items: [run], nextCursor: null })
    if (path === '/agent/runs/r') return data(run)
    if (path === '/edit-proposals/q') return data(proposal)
    return route.fulfill({ status: 404, json: { message: `Unexpected request ${path}` } })
  })
  return { chapter, proposal, writes }
}

test('partial saves allow manual editing and remaining changes survive reload', async ({ page }) => {
  const state = await mockReview(page)
  await page.goto('/projects/p/chapters/c')
  const cards = page.locator('.changeCard')
  await expect(cards).toHaveCount(2)
  const before = await cards.first().locator('.diffBefore').boundingBox()
  const after = await cards.first().locator('.diffAfter').boundingBox()
  expect(after.y).toBeGreaterThanOrEqual(before.y + before.height - 1)
  expect(await page.locator('.agentPanel .comparison').count()).toBe(0)
  await page.screenshot({ path: 'test-results/proposal-review-desktop.png' })
  await cards.first().getByRole('button', { name: '应用此处' }).click()
  await page.getByRole('button', { name: '编辑正文', exact: true }).click()
  const editor = page.getByRole('textbox', { name: '章节正文' })
  await expect(editor).toHaveValue(base.replace('游过河', '走过桥'))
  const manual = `${await editor.inputValue()}用户发现新的细节，补写在这里。`
  await editor.fill(manual)
  await page.getByRole('button', { name: '保存正文', exact: true }).click()
  await expect(page.locator('.editorHeader')).toContainText('已保存')
  expect(state.writes[0].proposalReviews).toEqual([{ proposalId: 'q', decisions: [{ itemId: 0, status: 'APPLIED' }] }])
  expect(state.proposal.status).toBe('PARTIALLY_APPLIED')
  await page.reload()
  await expect(cards.first()).toContainText('已保存 · 版本 2')
  await expect(cards.last().getByRole('button', { name: '应用此处' })).toBeEnabled()
  await page.getByRole('button', { name: '应用剩余', exact: true }).click()
  await page.getByRole('button', { name: '编辑正文', exact: true }).click()
  await expect(editor).toHaveValue(manual.replace('红', '蓝'))
  await page.getByRole('button', { name: '保存正文', exact: true }).click()
  await expect(page.locator('.editorHeader')).toContainText('已保存')
  expect(state.chapter.content).toBe(manual.replace('红', '蓝'))
  expect(state.proposal.status).toBe('APPLIED')
})

test('apply all preserves a same-position manual change and reports its conflict', async ({ page }) => {
  const state = await mockReview(page)
  await page.goto('/projects/p/chapters/c')
  await expect(page.locator('.changeCard')).toHaveCount(2)
  await page.getByRole('button', { name: '编辑正文', exact: true }).click()
  const manual = `${base.replace('红', '紫')}我自己补充的情节。`
  await page.getByRole('textbox', { name: '章节正文' }).fill(manual)
  await page.getByRole('button', { name: /^审阅修改/ }).click()
  await expect(page.locator('.changeCard').last()).toContainText('需要手动确认')
  await page.getByRole('button', { name: '应用全部', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('1 处已应用到草稿，1 处需要手动确认')
  await page.getByRole('button', { name: '忽略剩余', exact: true }).click()
  await page.getByRole('button', { name: '保存正文', exact: true }).click()
  await expect(page.locator('.editorHeader')).toContainText('已保存')
  expect(state.chapter.content).toBe(manual.replace('游过河', '走过桥'))
  expect(state.proposal.items.map(item => item.status)).toEqual(['APPLIED', 'IGNORED'])
})

test('mobile review uses the main area and supports editing without horizontal overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockReview(page)
  await page.goto('/projects/p/chapters/c')
  await expect(page.getByRole('region', { name: '修改审阅' })).toBeVisible()
  await expect(page.locator('.changeCard')).toHaveCount(2)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  await page.screenshot({ path: 'test-results/proposal-review-mobile.png' })
  await page.getByRole('button', { name: '助手结果', exact: true }).click()
  await page.getByRole('button', { name: '在正文区审阅修改' }).click()
  await expect(page.locator('.overlay')).toHaveCount(0)
  await page.locator('.changeCard').first().getByRole('button', { name: '定位正文并编辑' }).click()
  await expect(page.getByRole('textbox', { name: '章节正文' })).toBeEditable()
})

test('refreshing server review choices keeps the current manual draft', async ({ page }) => {
  const state = await mockReview(page)
  await page.goto('/projects/p/chapters/c')
  await expect(page.locator('.changeCard')).toHaveCount(2)
  await page.locator('.changeCard').first().getByRole('button', { name: '应用此处' }).click()
  await page.getByRole('button', { name: '编辑正文', exact: true }).click()
  const draft = `${base.replace('游过河', '走过桥')}我的新内容。`
  await page.getByRole('textbox', { name: '章节正文' }).fill(draft)
  // Simulate another tab processing that choice without changing this tab's draft.
  Object.assign(state.proposal.items[0], { status: 'IGNORED', savedVersion: 1 })
  await page.getByRole('button', { name: /^审阅修改/ }).click()
  await page.getByRole('button', { name: '刷新建议状态', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('已同步建议处理状态')
  await expect(page.locator('.changeCard').first().locator('.changeState')).toHaveText('已忽略')
  await page.getByRole('button', { name: '编辑正文', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '章节正文' })).toHaveValue(draft)
  await page.getByRole('button', { name: '保存正文', exact: true }).click()
  await expect(page.locator('.editorHeader')).toContainText('已保存')
  expect(state.writes[0].proposalReviews).toBeUndefined()
})
