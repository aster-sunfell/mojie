import { useCallback, useEffect, useRef, useState } from 'react'
import { api, readableError } from '../../shared/api/client.js'
import { useRun } from '../agent/useRun.js'
import { active } from '../agent/status.js'

const emptyDraft = () => ({ input: '', useChapter: true, analysisOnly: false, mentions: [], pending: null })
const latestRun = (current, incoming) => current && ((current.lastEventSeq || 0) > (incoming.lastEventSeq || 0)
  || !active(current.status) && active(incoming.status)) ? current : incoming

export function useConversation({ projectId, getContext, projectBusy, onActivityChange }) {
  const storageKey = `mojie:conversation:${projectId}`
  const [id, setId] = useState(() => localStorage.getItem(storageKey) || '')
  const scope = useRef(id), drafts = useRef({}), mounted = useRef(true)
  const [draft, setDraft] = useState(() => {
    try { drafts.current = Object.fromEntries(Object.entries(JSON.parse(sessionStorage.getItem(`${storageKey}:drafts`) || '{}')).map(([key, value]) => { const { selected, discoveryEnabled, discoveryPending, ...draft } = value; return [key, draft] })) } catch { /* Start with a fresh draft. */ }
    return { ...emptyDraft(), ...drafts.current[id] }
  })
  drafts.current[id] = draft
  const [conversation, setConversation] = useState(null), [messages, setMessages] = useState([])
  const [runs, setRuns] = useState({}), [runId, setRunId] = useState(null)
  const [items, setItems] = useState([]), [cursor, setCursor] = useState(null)
  const [listLoading, setListLoading] = useState(true), [listError, setListError] = useState('')
  const [busy, setBusy] = useState(false), [loading, setLoading] = useState(Boolean(id))
  const [error, setError] = useState(''), [cancelRequested, setCancelRequested] = useState(false)
  const [capabilities, setCapabilities] = useState(null)
  const counter = useRef(0), listCounter = useRef(0), submitting = useRef(false)
  const mutating = useRef(false), retryRequests = useRef(new Map())
  const runCache = useRef({}), terminalSeen = useRef(new Set()), loadRef = useRef(null), listRef = useRef(null)
  const options = useRef({ getContext, projectBusy, onActivityChange })
  options.current = { getContext, projectBusy, onActivityChange }

  function persistDrafts() {
    try {
      // Pending receipts are kept in memory; edited text and preferences survive refresh.
      sessionStorage.setItem(`${storageKey}:drafts`, JSON.stringify(Object.fromEntries(
        Object.entries(drafts.current).map(([key, value]) => [key, { ...value, pending: null }]))))
    } catch { /* Storage may be unavailable or full. */ }
  }
  function updateDraft(value, invalidate = true) {
    const next = { ...(drafts.current[scope.current] || emptyDraft()), ...value }
    if (invalidate) next.pending = null
    drafts.current[scope.current] = next
    setDraft(next)
    persistDrafts()
  }
  function select(nextId, transferredDraft) {
    if ((submitting.current || mutating.current) && !transferredDraft) return
    counter.current++
    scope.current = nextId
    if (transferredDraft) {
      drafts.current[nextId] = transferredDraft
      delete drafts.current['']
    }
    setId(nextId)
    setDraft({ ...emptyDraft(), ...drafts.current[nextId] })
    setConversation(null); setMessages([]); setRunId(null); setError(''); setLoading(Boolean(nextId)); setCancelRequested(false)
    if (nextId) localStorage.setItem(storageKey, nextId)
    else localStorage.removeItem(storageKey)
    persistDrafts()
  }
  const loadList = useCallback(async (after = null) => {
    const token = ++listCounter.current
    setListLoading(true); setListError('')
    try {
      const page = await api(`/projects/${projectId}/conversations?limit=50${after ? `&cursor=${encodeURIComponent(after)}` : ''}`)
      if (!mounted.current || token !== listCounter.current) return
      setItems(current => after ? [...new Map([...current, ...page.items].map(c => [c.id, c])).values()] : page.items)
      setCursor(page.nextCursor)
    } catch (e) {
      if (mounted.current && token === listCounter.current) setListError(readableError(e))
      throw e
    } finally {
      if (mounted.current && token === listCounter.current) setListLoading(false)
    }
  }, [projectId])
  listRef.current = loadList
  const load = useCallback(async () => {
    const currentId = scope.current
    if (!currentId) return
    const token = ++counter.current
    const c = await api(`/conversations/${currentId}`)
    let page, after, all = []
    do {
      page = await api(`/conversations/${currentId}/messages?limit=50${after ? `&cursor=${encodeURIComponent(after)}` : ''}`)
      all.push(...page.items); after = page.nextCursor
    } while (after)
    const ids = [...new Set(all.map(m => m.runId).filter(Boolean))]
    // Refresh failures as well as the active run; immutable completed turns are reused.
    const snapshots = await Promise.allSettled(ids.filter(key => !runCache.current[key] || active(runCache.current[key].status))
      .map(key => api(`/agent/runs/${key}`)))
    if (!mounted.current || scope.current !== currentId || counter.current !== token) return
    for (const result of snapshots) if (result.status === 'fulfilled') {
      runCache.current[result.value.id] = latestRun(runCache.current[result.value.id], result.value)
    }
    const activeId = c.activeRunId && (!runCache.current[c.activeRunId] || active(runCache.current[c.activeRunId].status)) ? c.activeRunId : null
    setRuns({ ...runCache.current }); setConversation({ ...c, activeRunId: activeId, lastSeq: Math.max(c.lastSeq, all.at(-1)?.seq || 0) }); setMessages(all)
    setRunId(activeId); setCancelRequested(Boolean(activeId && runCache.current[activeId]?.cancelRequested)); setLoading(false)
  }, [])
  loadRef.current = load
  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false; counter.current++; listCounter.current++ }
  }, [])
  useEffect(() => { setItems([]); setCursor(null); loadList().catch(() => {}) }, [loadList])
  useEffect(() => {
    api('/system/capabilities').then(value => { if (mounted.current) setCapabilities(value) })
      .catch(() => { if (mounted.current) setCapabilities({ unavailable: true }) })
  }, [])
  useEffect(() => {
    if (!capabilities || capabilities.agentConfigured && capabilities.modelMetadataAvailable !== false && !capabilities.unavailable) return
    const timer = window.setInterval(() => api('/system/capabilities').then(value => {
      if (mounted.current) setCapabilities(value)
    }).catch(() => {}), 5000)
    return () => window.clearInterval(timer)
  }, [capabilities])
  useEffect(() => {
    if (!id) return
    load().catch(e => {
      if (mounted.current && scope.current === id) { setLoading(false); setError(readableError(e)) }
    })
  }, [id, load])
  const onRunChange = useCallback(snapshot => {
    snapshot = latestRun(runCache.current[snapshot.id], snapshot)
    runCache.current[snapshot.id] = snapshot
    setRuns(current => ({ ...current, [snapshot.id]: snapshot }))
    if (!active(snapshot.status) && !terminalSeen.current.has(snapshot.id)) {
      terminalSeen.current.add(snapshot.id)
      loadRef.current?.().catch(e => { if (mounted.current) setError(readableError(e)) })
      listRef.current?.().catch(() => {})
      options.current.onActivityChange?.()
      setCancelRequested(false)
    }
  }, [])
  const live = useRun(runId, onRunChange)
  useEffect(() => {
    if (!conversation?.activeRunId) return
    const timer = window.setInterval(() => load().catch(() => {}), 3000)
    return () => window.clearInterval(timer)
  }, [conversation?.activeRunId, load])
  const taskActive = Boolean(conversation?.activeRunId || runs[runId] && active(runs[runId].status))
  const unavailableReason = !capabilities ? '正在连接写作助手…' : capabilities.unavailable ? '暂时无法连接写作助手，请稍后重试。'
    : !capabilities.agentConfigured ? '模型尚未配置；章节和设定仍可编辑。'
      : capabilities.modelMetadataAvailable === false ? '模型信息暂不可用，请稍后重试；章节和设定仍可编辑。' : ''

  async function action(fn) {
    if (submitting.current || mutating.current || busy) return false
    mutating.current = true
    setBusy(true); setError('')
    const current = scope.current
    try {
      await fn()
      if (mounted.current && scope.current === current) { await load(); await loadList() }
      return true
    } catch (e) {
      if (mounted.current && scope.current === current) {
        setError(readableError(e))
        if (e.code === 'CONVERSATION_VERSION_CONFLICT') await load().catch(() => {})
      }
      return false
    } finally { mutating.current = false; if (mounted.current) setBusy(false) }
  }
  async function send(skillMentions = draft.mentions) {
    const originalDraft = drafts.current[scope.current] || draft
    if (submitting.current || mutating.current || busy || loading || id && !conversation || taskActive || unavailableReason || options.current.projectBusy
      || !originalDraft.input.trim() || [...originalDraft.input].length > 8000) return
    const initialContext = options.current.getContext()
    submitting.current = true; setBusy(true); setError('')
    let currentId = scope.current
    // Capture only the selected saved reference; body text is read by tools on demand.
    const target = originalDraft.useChapter !== false ? initialContext.chapter : null
    try {
      let c = conversation
      if (!currentId) {
        c = await api(`/projects/${projectId}/conversations`, 'POST')
        currentId = c.id
        select(currentId, originalDraft)
        setConversation(c)
        setLoading(false)
      }
      const request = originalDraft.pending || { content: originalDraft.input.trim(), clientRequestId: crypto.randomUUID(),
        expectedConversationSeq: c.lastSeq,
        ...(originalDraft.analysisOnly ? { analysisOnly: true } : {}),
        ...(target ? { contextChapterId: target.id, contextVersion: target.version } : {}),
        skillMentions }
      updateDraft({ pending: request }, false)
      const receipt = await api(`/conversations/${currentId}/messages`, 'POST', request)
      updateDraft({ input: '', mentions: [], pending: null }, false)
      setRunId(receipt.runId)
      setConversation(current => ({ ...(current || c), activeRunId: receipt.runId, lastSeq: receipt.conversationSeq }))
      options.current.onActivityChange?.()
      await load()
      await loadList()
    } catch (e) {
      if (mounted.current && scope.current === currentId) {
        setError(readableError(e))
        if (['CONVERSATION_VERSION_CONFLICT', 'VERSION_CONFLICT', 'SKILL_VERSION_CONFLICT', 'SKILL_UNAVAILABLE'].includes(e.code)) {
          updateDraft({ pending: null }, false)
          await load().catch(() => {})
        }
      }
    } finally { submitting.current = false; if (mounted.current) setBusy(false) }
  }
  async function stop() {
    if (cancelRequested || submitting.current || mutating.current || busy) return
    setCancelRequested(true)
    await action(async () => {
      try { await api(`/agent/runs/${conversation?.activeRunId || runId}/cancel`, 'POST') }
      catch (e) { setCancelRequested(false); throw e }
    })
  }
  async function retry(message) {
    const key = `${scope.current}:${message.id}`
    if (!retryRequests.current.has(key)) retryRequests.current.set(key, { clientRequestId: crypto.randomUUID() })
    await action(async () => {
      const receipt = await api(`/conversations/${scope.current}/messages/${message.id}/retry`, 'POST', retryRequests.current.get(key))
      retryRequests.current.delete(key)
      setRunId(receipt.runId)
      options.current.onActivityChange?.()
    })
  }
  return { id, conversation, messages, runs, items, cursor, listLoading, listError, draft, unavailableReason,
    loading, busy, error: error || live.error, taskActive, cancelRequested,
    connection: live.connection, updateDraft, select, send, stop, retry, action, loadList,
    patchConversation: (item, value) => action(async () => {
      try {
        await api(`/conversations/${item.id}`, 'PATCH', { expectedVersion: item.version, ...value })
      } catch (e) {
        if (e.code === 'CONVERSATION_VERSION_CONFLICT') await loadList().catch(() => {})
        throw e
      }
    }),
    patch: value => action(async () => {
      await api(`/conversations/${scope.current}`, 'PATCH', { expectedVersion: conversation.version, ...value })
    }) }
}
