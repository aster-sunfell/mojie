#!/usr/bin/env node

// Run after `seed-demo.mjs --scale`; pass its project and target chapter IDs.
const [projectId, chapterId] = process.argv.slice(2)
if (!projectId || !chapterId) {
  console.error('Usage: node scripts/check-m4-load.mjs PROJECT_ID TARGET_CHAPTER_ID')
  process.exit(2)
}
const base = (process.env.MOJIE_API_URL || 'http://127.0.0.1:8080/api/v1').replace(/\/$/, '')

async function api(path, method = 'GET', body) {
  const started = performance.now()
  const response = await fetch(`${base}${path}`, {
    method,
    headers: body === undefined ? undefined : { 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const result = await response.json()
  if (!response.ok) throw new Error(`${method} ${path}: ${response.status} ${result.code || ''}`)
  return { data: result.data, elapsedMs: performance.now() - started }
}

async function all(path) {
  const ids = new Set()
  let cursor = null
  do {
    const suffix = cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''
    const page = (await api(`${path}?limit=50${suffix}`)).data
    for (const item of page.items) {
      if (ids.has(item.id)) throw new Error(`Duplicate ID in ${path}: ${item.id}`)
      ids.add(item.id)
    }
    cursor = page.nextCursor
  } while (cursor)
  return ids.size
}

const chapterCount = await all(`/projects/${projectId}/chapters`)
const settingCount = await all(`/projects/${projectId}/settings`)
if (chapterCount !== 100 || settingCount !== 200)
  throw new Error(`Expected 100 chapters and 200 settings, got ${chapterCount} and ${settingCount}`)

const chapter = (await api(`/chapters/${chapterId}`)).data
const timings = []
for (let index = 0; index < 20; index++) {
  const kind = index % 4
  const call = kind === 0 ? () => api(`/projects/${projectId}/chapters?limit=50`)
    : kind === 1 ? () => api(`/projects/${projectId}/settings?limit=50`)
      : kind === 2 ? () => api(`/chapters/${chapterId}`)
        : () => api(`/chapters/${chapterId}/content`, 'PUT', {
          content: chapter.content, expectedVersion: chapter.version,
        })
  const result = await call()
  if (kind === 3 && result.data.version !== chapter.version) throw new Error('No-op save changed the chapter version')
  timings.push(Math.round(result.elapsedMs))
}
const underOneSecond = timings.filter(ms => ms <= 1000).length
console.log(JSON.stringify({ chapterCount, settingCount, timingsMs: timings,
  underOneSecond, target: 'at least 19 of 20' }, null, 2))
if (underOneSecond < 19) process.exitCode = 1
