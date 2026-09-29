#!/usr/bin/env node

// Exercise HTTP cancellation against a real Java process and a disposable PostgreSQL database.
import { spawn } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import http from 'node:http'
import net from 'node:net'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const jar = process.env.MOJIE_EXEC_JAR || path.join(root, 'backend/target/mojie-agent-core-0.1.0-SNAPSHOT-exec.jar')
const java = process.env.MOJIE_JAVA_BIN || 'java'
const dbUrl = process.env.MOJIE_TEST_JDBC_URL
if (!dbUrl) throw new Error('Set MOJIE_TEST_JDBC_URL to a dedicated disposable PostgreSQL database')
const dbUser = process.env.MOJIE_TEST_DB_USER || 'mojie'
const dbPassword = process.env.MOJIE_TEST_DB_PASSWORD || 'mojie_dev_only'
const pendingModelReplies = new Set()
let modelRequests = 0
let backend
const projects = new Set()
let apiBase
let recentLog = ''

const fakeModel = http.createServer(async (request, reply) => {
  if (request.method !== 'POST' || request.url !== '/v1/messages') {
    reply.writeHead(404).end()
    return
  }
  for await (const _chunk of request) { /* Consume the model request before holding its response. */ }
  modelRequests++
  pendingModelReplies.add(reply)
  reply.on('close', () => pendingModelReplies.delete(reply))
})

function releaseModelReplies() {
  for (const reply of pendingModelReplies) {
    if (reply.destroyed) continue
    const body = JSON.stringify({
      id: 'msg_cancel_test', type: 'message', role: 'assistant', model: 'claude-test',
      content: [{ type: 'text', text: '这份迟到的响应不能推进已取消的任务。' }],
      stop_reason: 'end_turn', stop_sequence: null,
      usage: { input_tokens: 10, output_tokens: 20 },
    })
    reply.writeHead(200, { 'content-type': 'application/json', connection: 'close' })
    reply.end(body)
  }
}

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => resolve(server.address().port))
  })
}

async function freePort() {
  const server = net.createServer()
  const port = await listen(server)
  await new Promise(resolve => server.close(resolve))
  return port
}

async function stopBackend() {
  if (!backend || backend.exitCode !== null || backend.signalCode !== null) return
  const exited = new Promise(resolve => backend.once('exit', resolve))
  backend.kill('SIGKILL')
  await exited
}

async function waitFor(check, label, timeoutMs = 20000) {
  const until = Date.now() + timeoutMs
  let lastError
  while (Date.now() < until) {
    try { if (await check()) return }
    catch (error) { lastError = error }
    if (backend && (backend.exitCode !== null || backend.signalCode !== null))
      throw new Error(`Java process exited before ${label}: ${recentLog}`)
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  throw new Error(`Timed out waiting for ${label}: ${lastError?.message || recentLog}`)
}

async function api(pathname, method = 'GET', body) {
  const response = await fetch(apiBase + pathname, {
    method, signal: AbortSignal.timeout(5000),
    headers: body === undefined ? undefined : { 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (response.status === 204) return null
  const value = await response.json()
  if (!response.ok) throw new Error(`${method} ${pathname}: ${response.status} ${value.code || value.message || ''}`)
  return value.data
}

async function createBlockedRun(title) {
  const projectId = (await api('/projects', 'POST', { title })).id
  projects.add(projectId)
  const chapterId = (await api(`/projects/${projectId}/chapters`, 'POST', { title: '目标章' })).id
  const saved = await api(`/chapters/${chapterId}/content`, 'PUT', { content: '原文保持不变。', expectedVersion: 1 })
  const runId = (await api('/agent/runs', 'POST', {
    projectId, targetChapterId: chapterId, expectedVersion: saved.version,
    instruction: '检查目标章并等待模型响应。', clientRequestId: randomUUID(),
  })).runId
  return { projectId, chapterId, version: saved.version, runId }
}

async function removeProject(projectId) {
  await api(`/projects/${projectId}`, 'DELETE')
  projects.delete(projectId)
}

try {
  const modelPort = await listen(fakeModel)
  const apiPort = await freePort()
  apiBase = `http://127.0.0.1:${apiPort}/api/v1`
  backend = spawn(java, ['-jar', jar], {
    cwd: root,
    env: {
      ...process.env,
      MOJIE_PORT: String(apiPort), MOJIE_BIND_HOST: '127.0.0.1',
      MOJIE_DB_URL: dbUrl, MOJIE_DB_USER: dbUser, MOJIE_DB_PASSWORD: dbPassword,
      MOJIE_MODEL_ID: 'claude-test', MOJIE_MODEL_CONTEXT_TOKENS: '200000',
      ANTHROPIC_API_KEY: 'local-cancel-test',
      MOJIE_ANTHROPIC_BASE_URL: `http://127.0.0.1:${modelPort}`,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  for (const stream of [backend.stdout, backend.stderr])
    stream.on('data', chunk => { recentLog = (recentLog + chunk.toString()).slice(-2000) })
  await waitFor(async () => (await api('/health')).status === 'UP', 'startup')

  const running = await createBlockedRun('HTTP 运行取消专用')
  await waitFor(async () => modelRequests === 1 && (await api(`/agent/runs/${running.runId}`)).status === 'RUNNING',
    'blocked model request')

  const cancellation = await api(`/agent/runs/${running.runId}/cancel`, 'POST')
  if (!['RUNNING', 'CANCELLED'].includes(cancellation.status))
    throw new Error(`Unexpected cancellation receipt: ${cancellation.status}`)
  releaseModelReplies()
  let finished
  await waitFor(async () => {
    finished = await api(`/agent/runs/${running.runId}`)
    return finished.status === 'CANCELLED'
  }, 'cancelled run', 10000)
  const chapter = await api(`/chapters/${running.chapterId}`)
  if (finished.proposalId || finished.steps.length || finished.sources.length
      || chapter.content !== '原文保持不变。' || chapter.version !== running.version || modelRequests !== 1)
    throw new Error('Cancellation changed chapter content, published output or continued the model loop')
  const events = await fetch(apiBase + `/agent/runs/${running.runId}/events?afterSeq=0`,
    { signal: AbortSignal.timeout(10000) })
  if (!events.ok || !/event:\s*run\.cancelled/.test(await events.text()))
    throw new Error('Cancelled event was not replayed through SSE')
  await removeProject(running.projectId)

  const second = await createBlockedRun('HTTP 排队取消占槽一')
  const third = await createBlockedRun('HTTP 排队取消占槽二')
  await waitFor(async () => modelRequests === 3
    && (await api(`/agent/runs/${second.runId}`)).status === 'RUNNING'
    && (await api(`/agent/runs/${third.runId}`)).status === 'RUNNING', 'two occupied workers')
  const queued = await createBlockedRun('HTTP 排队取消目标')
  await waitFor(async () => (await api(`/agent/runs/${queued.runId}`)).status === 'QUEUED', 'queued run')
  const queuedCancellation = await api(`/agent/runs/${queued.runId}/cancel`, 'POST')
  if (queuedCancellation.status !== 'CANCELLED'
      || (await api(`/agent/runs/${queued.runId}`)).status !== 'CANCELLED' || modelRequests !== 3)
    throw new Error('Queued cancellation did not finish without calling the model')
  const queuedEvents = await fetch(apiBase + `/agent/runs/${queued.runId}/events?afterSeq=0`,
    { signal: AbortSignal.timeout(10000) })
  if (!queuedEvents.ok || !/event:\s*run\.cancelled/.test(await queuedEvents.text()))
    throw new Error('Queued cancellation event was not replayed through SSE')
  for (const active of [second, third]) await api(`/agent/runs/${active.runId}/cancel`, 'POST')
  releaseModelReplies()
  await waitFor(async () => (await api(`/agent/runs/${second.runId}`)).status === 'CANCELLED'
    && (await api(`/agent/runs/${third.runId}`)).status === 'CANCELLED', 'worker cleanup')
  for (const item of [second, third, queued]) await removeProject(item.projectId)

  console.log(JSON.stringify({ runningStatus: finished.status, queuedStatus: queuedCancellation.status,
    chapterVersion: chapter.version, modelRequests, proposalId: finished.proposalId,
    runningCancelReceipt: cancellation.status }, null, 2))
} finally {
  releaseModelReplies()
  await stopBackend()
  await new Promise(resolve => fakeModel.close(resolve))
  if (projects.size) console.error(`Test projects retained for inspection: ${[...projects].join(', ')}`)
}
