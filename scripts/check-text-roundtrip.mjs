#!/usr/bin/env node

// AC-19: verify text survives the HTTP, PostgreSQL and immutable-revision path.
const base = (process.env.MOJIE_API_URL || 'http://127.0.0.1:8080/api/v1').replace(/\/$/, '')

async function api(path, method = 'GET', body) {
  const response = await fetch(`${base}${path}`, {
    method,
    headers: body === undefined ? undefined : { 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const result = await response.json()
  if (!response.ok) throw new Error(`${method} ${path}: ${response.status} ${result.code || ''} ${result.message || ''}`)
  return result.data
}

const project = await api('/projects', 'POST', { title: 'AC-19 文本往返' })
const chapter = await api(`/projects/${project.id}/chapters`, 'POST', { title: '换行与 Unicode' })
const input = '\r\n中文😀\r\n\r\n第二段🚀\r第三行\r\n\r\n'
const expected = '\n中文😀\n\n第二段🚀\n第三行\n\n'
const saved = await api(`/chapters/${chapter.id}/content`, 'PUT', { content: input, expectedVersion: 1 })
const current = await api(`/chapters/${chapter.id}`)
const revision = await api(`/chapters/${chapter.id}/revisions/2`)
const duplicate = await api(`/chapters/${chapter.id}/content`, 'PUT', { content: input, expectedVersion: 2 })
const history = await api(`/chapters/${chapter.id}/revisions?limit=50`)
if (saved.version !== 2 || current.version !== 2 || duplicate.version !== 2
    || saved.content !== expected || current.content !== expected || revision.content !== expected
    || history.items.length !== 2 || history.nextCursor !== null)
  throw new Error('Text or version history changed during HTTP round trip')
console.log(JSON.stringify({ projectId: project.id, chapterId: chapter.id, version: 2,
  historyCount: history.items.length, codePoints: [...expected].length, normalizedText: expected }, null, 2))
