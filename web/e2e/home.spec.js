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
