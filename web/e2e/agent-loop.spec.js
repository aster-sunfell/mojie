import { expect, test } from '@playwright/test'
import http from 'node:http'
import { randomUUID } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { existsSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'

const api = '/api/v1'
let projectId
let chapterId

test.beforeEach(async ({ request }) => {
  projectId = null
  chapterId = null
  projectId = (await (await request.post(`${api}/projects`, { data: { title: '浏览器 Agent 验收' } })).json()).data.id
  chapterId = (await (await request.post(`${api}/projects/${projectId}/chapters`,
    { data: { title: '目标章节' } })).json()).data.id
  await request.put(`${api}/chapters/${chapterId}/content`,
    { data: { content: '林舟游到了对岸。但人物设定说他不会游泳。', expectedVersion: 1 } })
})

test.afterEach(async ({ request }) => {
  if (projectId) expect((await request.delete(`${api}/projects/${projectId}`)).status()).toBe(204)
})

test('reload and broken event stream recover the completed run', async ({ page, request }) => {
  await page.route('**/agent/runs/*/events*', route => route.abort())
  await page.goto(`/projects/${projectId}/chapters/${chapterId}`)
  await expect(page.locator('.editor')).toHaveValue(/林舟游到了对岸/)
  await page.locator('#instruction').fill('E2E_SLOW 检查目标章节。')
  await page.getByRole('button', { name: '发送要求' }).click()
  await expect(page.locator('.runDetail')).toContainText('运行中')
  await page.reload()
  await expect(page.locator('.runDetail')).toContainText('已完成', { timeout: 30_000 })
  await expect(page.locator('.analysis')).toContainText('已核对目标章完整正文')
  const runs = (await (await request.get(`${api}/agent/runs?projectId=${projectId}&chapterId=${chapterId}`)).json()).data.items
  expect(runs).toHaveLength(1)
  expect(runs[0].status).toBe('SUCCEEDED')
  expect(page.locator('.sources button')).toHaveCount(1)
})

test('source view and inline diff preserve customized edits through saving and reload', async ({ page, request }) => {
  await page.goto(`/projects/${projectId}/chapters/${chapterId}`)
  await expect(page.locator('.editor')).toHaveValue(/林舟游到了对岸/)
  await page.locator('#instruction').fill('E2E_EDIT 检查设定并提交候选稿。')
  await page.getByRole('button', { name: '发送要求' }).click()
  const input = page.getByRole('textbox', { name: '修改 1 建议（应用前可编辑）' })
  await expect(input).toBeVisible()
  const customized = '林舟沿山路走到旧桥，安全到达对岸。但人物设定说他不会游泳。'
  await input.fill(customized)
  await page.locator('.sources button').first().click()
  await expect(page.locator('.drawer')).toContainText('历史来源')
  await expect(page.locator('.drawer')).toContainText('林舟游到了对岸')
  await page.locator('.drawer').getByRole('button', { name: '关闭' }).click()
  await expect(input).toHaveValue(customized)
  await page.getByRole('button', { name: '应用全部', exact: true }).click()
  const current = page.getByRole('textbox', { name: '修改 1 后的正文' })
  await current.fill(`${customized}用户补写的细节。`)
  await page.getByRole('button', { name: '保存正文', exact: true }).click()
  await expect(page.locator('.proposalReview')).toContainText('已保存 · 版本 3')
  await page.reload()
  await expect(current).toHaveValue(`${customized}用户补写的细节。`)
  const revisions = (await (await request.get(`${api}/chapters/${chapterId}/revisions`)).json()).data.items
  expect(revisions.filter(revision => revision.source === 'MIXED')).toHaveLength(1)
  const runs = (await (await request.get(`${api}/agent/runs?projectId=${projectId}&chapterId=${chapterId}`)).json()).data.items
  const proposal = (await (await request.get(`${api}/edit-proposals/${runs[0].proposalId}`)).json()).data
  expect(proposal.items[0].editedContent).toBe(customized)
})

test('browser cancellation reaches a terminal state without a proposal', async ({ page, request }) => {
  await page.goto(`/projects/${projectId}/chapters/${chapterId}`)
  await expect(page.locator('.editor')).toHaveValue(/林舟游到了对岸/)
  await page.locator('#instruction').fill('E2E_BLOCK 等待取消。')
  await page.getByRole('button', { name: '发送要求' }).click()
  await expect(page.locator('.runDetail')).toContainText('运行中')
  await page.getByRole('button', { name: '停止任务', exact: true }).click()
  await expect(page.locator('.runDetail')).toContainText('正在停止')
  await expect(page.locator('.runDetail')).toContainText('已停止', { timeout: 40_000 })
  const runs = (await (await request.get(`${api}/agent/runs?projectId=${projectId}&chapterId=${chapterId}`)).json()).data.items
  expect(runs[0].status).toBe('CANCELLED')
  expect(runs[0].proposalId).toBeFalsy()
})

test('two tabs keep the losing draft when the chapter version changes', async ({ page }) => {
  const second = await page.context().newPage()
  try {
    const path = `/projects/${projectId}/chapters/${chapterId}`
    await Promise.all([page.goto(path), second.goto(path)])
    await expect(page.locator('.editor')).toHaveValue(/林舟游到了对岸/)
    await expect(second.locator('.editor')).toHaveValue(/林舟游到了对岸/)
    await page.locator('.editor').fill('第一个标签页的新正文。')
    await second.locator('.editor').fill('第二个标签页保留的草稿。')
    await page.getByRole('button', { name: '保存正文', exact: true }).click()
    await expect(page.locator('.editorHeader')).toContainText('版本 3')
    await second.getByRole('button', { name: '保存正文', exact: true }).click()
    await expect(second.locator('.editorHeader')).toContainText('版本冲突')
    await expect(second.locator('.editor')).toHaveValue('第二个标签页保留的草稿。')
  } finally { await second.close() }
})

test('SSE rejects the 21st subscription and releases capacity after completion', async ({ request }) => {
  const run = (await (await request.post(`${api}/agent/runs`, { data: {
    projectId, targetChapterId: chapterId, expectedVersion: 2,
    instruction: 'E2E_SLOW 检查目标章节。', clientRequestId: randomUUID(),
  } })).json()).data
  const path = `${api}/agent/runs/${run.runId}/events`
  const streams = []
  const open = () => new Promise((resolve, reject) => {
    const socket = http.get(`http://127.0.0.1:18080${path}`, response => {
      let text = ''
      response.setEncoding('utf8')
      response.on('data', chunk => { text += chunk })
      const done = new Promise((end, fail) => {
        response.on('end', () => end(text))
        response.on('error', fail)
      })
      // Attach immediately, including when a later assertion fails and sockets are closed.
      done.catch(() => {})
      resolve({ response, socket, done })
    })
    socket.on('error', reject)
  })
  try {
    // Invalid cursors must not consume a subscription slot.
    for (let i = 0; i < 3; i++) expect((await request.get(`${path}?afterSeq=-1`)).status()).toBe(400)
    for (let i = 0; i < 20; i++) {
      const stream = await open()
      streams.push(stream)
      expect(stream.response.statusCode).toBe(200)
    }
    const excess = await request.get(path)
    expect(excess.status()).toBe(429)
    expect((await excess.json()).code).toBe('STREAM_CAPACITY_EXCEEDED')
    expect((await request.get(`${api}/health`)).status()).toBe(200)
    const replay = await Promise.all(streams.map(stream => stream.done))
    for (const events of replay) expect(events).toContain('event:run.succeeded')

    // Completion and servlet callbacks may both run; repeated replay must not leak permits.
    for (let i = 0; i < 25; i++) {
      const replayed = await request.get(path)
      expect(replayed.status()).toBe(200)
      expect(await replayed.text()).toContain('event:run.succeeded')
    }
  } finally {
    for (const stream of streams) stream.socket.destroy()
  }
})

test('reconnection does not let a delayed running snapshot replace the terminal result', async ({ page, request }) => {
  await page.goto(`/projects/${projectId}/chapters/${chapterId}`)
  await page.locator('#instruction').fill('E2E_SLOW 检查目标章节。')
  await page.getByRole('button', { name: '发送要求' }).click()
  await expect(page.locator('.runDetail')).toContainText('运行中')
  const runs = (await (await request.get(`${api}/agent/runs?projectId=${projectId}&chapterId=${chapterId}`)).json()).data.items
  const path = `${api}/agent/runs/${runs[0].id}`
  const oldSnapshot = await (await request.get(path)).json()
  expect(oldSnapshot.data.status).toBe('RUNNING')
  let heldRoute
  let heldOnce = false
  await page.route(`**${path}`, async route => {
    if (!heldOnce && route.request().method() === 'GET') {
      heldOnce = true
      heldRoute = route
      return
    }
    await route.continue()
  })
  try {
    await page.context().setOffline(true)
    await expect(page.locator('.runDetail')).toContainText('事件连接中断')
    await page.context().setOffline(false)
    await expect(page.locator('.runDetail')).toContainText('已完成', { timeout: 30_000 })
    expect(heldRoute).toBeTruthy()
    const analysis = await page.locator('.analysis').textContent()
    const heldRequest = heldRoute.request()
    const delivered = page.waitForResponse(response => response.request() === heldRequest)
    await heldRoute.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(oldSnapshot) })
    heldRoute = null
    await (await delivered).finished()
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
    await expect(page.locator('.analysis')).toHaveText(analysis)
    await expect(page.locator('.runDetail')).toContainText('已完成')
    await expect(page.locator('.runDetail')).not.toContainText('运行中')
  } finally {
    await page.context().setOffline(false)
    if (heldRoute) await heldRoute.abort()
  }
})

test('live audit rejects a different server model before creating data or calling it', async ({ request }) => {
  const capabilities = (await (await request.get(`${api}/system/capabilities`)).json()).data
  expect(capabilities.modelId).toBe('browser-test')
  const before = await (await request.get(`${api}/projects`)).json()
  const directory = mkdtempSync(path.join(tmpdir(), 'mojie-model-check-'))
  const output = path.join(directory, 'audit.json')
  try {
    const result = spawnSync('python3', ['../scripts/check-agent-loop-live.py',
      '--base-url', 'http://127.0.0.1:18080/api/v1', '--model', 'deepseek-ai/DeepSeek-V3.2',
      '--output', output, '--cases', 'readonly'], { encoding: 'utf8', timeout: 10_000 })
    expect(result.status).toBe(2)
    expect(result.stderr).toContain('Expected model deepseek-ai/DeepSeek-V3.2')
    expect(result.stderr).toContain('no tasks submitted')
    expect(existsSync(output)).toBe(false)
    expect(await (await request.get(`${api}/projects`)).json()).toEqual(before)
  } finally { rmSync(directory, { recursive: true, force: true }) }
})
