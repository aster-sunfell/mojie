// Start the real Java API and Vite with a deterministic local model. Only use a disposable test database.
import { spawn, spawnSync } from 'node:child_process'
import http from 'node:http'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const jdbc = process.env.MOJIE_TEST_JDBC_URL
if (!jdbc || /\/(?:mojie|postgres)(?:\?|$)/.test(jdbc))
  throw new Error('MOJIE_TEST_JDBC_URL must name a dedicated disposable database, not mojie or postgres')

const built = spawnSync('mvn', ['-B', '-ntp', '-DskipTests', '-f', path.join(root, 'backend/pom.xml'), 'package'],
  { cwd: root, stdio: 'inherit' })
if (built.status !== 0) throw new Error(`Backend build failed: ${built.status}`)

let serial = 0
const model = http.createServer(async (request, reply) => {
  if (request.method !== 'POST' || request.url !== '/v1/messages') { reply.writeHead(404).end(); return }
  try {
    const chunks = []
    for await (const chunk of request) chunks.push(chunk)
    const body = JSON.parse(Buffer.concat(chunks).toString('utf8'))
    const blocks = body.messages.flatMap(message => message.content || [])
    const instruction = body.messages.filter(message => message.role === 'user').flatMap(message => message.content || []).filter(block => block.type === 'text').at(-1)?.text || ''
    const targetId = instruction.match(/TARGET=([a-f0-9-]+)/)?.[1]
      || instruction.match(/(?:目标章节 ID|本轮优先参考章节 ID): ([a-f0-9-]+)/)?.[1]
    const searchEdit = instruction.includes('E2E_SEARCH_EDIT')
    if ((!targetId && !searchEdit) || !instruction.includes('E2E_')) {
      const remembered = blocks.some(block => block.type === 'text' && block.text?.includes('怕火'))
      reply.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ id: `chat-${++serial}`, type: 'message', role: 'assistant', model: 'browser-test', content: [{ type: 'text', text: remembered ? '林舟怕火，因此避开火光。' : '可以一起讨论大纲。' }], stop_reason: 'end_turn', stop_sequence: null, usage: { input_tokens: 20, output_tokens: 20 } }))
      return
    }
    if (instruction.includes('E2E_SLOW') && !blocks.some(block => block.type === 'tool_result'))
      await new Promise(resolve => setTimeout(resolve, 7_000))
    if (instruction.includes('E2E_BLOCK') && !blocks.some(block => block.type === 'tool_result'))
      await new Promise(resolve => setTimeout(resolve, 30_000))

    const currentTurn = body.messages.findLastIndex(message => message.role === 'user'
      && message.content?.some(block => block.type === 'text'))
    const replies = body.messages.slice(currentTurn + 1).flatMap(message => message.content || [])
      .filter(block => block.type === 'tool_result')
    let content
    let stopReason
    if (searchEdit && !replies.length) {
      content = [{ type: 'tool_use', id: `browser-search-${++serial}`, name: 'search_chapters', input: { keyword: '游泳' } }]
      stopReason = 'tool_use'
    } else if (searchEdit && !replies.some(block => block.tool_use_id.startsWith('browser-read-'))) {
      const search = JSON.parse(replies.at(-1).content)
      const match = search.data.matches.find(match => match.chapterId === targetId) || search.data.matches[0]
      content = [{ type: 'tool_use', id: `browser-read-${++serial}`, name: 'read_chapter', input: { chapterId: match.chapterId } }]
      stopReason = 'tool_use'
    } else if (!replies.length) {
      content = [{ type: 'tool_use', id: `browser-read-${++serial}`, name: 'read_chapter', input: { chapterId: targetId } }]
      stopReason = 'tool_use'
    } else if ((instruction.includes('E2E_EDIT') || searchEdit) && !replies.some(block => block.tool_use_id.startsWith('browser-edit-'))) {
      const read = JSON.parse(replies.findLast(block => block.tool_use_id.startsWith('browser-read-')).content)
      const proposedContent = read.data.content.replace('林舟游到了对岸', '林舟沿桥走到了对岸')
      content = [{ type: 'tool_use', id: `browser-edit-${++serial}`, name: 'propose_edit',
        input: { chapterId: read.data.chapterId, expectedVersion: read.data.version, proposedContent, reason: '人物设定表明林舟不会游泳，改为过桥。' } }]
      stopReason = 'tool_use'
    } else {
      content = [{ type: 'text', text: '已核对目标章完整正文与任务证据。' }]
      stopReason = 'end_turn'
    }
    const encoded = JSON.stringify({ id: `browser-message-${++serial}`, type: 'message', role: 'assistant',
      model: 'browser-test', content, stop_reason: stopReason, stop_sequence: null,
      usage: { input_tokens: 20, output_tokens: 20 } })
    if (!reply.destroyed) reply.writeHead(200, { 'content-type': 'application/json', connection: 'close' }).end(encoded)
  } catch (error) {
    if (!reply.destroyed) reply.writeHead(500).end(error.message)
  }
})

await new Promise((resolve, reject) => { model.once('error', reject); model.listen(18081, '127.0.0.1', resolve) })
const backend = spawn('java', ['-jar', path.join(root, 'backend/target/mojie-agent-core-0.1.0-SNAPSHOT-exec.jar')], {
  cwd: root,
  env: { ...process.env, MOJIE_DB_URL: jdbc, MOJIE_DB_USER: process.env.MOJIE_TEST_DB_USER || 'mojie',
    MOJIE_DB_PASSWORD: process.env.MOJIE_TEST_DB_PASSWORD || 'mojie_dev_only',
    MOJIE_PORT: '18080', MOJIE_BIND_HOST: '127.0.0.1', MOJIE_MODEL_API_PATH: '/v1/messages',
    MOJIE_MODEL_BASE_URL: 'http://127.0.0.1:18081', MOJIE_MODEL_API_KEY: 'browser-test-key',
    MOJIE_MODEL_ID: 'browser-test', MOJIE_MODEL_CONTEXT_TOKENS: '200000' },
  stdio: ['ignore', 'pipe', 'pipe'],
})
let recentLog = ''
for (const stream of [backend.stdout, backend.stderr])
  stream.on('data', chunk => { recentLog = (recentLog + chunk.toString()).slice(-3000) })
let vite
let stopping = false
function shutdown() {
  if (stopping) return
  stopping = true
  vite?.kill('SIGTERM')
  backend.kill('SIGTERM')
  model.close()
}
process.on('SIGTERM', shutdown)
process.on('SIGINT', shutdown)

let healthy = false
for (let attempt = 0; attempt < 300; attempt++) {
  if (backend.exitCode !== null) throw new Error(`Java exited before readiness: ${recentLog}`)
  try { healthy = (await (await fetch('http://127.0.0.1:18080/api/v1/health')).json()).data.status === 'UP' }
  catch { /* Wait for Java startup. */ }
  if (healthy) break
  await new Promise(resolve => setTimeout(resolve, 100))
}
if (!healthy) throw new Error(`Java did not become healthy: ${recentLog}`)
vite = spawn('npm', ['run', 'dev', '--', '--port', '15173', '--strictPort'], {
  cwd: path.join(root, 'web'),
  env: { ...process.env, MOJIE_API_URL: 'http://127.0.0.1:18080', MOJIE_HMR_CLIENT_PORT: '15173' },
  stdio: 'inherit',
})
backend.on('exit', (code, signal) => {
  if (!stopping && code !== 0 && signal !== 'SIGTERM') {
    process.stderr.write(`Java exited during browser tests: ${recentLog}\n`)
    process.exit(1)
  }
})
