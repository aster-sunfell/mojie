import { expect, test } from '@playwright/test'

const base = '开头：雨还在下。\n\n林舟游过河。\n\n两人在桥边整理工具。\n\n他拿起红灯。\n\n尾声：他们继续赶路。\n'
const proposed = base.replace('游过河', '走过桥').replace('红', '蓝')

async function mockReview(page, { currentContent = base } = {}) {
  const chapter = { id: 'c', projectId: 'p', title: '第二章 · 暴雨', chapterOrder: 2, version: 1, content: currentContent }
  const run = { id: 'r', projectId: 'p', targetChapterId: 'c', status: 'SUCCEEDED', proposalId: 'q',
    baseVersion: 1, queuedAt: '2026-10-01T00:00:00Z', lastEventSeq: 1, steps: [], sources: [],
    analysis: '人物设定中林舟不会游泳，建议改为过桥；灯笼颜色应与前文一致。' }
  const items = [['游过河', '走过桥'], ['红', '蓝']].map(([before, after], id) => {
    const start = base.indexOf(before), end = start + before.length
    return { id, before, after, start, end, lineNumber: base.slice(0, start).split('\n').length,
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
        Object.assign(items[choice.itemId], { status: choice.status, savedVersion: chapter.version, editedContent: choice.editedContent })
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

test('one continuous document supports partial saves, manual editing and reload', async ({ page }) => {
  const state = await mockReview(page)
  await page.goto('/projects/p/chapters/c')
  const changes = page.locator('.inlineChange')
  await expect(changes).toHaveCount(2)
  await expect(page.locator('.editorTabs')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '编辑正文', exact: true })).toHaveCount(0)
  const before = await changes.first().locator('.diffBefore').boundingBox()
  const after = await changes.first().locator('.diffAfter').boundingBox()
  expect(after.y).toBeGreaterThanOrEqual(before.y + before.height - 1)
  await expect(page.getByRole('textbox', { name: '章节正文', exact: true }).first()).toHaveValue(/开头：雨还在下/)
  await page.screenshot({ path: 'test-results/proposal-inline-desktop.png' })
  await changes.first().getByRole('button', { name: '应用', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '修改 1 后的正文' })).toHaveValue('林舟走过桥。')
  const tail = page.getByRole('textbox', { name: '章节正文', exact: true }).last()
  await tail.fill(`${await tail.inputValue()}用户发现新的细节，补写在这里。`)
  const manual = `${base.replace('游过河', '走过桥')}用户发现新的细节，补写在这里。`
  await page.getByRole('button', { name: '保存正文', exact: true }).click()
  await expect(page.locator('.editorHeader')).toContainText('已保存')
  expect(state.writes[0].content).toBe(manual)
  expect(state.writes[0].proposalReviews).toEqual([{ proposalId: 'q', decisions: [{ itemId: 0, status: 'APPLIED' }] }])
  await page.reload()
  await expect(changes.first()).toContainText('已保存 · 版本 2')
  await expect(changes.last().getByRole('button', { name: '应用', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '应用剩余', exact: true }).click()
  await page.getByRole('button', { name: '保存正文', exact: true }).click()
  await expect(page.locator('.editorHeader')).toContainText('已保存')
  expect(state.chapter.content).toBe(manual.replace('红', '蓝'))
  expect(state.proposal.status).toBe('APPLIED')
})

test('an edited suggestion only enters the draft when applied, and persists after saving', async ({ page }) => {
  const state = await mockReview(page)
  await page.goto('/projects/p/chapters/c')
  const edited = '傍晚，林舟沿山路走到旧桥。'
  const input = page.getByRole('textbox', { name: '修改 1 建议（应用前可编辑）' })
  await input.fill(edited)
  await expect(page.locator('.inlineChange').first()).toContainText('已调整 · 待应用')
  await expect(page.getByRole('button', { name: '保存正文', exact: true })).toBeDisabled()
  expect(state.chapter.content).toBe(base)
  await page.locator('.inlineChange').first().getByRole('button', { name: '应用', exact: true }).click()
  const applied = page.getByRole('textbox', { name: '修改 1 后的正文' })
  await expect(applied).toHaveValue(edited)
  await applied.fill(`${edited}他还检查了桥板。`)
  await page.getByRole('button', { name: '保存正文', exact: true }).click()
  await expect(page.locator('.editorHeader')).toContainText('已保存')
  expect(state.chapter.content).toBe(base.replace('林舟游过河。', `${edited}他还检查了桥板。`))
  expect(state.writes[0].proposalReviews[0].decisions[0].editedContent).toBe(edited)
  await page.reload()
  await expect(page.getByRole('textbox', { name: '修改 1 后的正文' })).toHaveValue(`${edited}他还检查了桥板。`)
  await expect(page.locator('.inlineChange').last().getByRole('button', { name: '应用', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '应用剩余', exact: true }).click()
  await page.getByRole('button', { name: '保存正文', exact: true }).click()
  await expect(page.locator('.editorHeader')).toContainText('已保存')
  expect(state.chapter.content).toContain('蓝灯。')
  expect(state.chapter.content).toContain('他还检查了桥板。')
})

test('apply all preserves a same-position manual change and allows direct conflict editing', async ({ page }) => {
  const manual = `${base.replace('红', '紫')}我自己补充的情节。`
  const state = await mockReview(page, { currentContent: manual })
  await page.goto('/projects/p/chapters/c')
  await expect(page.locator('.inlineChange')).toHaveCount(2)
  await expect(page.locator('.inlineChange').last()).toContainText('需要手动确认')
  await page.getByRole('button', { name: '应用全部', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('1 处已应用到草稿，1 处需要手动确认')
  const current = page.getByRole('textbox', { name: '修改 2 当前正文' })
  await expect(current).toHaveValue('他拿起紫灯。')
  await current.fill('他拿起墨色灯。')
  await page.getByRole('button', { name: '忽略剩余', exact: true }).click()
  await page.getByRole('button', { name: '保存正文', exact: true }).click()
  await expect(page.locator('.editorHeader')).toContainText('已保存')
  expect(state.chapter.content).toBe(manual.replace('游过河', '走过桥').replace('紫', '墨色'))
  expect(state.proposal.items.map(item => item.status)).toEqual(['APPLIED', 'IGNORED'])
})

test('mobile inline diff allows editing and navigation without tabs or overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockReview(page)
  await page.goto('/projects/p/chapters/c')
  await expect(page.getByRole('region', { name: '正文行内修改' })).toBeVisible()
  await expect(page.locator('.inlineChange')).toHaveCount(2)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  const proposal = page.getByRole('textbox', { name: '修改 1 建议（应用前可编辑）' })
  await proposal.fill('傍晚，林舟沿山路走到旧桥，确认桥板牢固后安全抵达对岸。')
  await expect(proposal).toBeEditable()
  expect(await proposal.evaluate(element => element.scrollHeight <= element.clientHeight + 1)).toBe(true)
  await page.screenshot({ path: 'test-results/proposal-inline-mobile.png' })
  await page.getByRole('button', { name: '助手结果', exact: true }).click()
  await page.getByRole('button', { name: /修改 2 · 第 7 行/ }).click()
  await expect(page.locator('.overlay')).toHaveCount(0)
  await expect(page.locator('.inlineChange').last()).toHaveClass(/selectedChange/)
})

test('refreshing server review choices keeps the current manual draft', async ({ page }) => {
  const state = await mockReview(page)
  await page.goto('/projects/p/chapters/c')
  await expect(page.locator('.inlineChange')).toHaveCount(2)
  await page.locator('.inlineChange').first().getByRole('button', { name: '应用', exact: true }).click()
  const applied = page.getByRole('textbox', { name: '修改 1 后的正文' })
  await applied.fill('林舟走过桥。我的新内容。')
  Object.assign(state.proposal.items[0], { status: 'IGNORED', savedVersion: 1 })
  await page.getByRole('button', { name: '刷新状态', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('已同步建议处理状态')
  await expect(page.locator('.inlineChange').first().locator('.changeState')).toHaveText('已忽略')
  await expect(page.getByRole('textbox', { name: '修改 1 当前正文' })).toHaveValue('林舟走过桥。我的新内容。')
  await page.getByRole('button', { name: '保存正文', exact: true }).click()
  await expect(page.locator('.editorHeader')).toContainText('已保存')
  expect(state.writes[0].proposalReviews).toBeUndefined()
  expect(state.chapter.content).toContain('我的新内容。')
})

test('continuous typing and manual resolution preserve text and clear discarded suggestion edits', async ({ page }) => {
  const state = await mockReview(page, { currentContent: base.replace('红', '紫') })
  await page.goto('/projects/p/chapters/c')
  const tail = page.getByRole('textbox', { name: '章节正文', exact: true }).last()
  await tail.focus()
  await tail.press('ControlOrMeta+End')
  await tail.pressSequentially('extra details')
  await expect(tail).toBeFocused()
  await expect(tail).toHaveValue(/extra details$/)
  await page.getByRole('textbox', { name: '修改 2 建议（应用前可编辑）' }).fill('他拿起蓝灯，检查了灯芯。')
  const current = page.getByRole('textbox', { name: '修改 2 当前正文' })
  await current.focus()
  await current.press('ControlOrMeta+End')
  await current.pressSequentially(' manual')
  await expect(current).toBeFocused()
  await expect(current).toHaveValue('他拿起紫灯。 manual')
  await page.getByRole('button', { name: '已手动处理', exact: true }).click()
  await page.getByRole('button', { name: '保存正文', exact: true }).click()
  await expect(page.locator('.editorHeader')).toContainText('已保存')
  expect(state.writes[0].proposalReviews[0].decisions).toEqual([
    { itemId: 1, status: 'APPLIED', editedContent: '他拿起紫灯。 manual' },
  ])
  expect(state.chapter.content).toBe(`${base.replace('他拿起红灯。', '他拿起紫灯。 manual')}extra details`)
  expect(await page.evaluate(() => window.dispatchEvent(new Event('beforeunload', { cancelable: true })))).toBe(true)
  await page.reload()
  await expect(page.getByRole('textbox', { name: '修改 2 后的正文' })).toHaveValue('他拿起紫灯。 manual')
})
