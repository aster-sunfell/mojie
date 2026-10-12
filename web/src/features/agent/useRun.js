import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../../shared/api/client.js'

const eventTypes = ['run.queued', 'run.started', 'model.started', 'model.finished', 'tool.started', 'tool.finished', 'run.succeeded', 'run.failed', 'run.cancelled', 'context.compacted', 'skill.loaded', 'script.started', 'script.finished', 'artifact.created']
const terminal = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED'])

export function useRun(runId, onChange) {
  const [run, setRun] = useState(null)
  const [connection, setConnection] = useState('idle')
  const [error, setError] = useState('')
  const seq = useRef(0)
  const selectedRunId = useRef(runId)
  const mounted = useRef(true)
  const snapshotRef = useRef(null), onChangeRef = useRef(onChange)
  onChangeRef.current = onChange
  selectedRunId.current = runId
  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false }
  }, [])
  const refresh = useCallback(async () => {
    if (!runId) return null
    const snapshot = await api(`/agent/runs/${runId}`)
    if (!mounted.current || selectedRunId.current !== runId) return null
    if (snapshot.lastEventSeq < seq.current) return null
    if (snapshotRef.current && terminal.has(snapshotRef.current.status) && !terminal.has(snapshot.status)) return null
    snapshotRef.current = snapshot
    setRun(snapshot)
    seq.current = Math.max(seq.current, snapshot.lastEventSeq)
    onChangeRef.current?.(snapshot)
    return snapshot
  }, [runId])

  useEffect(() => {
    setRun(null); setError(''); seq.current = 0; snapshotRef.current = null
    if (!runId) { setConnection('idle'); return }
    let disposed = false
    let source
    let fallback
    let retryTimer
    ;(async () => {
      try {
        const snapshot = await api(`/agent/runs/${runId}`)
        if (disposed) return
        snapshotRef.current = snapshot
        setRun(snapshot); seq.current = snapshot.lastEventSeq; onChangeRef.current?.(snapshot)
        if (terminal.has(snapshot.status)) { setConnection('closed'); return }
        source = new EventSource(`/api/v1/agent/runs/${runId}/events?afterSeq=${snapshot.lastEventSeq}`)
        source.onopen = () => {
          if (!disposed) {
            window.clearTimeout(retryTimer); retryTimer = null
            window.clearInterval(fallback); fallback = null
            setConnection('connected'); setError('')
          }
        }
        const handle = event => {
          if (disposed) return
          try {
            const payload = JSON.parse(event.data)
            if (payload.runId !== runId || payload.seq <= seq.current) return
            seq.current = payload.seq
            refresh().then(current => {
              if (current && terminal.has(current.status)) { source?.close(); setConnection('closed') }
            }).catch(() => setConnection('reconnecting'))
          } catch { setConnection('reconnecting') }
        }
        const textDelta = event => {
          if (disposed) return
          try {
            const eventData = JSON.parse(event.data), delta = eventData.payload
            if (eventData.runId !== runId || eventData.seq <= seq.current) return
            if (!Number.isInteger(eventData.seq) || !Number.isInteger(delta?.callIndex)
              || delta.callIndex < 1 || typeof delta.text !== 'string') throw new Error('Invalid text event')
            const current = snapshotRef.current
            if (!current || terminal.has(current.status)) return
            const output = current.output
            if (output && output.modelCallIndex > delta.callIndex) return
            seq.current = eventData.seq
            const next = { ...current, lastEventSeq: eventData.seq,
              output: { modelCallIndex: delta.callIndex,
                text: (output?.modelCallIndex === delta.callIndex ? output.text : '') + delta.text,
                status: 'STARTED', finishReason: null, lastEventSeq: eventData.seq } }
            snapshotRef.current = next
            setRun(next); onChangeRef.current?.(next)
          } catch { setConnection('reconnecting'); refresh().catch(() => {}) }
        }
        source.addEventListener('model.text.delta', textDelta)
        for (const type of eventTypes) source.addEventListener(type, handle)
        source.onerror = () => {
          if (disposed) return
          setConnection('reconnecting')
          if (!retryTimer) retryTimer = window.setTimeout(() => {
            if (disposed) return
            fallback = window.setInterval(() => refresh().then(current => {
              if (current && terminal.has(current.status)) {
                source?.close(); window.clearInterval(fallback); setConnection('closed')
              }
            }).catch(() => {}), 2000)
          }, 10000)
        }
      } catch (failure) { if (!disposed) { setError(failure.message); setConnection('error') } }
    })()
    return () => {
      disposed = true; source?.close()
      window.clearInterval(fallback); window.clearTimeout(retryTimer)
    }
  }, [runId, refresh])
  return { run, connection, error, refresh }
}
