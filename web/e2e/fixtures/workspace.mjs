export async function mockWorkspace(page, { history = true,
  analysis = '已核对人物设定和前文章节。林舟的行动与已知经历相符，暂未发现明显矛盾。', saveFailure = false } = {}) {
  const chapters = [
    { id: 'c', title: '第一章 · 雨夜', chapterOrder: 1, version: 3, content: ('林舟推开窗，看见雨落在街道上。\n\n').repeat(40) },
    { id: 'c2', title: '第二章 · 故人', chapterOrder: 2, version: 1, content: '次日，林舟与故人在桥边重逢。' },
  ]
  const conversations = history ? [{ id: 'd', title: '雨夜章节讨论', lastSeq: 2, version: 1, discoveryEnabled: true }] : []
  const runs = history ? [{ id: 'old', status: 'SUCCEEDED', targetChapterId: 'c', taskType: 'CHAPTER_REVIEW', baseVersion: 3,
    instruction: '检查人物设定与前文是否矛盾。', analysis, lastEventSeq: 1, steps: [], sources: [], modelCallCount: 2, toolCallCount: 3 }] : []
  const messages = history ? { d: [{ id: 'u-old', role: 'USER', content: runs[0].instruction, seq: 1, runId: 'old', status: 'SUCCEEDED' },
    { id: 'a-old', role: 'ASSISTANT', content: analysis, seq: 2, runId: 'old', status: 'SUCCEEDED' }] } : {}
  const writes = [], saves = [], cancelled = [], creates = []
  if (history) await page.addInitScript(() => { if (!localStorage.getItem('mojie:conversation:p')) localStorage.setItem('mojie:conversation:p', 'd') })
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname.replace('/api/v1', '')
    const data = value => route.fulfill({ json: { data: value } })
    if (path === '/system/maintenance') return data({ active: false })
    if (path === '/projects/p') return data({ id: 'p', title: '林舟演示' })
    if (path === '/projects/p/chapters') return data({ items: chapters, nextCursor: null })
    if (path === '/system/capabilities') return data({ agentConfigured: true, modelMetadataAvailable: true })
    if (path === '/skills') return data({ items: [], nextCursor: null })
    const chapter = chapters.find(item => path === `/chapters/${item.id}` || path === `/chapters/${item.id}/content`)
    if (chapter) {
      if (request.method() === 'PUT') {
        const body = request.postDataJSON(); saves.push(body)
        if (saveFailure) return route.fulfill({ status: 409, json: { code: 'VERSION_CONFLICT' } })
        chapter.content = body.content; chapter.version++
      }
      return data(chapter)
    }
    if (path === '/projects/p/conversations') {
      if (request.method() === 'POST') {
        const id = `d${conversations.length + 1}`, c = { id, title: '新对话', lastSeq: 0, version: 1, discoveryEnabled: true }
        conversations.push(c); messages[id] = []; creates.push(id); return data(c)
      }
      return data({ items: conversations, nextCursor: null })
    }
    const c = conversations.find(c => path.startsWith(`/conversations/${c.id}`))
    if (c) {
      if (path === `/conversations/${c.id}`) {
        if (request.method() === 'PATCH') { Object.assign(c, request.postDataJSON()); c.version++ }
        return data(c)
      }
      if (path.endsWith('/skills')) return data([])
      if (path.endsWith('/messages')) {
        if (request.method() === 'POST') {
          const body = request.postDataJSON(); writes.push(body)
          const run = { id: `active-${writes.length}`, status: 'RUNNING', taskType: 'ASSISTANT', contextChapterId: body.contextChapterId, contextVersion: body.contextVersion,
            instruction: body.content, baseVersion: body.contextVersion, lastEventSeq: 1, steps: [], sources: [], modelCallCount: 1, toolCallCount: 0 }
          runs.push(run); c.activeRunId = run.id
          messages[c.id].push({ id: `u-${run.id}`, role: 'USER', content: body.content, seq: ++c.lastSeq, runId: run.id, status: 'RUNNING' })
          return data({ runId: run.id, conversationSeq: c.lastSeq })
        }
        return data({ items: messages[c.id], nextCursor: null })
      }
    }
    if (path === '/agent/runs') return data({ items: runs, nextCursor: null })
    if (path.endsWith('/events')) return route.abort()
    if (path.endsWith('/resources')) return data({ skills: [], artifacts: [] })
    const run = runs.find(item => path === `/agent/runs/${item.id}` || path === `/agent/runs/${item.id}/cancel`)
    if (run) {
      if (path.endsWith('/cancel')) { cancelled.push(run.id); run.cancelRequested = true }
      return data(run)
    }
    return route.fulfill({ status: 404, json: { message: `Unexpected request ${path}` } })
  })
  return { writes, saves, cancelled, creates, chapters, conversations, runs, messages }
}
