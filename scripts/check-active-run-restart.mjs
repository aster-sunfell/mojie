#!/usr/bin/env node

// Run against a disposable local Mojie database. This test kills only the Java process it starts.
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
const baseContent = '林舟潜入深水。'
const proposedContent = '林舟借助绳索穿过深水。'
let backend
let projectId
let proposalId
let apiBase
let modelRequests = 0
let blockedRequest = false
let recentLog = ''

function response(res, content, stopReason) {
  const payload = JSON.stringify({
    id: 'msg_restart_' + modelRequests, type: 'message', role: 'assistant', model: 'claude-test',
    content, stop_reason: stopReason, stop_sequence: null,
    usage: { input_tokens: 100, output_tokens: 50 },
  })
  res.writeHead(200, { 'content-type': 'application/json', connection: 'close' })
  res.end(payload)
}

const fakeModel = http.createServer(async (req, res) => {
  if (req.method !== 'POST' || req.url !== '/v1/messages') {
    res.writeHead(404).end()
    return
  }
  let raw = ''
  for await (const chunk of req) raw += chunk
  const body = JSON.parse(raw)
  modelRequests++
  const initial = JSON.stringify(body.messages[0]?.content || '')
  if (initial.includes('重启中断测试')) {
    blockedRequest = true
    // Leave the HTTP request open until its owning Java process is killed.
    return
  }
  if (body.messages.length === 1) {
    const chapterId = initial.match(/目标章节 ID: ([0-9a-f-]+)/)?.[1]
    if (!chapterId) {
      res.writeHead(400).end()
      return
    }
    response(res, [
      { type: 'tool_use', id: 'read-target', name: 'read_chapter', input: { chapterId } },
      { type: 'tool_use', id: 'candidate', name: 'propose_chapter_edit',
        input: { proposedContent, reason: '补充符合前文的过河方式。' } },
    ], 'tool_use')
  } else {
    response(res, [{ type: 'text', text: '已完整阅读目标章，建议补充过河方式。' }], 'end_turn')
  }
})

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

function launch(port, modelPort) {
  const child = spawn(java, ['-jar', jar], {
    cwd: root,
    env: {
      ...process.env,
      MOJIE_PORT: String(port), MOJIE_BIND_HOST: '127.0.0.1',
      MOJIE_DB_URL: dbUrl, MOJIE_DB_USER: dbUser, MOJIE_DB_PASSWORD: dbPassword,
      MOJIE_MODEL_ID: 'claude-test', MOJIE_MODEL_CONTEXT_TOKENS: '200000',
      ANTHROPIC_API_KEY: 'local-restart-test',
      MOJIE_ANTHROPIC_BASE_URL: `http://127.0.0.1:${modelPort}`,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  for (const stream of [child.stdout, child.stderr])
    stream.on('data', chunk => { recentLog = (recentLog + chunk.toString()).slice(-2000) })
  return child
}

async function killStartedProcess() {
  if (!backend || backend.exitCode !== null || backend.signalCode !== null) return
  const exited = new Promise(resolve => backend.once('exit', resolve))
  backend.kill('SIGKILL')
  await exited
}

async function waitFor(check, label, timeoutMs = 20000) {
  const until = Date.now() + timeoutMs
  let lastError
  while (Date.now() < until) {
    try {
      if (await check()) return
    } catch (error) { lastError = error }
    if (backend && (backend.exitCode !== null || backend.signalCode !== null))
      throw new Error(`Java process exited before ${label}: ${recentLog}`)
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  throw new Error(`Timed out waiting for ${label}: ${lastError?.message || recentLog}`)
}

async function api(pathname, method = 'GET', body) {
  const reply = await fetch(apiBase + pathname, {
    method, signal: AbortSignal.timeout(5000),
    headers: body === undefined ? undefined : { 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (reply.status === 204) return null
  const value = await reply.json()
  if (!reply.ok) throw new Error(`${method} ${pathname}: ${reply.status} ${value.code || value.message || ''}`)
  return value.data
}

try {
  const modelPort = await listen(fakeModel)
  const apiPort = await freePort()
  apiBase = `http://127.0.0.1:${apiPort}/api/v1`
  backend = launch(apiPort, modelPort)
  await waitFor(async () => (await api('/health')).status === 'UP', 'first startup')

  projectId = (await api('/projects', 'POST', { title: 'M4 进程重启专用' })).id
  const chapterId = (await api(`/projects/${projectId}/chapters`, 'POST', { title: '深水' })).id
  const saved = await api(`/chapters/${chapterId}/content`, 'PUT',
    { content: baseContent, expectedVersion: 1 })
  const runA = (await api('/agent/runs', 'POST', {
    projectId, targetChapterId: chapterId, expectedVersion: saved.version,
    instruction: '持久成功测试：阅读目标章并提出整章建议。', clientRequestId: randomUUID(),
  })).runId
  let completed
  await waitFor(async () => {
    completed = await api(`/agent/runs/${runA}`)
    return completed.status === 'SUCCEEDED'
  }, 'successful run')
  proposalId = completed.proposalId
  if (!proposalId || (await api(`/edit-proposals/${proposalId}`)).status !== 'PENDING')
    throw new Error('The successful run did not publish a pending proposal')

  const runB = (await api('/agent/runs', 'POST', {
    projectId, targetChapterId: chapterId, expectedVersion: saved.version,
    instruction: '重启中断测试：等待模型返回。', clientRequestId: randomUUID(),
  })).runId
  await waitFor(async () => blockedRequest
    && (await api(`/agent/runs/${runB}`)).status === 'RUNNING', 'blocked active run')
  await killStartedProcess()
  backend = launch(apiPort, modelPort)
  await waitFor(async () => (await api('/health')).status === 'UP', 'restart')

  const previous = await api(`/agent/runs/${runA}`)
  const interrupted = await api(`/agent/runs/${runB}`)
  const proposal = await api(`/edit-proposals/${proposalId}`)
  const chapter = await api(`/chapters/${chapterId}`)
  if (previous.status !== 'SUCCEEDED' || previous.proposalId !== proposalId
      || proposal.status !== 'PENDING' || interrupted.status !== 'FAILED'
      || interrupted.errorCode !== 'SERVICE_RESTARTED' || interrupted.proposalId
      || chapter.content !== baseContent || chapter.version !== saved.version)
    throw new Error('Restart recovery changed a completed run or failed to settle the active run')
  const eventReply = await fetch(apiBase + `/agent/runs/${runB}/events?afterSeq=0`,
    { signal: AbortSignal.timeout(10000) })
  if (!eventReply.ok) throw new Error('Restart event replay failed')
  const eventStream = await eventReply.text()
  if (!/event:\s*run\.failed/.test(eventStream) || !eventStream.includes('SERVICE_RESTARTED'))
    throw new Error('Restart failure event was not replayed')
  if (modelRequests !== 3) throw new Error('Restart unexpectedly resumed the interrupted model request')

  console.log(JSON.stringify({
    successfulRun: previous.status, retainedProposal: proposal.status,
    interruptedRun: interrupted.status, errorCode: interrupted.errorCode,
    chapterVersion: chapter.version, modelRequests,
  }, null, 2))
  await api(`/edit-proposals/${proposalId}/reject`, 'POST')
  await api(`/projects/${projectId}`, 'DELETE')
  projectId = undefined
} finally {
  await killStartedProcess()
  await new Promise(resolve => fakeModel.close(resolve))
  if (projectId) console.error(`Test project retained for inspection: ${projectId}`)
}
