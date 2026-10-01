import { expect, test } from '@playwright/test'

async function mockWorkspace(page, { history = true,
  analysis = '已核对人物设定和前文章节。林舟的行动与已知经历相符，暂未发现明显矛盾。' } = {}) {
  const chapters = [
    { id: 'c', title: '第一章 · 雨夜', chapterOrder: 1, version: 3, content: ('林舟推开窗，看见雨落在街道上。\n\n').repeat(40) },
    { id: 'c2', title: '第二章 · 故人', chapterOrder: 2, version: 1, content: '次日，林舟与故人在桥边重逢。' },
  ]
  const runs = history ? [{ id: 'old', status: 'SUCCEEDED', baseVersion: 3, instruction: '检查人物设定与前文是否矛盾。',
    analysis,
    queuedAt: '2026-10-01T00:00:00Z', lastEventSeq: 1, steps: [], sources: [], modelCallCount: 2, toolCallCount: 3 }] : []
  const writes = []
  const cancelled = []
  await page.route('**/api/v1/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace('/api/v1', '')
    const data = value => route.fulfill({ json: { data: value } })
    if (path === '/projects/p') return data({ id: 'p', title: '林舟演示' })
    if (path === '/projects/p/chapters') return data({ items: chapters, nextCursor: null })
    if (path === '/system/capabilities') return data({ agentConfigured: true, maxInstructionCodePoints: 2000 })
    const chapter = chapters.find(item => path === `/chapters/${item.id}` || path === `/chapters/${item.id}/content`)
    if (chapter) {
      if (request.method() === 'PUT') { chapter.content = request.postDataJSON().content; chapter.version++ }
      return data(chapter)
    }
    if (path === '/agent/runs' && request.method() === 'GET')
      return data({ items: url.searchParams.get('chapterId') === 'c2' ? [] : runs, nextCursor: null })
    if (path === '/agent/runs' && request.method() === 'POST') {
      const body = request.postDataJSON()
      writes.push(body)
      const run = { id: 'active', status: 'RUNNING', instruction: body.instruction, baseVersion: body.expectedVersion,
        queuedAt: new Date().toISOString(), lastEventSeq: 1, steps: [], sources: [], modelCallCount: 1, toolCallCount: 0 }
      runs.unshift(run)
      return data({ runId: run.id, status: 'QUEUED' })
    }
    if (path.endsWith('/events')) return route.abort()
    const run = runs.find(item => path === `/agent/runs/${item.id}` || path === `/agent/runs/${item.id}/cancel`)
    if (run) {
      if (path.endsWith('/cancel')) { cancelled.push(run.id); run.cancelRequested = true }
      return data(run)
    }
    return route.fulfill({ status: 404, json: { message: `Unexpected request ${path}` } })
  })
  return { writes, cancelled }
}

test('desktop composer sits below the editor and stays in place while the chapter scrolls', async ({ page }) => {
  await mockWorkspace(page)
  await page.goto('/projects/p/chapters/c')
  const composer = page.getByRole('region', { name: '写作助手输入区' })
  const input = composer.getByRole('textbox', { name: '给写作助手的要求' })
  const editor = page.locator('.editorArea')
  const panel = page.getByRole('complementary', { name: '写作助手结果' })
  await expect(input).toBeVisible()
  await expect(page.locator('.analysis')).toContainText('已核对人物设定')
  const composerBox = await composer.boundingBox()
  const editorBox = await editor.boundingBox()
  const panelBox = await panel.boundingBox()
  expect(Math.abs(composerBox.x - editorBox.x)).toBeLessThan(1)
  expect(Math.abs(composerBox.width - editorBox.width)).toBeLessThan(1)
  expect(composerBox.y).toBeGreaterThanOrEqual(editorBox.y + editorBox.height - 1)
  expect(panelBox.x).toBeGreaterThanOrEqual(composerBox.x + composerBox.width - 1)
  expect(composerBox.y + composerBox.height).toBeLessThanOrEqual(720)
  expect(await page.locator('.steps').getAttribute('open')).toBeNull()
  await page.locator('.editor').evaluate(element => { element.scrollTop = element.scrollHeight })
  expect((await composer.boundingBox()).y).toBe(composerBox.y)
  await input.fill(('检查章节中人物的经历和时间顺序。\n').repeat(20))
  expect((await input.boundingBox()).height).toBeLessThanOrEqual(172)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(1280)
  await input.fill('检查当前章节与人物设定、前文经历是否矛盾；如有必要，请给出修改建议。')
  await page.screenshot({ path: 'test-results/workspace-desktop.png' })
})

for (const mobile of [false, true]) {
  test(`Markdown analysis renders and contains wide content on ${mobile ? 'mobile' : 'desktop'}`, async ({ page }) => {
    if (mobile) await page.setViewportSize({ width: 390, height: 844 })
    const longText = '核对人物经历与设定的一致性'.repeat(12)
    const analysis = '**简要分析：**\n\n第二章末句与人物设定存在明确冲突。\n\n'
      + '- **角色档案（林舟）**：林舟不会游泳。\n- 第一章设定：遇到急流会走桥。\n\n'
      + '> 建议安全走到对岸。\n\n'
      + `| 查阅来源 | 设定内容 |\n| --- | --- |\n| 人物档案 | ${longText} |\n\n`
      + `\`\`\`text\n${longText}\n\`\`\`\n\n`
      + `<https://example.com/${'long-path-'.repeat(30)}>`
    await mockWorkspace(page, { analysis })
    await page.goto('/projects/p/chapters/c')
    if (mobile) await page.getByRole('button', { name: '助手结果', exact: true }).click()
    const result = page.locator('.analysis')
    await expect(result.locator('strong').first()).toHaveText('简要分析：')
    await expect(result.getByRole('listitem')).toHaveCount(2)
    await expect(result.locator('blockquote')).toContainText('安全走到对岸')
    await expect(result.getByRole('table')).toHaveCount(1)
    expect(await result.locator('p').first().evaluate(element => getComputedStyle(element).whiteSpace)).toBe('normal')
    const table = result.getByLabel('表格内容')
    const code = result.getByLabel('代码块')
    for (const scroll of [table, code]) {
      expect(await scroll.evaluate(element => element.scrollWidth > element.clientWidth)).toBe(true)
      await scroll.evaluate(element => { element.scrollLeft = element.scrollWidth })
      expect(await scroll.evaluate(element => element.scrollLeft)).toBeGreaterThan(0)
    }
    expect(await result.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(mobile ? 390 : 1280)
    await result.scrollIntoViewIfNeeded()
    await page.screenshot({ path: `test-results/markdown-${mobile ? 'mobile' : 'desktop'}.png` })
  })
}

test('keyboard sending uses an icon, accepts line breaks, and switches to stop until server completion', async ({ page }) => {
  const state = await mockWorkspace(page, { history: false })
  await page.goto('/projects/p/chapters/c')
  const input = page.getByRole('textbox', { name: '给写作助手的要求' })
  await input.fill('核对人物经历')
  await input.press('Shift+Enter')
  await input.press('a')
  expect(state.writes).toHaveLength(0)
  const instruction = await input.inputValue()
  expect(instruction).toContain('\n')
  await input.press('Enter')
  await expect(page.getByRole('button', { name: '停止任务', exact: true })).toBeVisible()
  await expect(input).toHaveValue('')
  expect(state.writes).toHaveLength(1)
  expect(state.writes[0]).toMatchObject({ targetChapterId: 'c', expectedVersion: 3, instruction })
  await expect(page.locator('.requestMessage')).toContainText('核对人物经历')
  await page.getByRole('button', { name: '停止任务', exact: true }).click()
  await expect(page.getByRole('button', { name: '正在停止', exact: true })).toBeDisabled()
  expect(state.cancelled).toEqual(['active'])
})

test('mobile drawers preserve chapter requirements and return focus without horizontal overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockWorkspace(page)
  await page.goto('/projects/p/chapters/c')
  const input = page.getByRole('textbox', { name: '给写作助手的要求' })
  await expect(input).toBeVisible()
  await input.fill('第一章的待发送要求')
  const results = page.getByRole('button', { name: '助手结果', exact: true })
  await results.click()
  const drawer = page.getByRole('dialog', { name: '写作助手', exact: true })
  await expect(drawer).toBeVisible()
  await expect(drawer.locator('.analysis')).toContainText('已核对人物设定')
  await page.keyboard.press('Escape')
  await expect(drawer).toHaveCount(0)
  await expect(results).toBeFocused()
  await expect(input).toHaveValue('第一章的待发送要求')
  await page.getByRole('button', { name: '章节', exact: true }).click()
  await page.getByRole('dialog', { name: '章节导航', exact: true }).getByRole('button', { name: /第二章.*v1/ }).click()
  await expect(page.locator('.editorHeader h1')).toHaveText('第二章 · 故人')
  await input.fill('第二章的待发送要求')
  await page.getByRole('button', { name: '章节', exact: true }).click()
  await page.getByRole('dialog', { name: '章节导航', exact: true }).getByRole('button', { name: /第一章.*v3/ }).click()
  await expect(input).toHaveValue('第一章的待发送要求')
  const composer = page.getByRole('region', { name: '写作助手输入区' })
  expect((await composer.boundingBox()).y + (await composer.boundingBox()).height).toBeLessThanOrEqual(844)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  await page.screenshot({ path: 'test-results/workspace-mobile.png' })
  await page.setViewportSize({ width: 390, height: 500 })
  await expect(input).toBeVisible()
  await expect(page.getByRole('button', { name: '发送要求', exact: true })).toBeVisible()
  expect((await composer.boundingBox()).y + (await composer.boundingBox()).height).toBeLessThanOrEqual(500)
})
