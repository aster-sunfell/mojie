import { expect, test } from '@playwright/test'
import { writeFile, unlink } from 'node:fs/promises'

const marker = process.env.MOJIE_MAINTENANCE_FILE
test.skip(!marker, 'Run with a disposable MOJIE_MAINTENANCE_FILE shared with the test backend')
test.afterEach(async () => { if (marker) await unlink(marker).catch(() => {}) })

test('maintenance retains the draft, blocks API writes and keyboard saves, then restores editing', async ({ page, request }) => {
  const project = (await (await request.post('/api/v1/projects', { data: { title: '维护回归作品' } })).json()).data
  const chapter = (await (await request.post(`/api/v1/projects/${project.id}/chapters`, { data: { title: '维护回归章节' } })).json()).data
  await page.goto(`/projects/${project.id}/chapters/${chapter.id}`)
  const editor = page.locator('textarea.editor')
  await editor.fill('还没保存的正文，维护结束后继续写。')
  await writeFile(marker, 'maintenance\n')
  await expect(page.getByRole('dialog', { name: '墨界正在维护' })).toBeVisible({ timeout: 12000 })
  await expect(page.locator('.maintenance-workspace')).toHaveAttribute('inert', '')
  await expect(editor).toHaveValue('还没保存的正文，维护结束后继续写。')
  const rejected = await request.put(`/api/v1/chapters/${chapter.id}/content`, { data: { content: '应被阻止', expectedVersion: 1 } })
  expect(rejected.status()).toBe(503)
  expect((await rejected.json()).code).toBe('MAINTENANCE_MODE')
  let writes = 0
  page.on('request', outgoing => { if (outgoing.method() === 'PUT') writes++ })
  await page.keyboard.press('ControlOrMeta+s')
  expect(writes).toBe(0)
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog', { name: '墨界正在维护' })).toBeVisible()
  await page.screenshot({ path: 'test-results/maintenance-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: 'test-results/maintenance-mobile.png', fullPage: true })
  await unlink(marker)
  await page.getByRole('button', { name: '检查恢复状态' }).click()
  await expect(page.getByRole('dialog', { name: '墨界正在维护' })).toHaveCount(0)
  await expect(editor).toHaveValue('还没保存的正文，维护结束后继续写。')
  await page.locator('.editorHeader').getByRole('button', { name: '保存正文', exact: true }).click()
  await expect(page.getByText('版本 2', { exact: false }).first()).toBeVisible()
  const saved = (await (await request.get(`/api/v1/chapters/${chapter.id}`)).json()).data
  expect(saved.content).toBe('还没保存的正文，维护结束后继续写。')
  expect((await request.delete(`/api/v1/projects/${project.id}`)).status()).toBe(204)
})

test('a fresh tab during maintenance does not mount the workbench', async ({ page }) => {
  await writeFile(marker, 'maintenance\n')
  await page.goto('/')
  await expect(page.getByRole('dialog', { name: '墨界正在维护' })).toBeVisible()
  await expect(page.getByRole('button', { name: '新建作品', exact: true })).toHaveCount(0)
})
