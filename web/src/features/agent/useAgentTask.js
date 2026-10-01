import { useCallback, useEffect, useRef, useState } from 'react'
import { api, readableError } from '../../shared/api/client.js'
import { cp } from '../../shared/utils/text.js'
import { active } from './status.js'
import { useRun } from './useRun.js'

export const DEFAULT_INSTRUCTION = '检查当前章节与人物设定、前文经历是否矛盾；如有必要，请给出修改建议。'

function mergeRun(current, incoming) {
  if (!current) return incoming
  const stale = (current.lastEventSeq || 0) > (incoming.lastEventSeq || 0)
    || ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(current.status) && active(incoming.status)
  return stale ? { ...incoming, ...current } : { ...current, ...incoming }
}

export function useAgentTask({ projectId, chapterId, chapter, dirty, accepting, projectBusy }) {
  const scope = `${projectId}:${chapterId || ''}`
  const currentScope = useRef(scope)
  currentScope.current = scope
  const [drafts, setDrafts] = useState({})
  const [list, setList] = useState({ scope, items: [], cursor: null })
  const [selection, setSelection] = useState({ scope, id: null })
  const [receipt, setReceipt] = useState(null)
  const [capabilities, setCapabilities] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [listError, setListError] = useState(null)
  const [cancelling, setCancelling] = useState(null)
  const pending = useRef(new Map())
  const submitting = useRef(false)
  const cancelPending = useRef(false)
  const requestNumber = useRef(0)
  const instruction = drafts[scope] ?? DEFAULT_INSTRUCTION
  const runs = list.scope === scope ? list.items : []
  const runCursor = list.scope === scope ? list.cursor : null
  const runId = selection.scope === scope ? selection.id : null

  const loadRuns = useCallback(async (after = null) => {
    if (!projectId || !chapterId) return
    const request = ++requestNumber.current
    try {
      const page = await api(`/agent/runs?projectId=${projectId}&chapterId=${chapterId}&limit=50${after ? `&cursor=${encodeURIComponent(after)}` : ''}`)
      if (request !== requestNumber.current || currentScope.current !== scope) return
      setList(current => {
        const previous = new Map((current.scope === scope ? current.items : []).map(item => [item.id, item]))
        const items = page.items.map(item => mergeRun(previous.get(item.id), item))
        return { scope, cursor: page.nextCursor, items: after && current.scope === scope
          ? [...new Map([...current.items, ...items].map(item => [item.id, item])).values()] : items }
      })
      setReceipt(current => {
        const latest = current?.scope === scope && page.items.find(item => item.id === current.run.id)
        return latest ? { ...current, run: mergeRun(current.run, latest) } : current
      })
      if (!after) setSelection(current => ({ scope, id: current.scope === scope && current.id
        ? current.id : page.items[0]?.id || null }))
      setListError(null)
    } catch (failure) {
      if (request === requestNumber.current && currentScope.current === scope)
        setListError({ scope, message: readableError(failure) })
    }
  }, [projectId, chapterId, scope])

  useEffect(() => {
    setList({ scope, items: [], cursor: null })
    setSelection({ scope, id: null })
    loadRuns()
    return () => { requestNumber.current++ }
  }, [scope, loadRuns])

  useEffect(() => {
    let disposed = false
    api('/system/capabilities').then(value => { if (!disposed) setCapabilities(value) })
      .catch(() => { if (!disposed) setCapabilities({ agentConfigured: false, unavailable: true }) })
    return () => { disposed = true }
  }, [])

  const onRunChange = useCallback(snapshot => {
    if (currentScope.current !== scope) return
    setList(current => current.scope === scope
      ? { ...current, items: current.items.map(item => item.id === snapshot.id ? mergeRun(item, snapshot) : item) }
      : current)
    setReceipt(current => current?.scope === scope && current.run.id === snapshot.id
      ? { ...current, run: mergeRun(current.run, snapshot) } : current)
  }, [scope])
  const { run: snapshot, connection, error: runError, refresh } = useRun(runId, onRunChange)
  const known = receipt?.scope === scope && receipt.run.id === runId ? receipt.run : runs.find(item => item.id === runId)
  const run = snapshot?.id === runId ? mergeRun(known, snapshot) : known || null
  const activeRun = [run, receipt?.scope === scope ? receipt.run : null, ...runs].find(item => item && active(item.status))
  const taskActive = Boolean(activeRun)
  const cancelRequested = Boolean(activeRun?.cancelRequested || cancelling?.scope === scope && cancelling.id === activeRun?.id)
  const maxLength = capabilities?.maxInstructionCodePoints || 2000

  useEffect(() => {
    if (!taskActive) return
    const timer = window.setInterval(() => { Promise.allSettled([refresh(), loadRuns()]) }, 5000)
    return () => window.clearInterval(timer)
  }, [taskActive, refresh, loadRuns])

  let blockedReason = ''
  if (!chapter || chapter.id !== chapterId) blockedReason = '先选择一个章节。'
  else if (dirty) blockedReason = '当前正文未保存，保存后才能发送。'
  else if (accepting) blockedReason = '正在采纳修改建议，请稍候。'
  else if (!capabilities) blockedReason = '正在连接写作助手…'
  else if (capabilities.unavailable) blockedReason = '暂时无法连接写作助手，请刷新页面重试。'
  else if (!capabilities.agentConfigured) blockedReason = '模型尚未配置；章节和设定仍可编辑。'
  else if (projectBusy && !taskActive) blockedReason = '作品有其他任务正在运行，请等待完成。'
  else if (!instruction.trim()) blockedReason = '请输入你想检查或改进的内容。'
  else if (cp(instruction) > maxLength) blockedReason = `要求不能超过 ${maxLength} 字。`

  function setInstruction(value) {
    setDrafts(current => ({ ...current, [scope]: value }))
    pending.current.delete(scope)
    setError(null)
  }

  async function submit() {
    if (submitting.current || taskActive || blockedReason) return
    submitting.current = true
    setBusy(true); setError(null)
    const request = pending.current.get(scope) || {
      projectId, targetChapterId: chapter.id, expectedVersion: chapter.version,
      instruction: instruction.trim(), clientRequestId: crypto.randomUUID(),
    }
    pending.current.set(scope, request)
    try {
      const result = await api('/agent/runs', 'POST', request)
      pending.current.delete(scope)
      if (currentScope.current !== scope) return
      const queued = { id: result.runId, status: result.status || 'QUEUED', instruction: request.instruction,
        baseVersion: request.expectedVersion, queuedAt: new Date().toISOString(), steps: [], sources: [] }
      setReceipt({ scope, run: queued })
      setSelection({ scope, id: result.runId })
      setList(current => ({ scope, cursor: current.scope === scope ? current.cursor : null,
        items: [queued, ...(current.scope === scope ? current.items.filter(item => item.id !== queued.id) : [])] }))
      setDrafts(current => ({ ...current, [scope]: (current[scope] ?? DEFAULT_INSTRUCTION) === instruction ? '' : current[scope] }))
      await loadRuns()
    } catch (failure) {
      if (currentScope.current === scope) setError({ scope, message: readableError(failure) })
      if (failure.status && failure.status !== 503) pending.current.delete(scope)
    } finally { submitting.current = false; setBusy(false) }
  }

  async function cancel() {
    if (!activeRun || cancelRequested || cancelPending.current) return
    cancelPending.current = true
    setCancelling({ scope, id: activeRun.id }); setError(null)
    try {
      await api(`/agent/runs/${activeRun.id}/cancel`, 'POST')
      if (currentScope.current !== scope) return
      await Promise.allSettled([activeRun.id === runId ? refresh() : Promise.resolve(), loadRuns()])
    } catch (failure) {
      if (currentScope.current === scope) {
        setError({ scope, message: readableError(failure) }); setCancelling(null)
      }
    } finally { cancelPending.current = false }
  }

  return { instruction, setInstruction, runs, runCursor, runId,
    selectRun: id => setSelection({ scope, id }), run, connection,
    runError, refresh, loadRuns, busy, taskActive, cancelRequested, blockedReason,
    maxLength, submit, cancel, error: error?.scope === scope ? error.message : '',
    listError: listError?.scope === scope ? listError.message : '' }
}
