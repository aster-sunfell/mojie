import { expect, test } from '@playwright/test'

const projects = Array.from({ length: 25 }, (_, index) => ({
  id: `project-${index}`, title: index === 24 ? '林舟演示' : `作品 ${index + 1}`,
  updatedAt: '2026-09-30T00:00:00Z',
}))

test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/v1/projects' && route.request().method() === 'GET') {
      const keyword = url.searchParams.get('keyword') || ''
      const items = projects.filter(project => project.title.includes(keyword))
      const offset = Number(url.searchParams.get('cursor') || 0)
      return route.fulfill({ json: { data: { items: items.slice(offset, offset + 20),
        nextCursor: items.length > offset + 20 ? String(offset + 20) : null } } })
    }
    return route.fulfill({ status: 404, json: { message: 'Unexpected test request' } })
  })
})

test('search and creation have separate controls and keyboard behavior', async ({ page }) => {
  await page.goto('/')
  const search = page.getByRole('searchbox', { name: '搜索作品名称' })
  const create = page.getByRole('button', { name: '新建作品', exact: true })
  const writes = []
  page.on('request', request => { if (request.method() === 'POST') writes.push(request.url()) })
  await expect(page.getByRole('region', { name: '作品列表' }).getByRole('button', { name: /作品/ })).toHaveCount(20)
  const createBox = await create.boundingBox()
  const searchBox = await search.boundingBox()
  expect(createBox.y + createBox.height).toBeLessThan(searchBox.y)
  await search.fill('林舟')
  await search.press('Enter')
  await expect(page.getByText('林舟演示', { exact: true })).toBeVisible()
  expect(writes).toHaveLength(0)
  await create.click()
  const dialog = page.getByRole('dialog', { name: '新建作品' })
  const title = dialog.getByRole('textbox', { name: '作品名称' })
  await expect(title).toHaveValue('')
  await expect(title).toBeFocused()
  await title.fill('我的新作品')
  await page.keyboard.press('Shift+Tab')
  await expect(dialog.getByRole('button', { name: '创建作品' })).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(title).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(dialog).toHaveCount(0)
  await expect(create).toBeFocused()
  await expect(search).toHaveValue('林舟')
  await page.getByRole('search').getByRole('button', { name: '清空搜索' }).click()
  await expect(search).toHaveValue('')
  await expect(search).toBeFocused()
  await page.screenshot({ path: 'test-results/home-desktop.png', fullPage: true })
})

test('mobile home keeps the search and creation controls inside the viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  await expect(page.getByText('作品 1', { exact: true })).toBeVisible()
  const search = page.getByRole('searchbox')
  const create = page.getByRole('button', { name: '新建作品', exact: true })
  await expect(search).toBeVisible()
  await expect(create).toBeVisible()
  const createBox = await create.boundingBox()
  const searchBox = await search.boundingBox()
  expect(createBox.y + createBox.height).toBeLessThan(searchBox.y)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  await create.click()
  await expect(page.getByRole('textbox', { name: '作品名称' })).toBeFocused()
  await page.getByRole('dialog').getByRole('button', { name: '取消' }).click()
  await page.screenshot({ path: 'test-results/home-mobile.png', fullPage: true })
})

test('delete stays inside the card and its tooltip is centered above the button', async ({ page }) => {
  await page.goto('/')
  const card = page.getByRole('article', { name: '作品：作品 1', exact: true })
  const remove = card.getByRole('button', { name: '删除', exact: true })
  await expect.poll(() => remove.evaluate(button => getComputedStyle(button.parentElement.parentElement).opacity)).toBe('0')
  await card.hover()
  await expect(page.getByRole('tooltip')).toHaveCount(0)
  const target = await remove.boundingBox()
  await page.mouse.move(target.x + target.width / 2, target.y + target.height / 2)
  const tooltip = page.getByRole('tooltip')
  await expect(tooltip).toBeVisible()
  const cardBox = await card.boundingBox()
  const buttonBox = await remove.boundingBox()
  const tooltipBox = await tooltip.boundingBox()
  expect(buttonBox.x).toBeGreaterThan(cardBox.x)
  expect(buttonBox.y).toBeGreaterThan(cardBox.y)
  expect(buttonBox.x + buttonBox.width).toBeLessThan(cardBox.x + cardBox.width)
  expect(buttonBox.y + buttonBox.height).toBeLessThan(cardBox.y + cardBox.height)
  expect(Math.abs(tooltipBox.x + tooltipBox.width / 2 - buttonBox.x - buttonBox.width / 2)).toBeLessThan(1)
  expect(tooltipBox.y + tooltipBox.height).toBeLessThan(buttonBox.y)
  expect(await remove.getAttribute('aria-describedby')).toBe(await tooltip.getAttribute('id'))
  await page.screenshot({ path: 'test-results/home-delete-tooltip.png' })

  let deletes = 0
  await page.route('**/api/v1/projects/project-0', async route => {
    expect(route.request().method()).toBe('DELETE')
    deletes++
    await route.fulfill({ status: 204 })
  })
  await remove.click()
  await expect(page.getByRole('dialog', { name: '删除作品' })).toBeVisible()
  await expect(page).toHaveURL(/\/$/)
  expect(deletes).toBe(0)
  await page.getByRole('button', { name: '确认删除' }).click()
  await expect(card).toHaveCount(0)
  expect(deletes).toBe(1)
})

test('keyboard users can reach deletion and return to it after cancelling', async ({ page }) => {
  await page.goto('/')
  const card = page.getByRole('article', { name: '作品：作品 1', exact: true })
  const remove = card.getByRole('button', { name: '删除', exact: true })
  await card.getByRole('button', { name: '打开作品《作品 1》', exact: true }).focus()
  await page.keyboard.press('Tab')
  await expect(remove).toBeFocused()
  await expect.poll(() => remove.evaluate(button => getComputedStyle(button.parentElement.parentElement).opacity)).toBe('1')
  await expect(page.getByRole('tooltip')).toBeVisible()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('dialog', { name: '删除作品' })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(remove).toBeFocused()
  await expect(page.getByRole('dialog')).toHaveCount(0)
})

test('tooltip flips below deletion when the card is near the top of the viewport', async ({ page }) => {
  await page.goto('/')
  const card = page.getByRole('article', { name: '作品：作品 1', exact: true })
  await card.evaluate(element => element.scrollIntoView({ block: 'start' }))
  await card.hover()
  const remove = card.getByRole('button', { name: '删除', exact: true })
  await remove.hover()
  const tooltip = page.getByRole('tooltip')
  await expect(tooltip).toBeVisible()
  await expect(tooltip).toHaveAttribute('data-placement', 'bottom')
  const tooltipBox = await tooltip.boundingBox()
  const buttonBox = await remove.boundingBox()
  expect(tooltipBox.y).toBeGreaterThan(buttonBox.y + buttonBox.height)
  expect(tooltipBox.y).toBeGreaterThanOrEqual(8)
})

test.describe('touch cards', () => {
  test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true })

  test('deletion is always available and a long title stays clear of the action', async ({ page }) => {
    const title = '这是一个很长的作品名称，用于检查作品卡片在手机上的排版与删除按钮位置'.repeat(2)
    await page.route('**/api/v1/projects?*', route => route.fulfill({ json: { data: {
      items: [{ id: 'long', title, updatedAt: '2026-09-30T00:00:00Z' }], nextCursor: null,
    } } }))
    await page.goto('/')
    const card = page.getByRole('article')
    const remove = card.getByRole('button', { name: '删除', exact: true })
    await expect.poll(() => remove.evaluate(button => getComputedStyle(button.parentElement.parentElement).opacity)).toBe('1')
    const cardBox = await card.boundingBox()
    const buttonBox = await remove.boundingBox()
    const titleBox = await card.locator('strong').boundingBox()
    expect(buttonBox.x + buttonBox.width).toBeLessThan(cardBox.x + cardBox.width)
    expect(buttonBox.y + buttonBox.height).toBeLessThan(cardBox.y + cardBox.height)
    expect(titleBox.x + titleBox.width).toBeLessThan(buttonBox.x)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
    await page.screenshot({ path: 'test-results/home-touch-card.png', fullPage: true })
    await remove.tap()
    await expect(page.getByRole('dialog', { name: '删除作品' })).toBeVisible()
    await page.getByRole('button', { name: '取消', exact: true }).tap()
    await expect(card).toBeVisible()
  })
})
