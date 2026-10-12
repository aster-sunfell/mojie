import { expect, test } from '@playwright/test'

async function start(page, request, instruction) {
  const project = (await (await request.post('http://127.0.0.1:18080/api/v1/projects',
    { data: { title: `流式验证-${Date.now()}` } })).json()).data
  await page.goto(`/projects/${project.id}`)
  await page.getByRole('textbox', { name: '对话消息' }).fill(instruction)
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.locator('.assistantDraft')).toContainText('可以一起')
  const runId = await page.locator('.conversationMessage.assistant').getAttribute('data-run-id')
  return { project, runId }
}

test('upstream text is visible before completion, survives reload, and becomes one final answer', async ({ page, request }) => {
  const { runId } = await start(page, request, 'E2E_STREAM 讨论大纲')
  const running = (await (await request.get(`http://127.0.0.1:18080/api/v1/agent/runs/${runId}`)).json()).data
  expect(running.status).toBe('RUNNING')
  expect(running.output.text).toBe('可以一起讨')
  expect(running.analysis).toBeNull()
  await page.reload()
  await expect(page.locator('.conversationMessages')).toContainText('可以一起讨')
  await expect(page.locator('.assistantAnswer')).toHaveText('可以一起讨论大纲。')
  await expect(page.locator('.assistantDraft')).toHaveCount(0)
  await expect(page.locator('.conversationMessage.assistant')).toHaveCount(1)
  await page.screenshot({ path: 'test-results/model-streaming-completed.png' })
})

test('stopping a live model stream retains an unfinished draft without publishing an answer', async ({ page, request }) => {
  const { runId } = await start(page, request, 'E2E_STREAM 讨论大纲')
  await page.getByRole('button', { name: '停止', exact: true }).click()
  await expect(page.locator('.turnFailure')).toContainText('已停止')
  await expect(page.locator('.assistantDraft')).toContainText('未完成的回复')
  await expect(page.locator('.assistantAnswer')).toHaveCount(0)
  const run = (await (await request.get(`http://127.0.0.1:18080/api/v1/agent/runs/${runId}`)).json()).data
  expect(run.status).toBe('CANCELLED')
  expect(run.analysis).toBeNull()
})

test('an upstream stream ending early displays its partial draft and a retry action', async ({ page, request }) => {
  const { runId } = await start(page, request, 'E2E_STREAM_FAIL 讨论大纲')
  await expect(page.locator('.turnFailure')).toContainText('失败')
  await expect(page.locator('.assistantDraft')).toContainText('未完成的回复')
  await expect(page.getByRole('button', { name: '重试本轮' })).toBeVisible()
  await expect(page.locator('.assistantAnswer')).toHaveCount(0)
  const run = (await (await request.get(`http://127.0.0.1:18080/api/v1/agent/runs/${runId}`)).json()).data
  expect(run.status).toBe('FAILED')
  expect(run.analysis).toBeNull()
})
