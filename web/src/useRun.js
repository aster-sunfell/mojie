import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from './api.js'

const eventTypes = ['run.queued', 'run.started', 'model.started', 'model.finished', 'tool.started', 'tool.finished', 'run.succeeded', 'run.failed', 'run.cancelled']
const terminal = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED'])

export function useRun(runId, onChange) {
  const [run, setRun] = useState(null)
  const [connection, setConnection] = useState('idle')
  const [error, setError] = useState('')
  const seq = useRef(0)
  const refresh = useCallback(async () => {
    if (!runId) return null
    const snapshot = await api(`/agent/runs/${runId}`)
    if (snapshot.lastEventSeq < seq.current) return null
    setRun(previous => previous?.id === snapshot.id && previous.lastEventSeq > snapshot.lastEventSeq ? previous : snapshot)
    seq.current = Math.max(seq.current, snapshot.lastEventSeq)
    onChange?.(snapshot)
    return snapshot
  }, [runId, onChange])

  useEffect(() => {
    setRun(null); setError(''); seq.current = 0
    if (!runId) { setConnection('idle'); return }
    let disposed = false
    let source
    let fallback
    let retryTimer
    ;(async () => {
      try {
        const snapshot = await api(`/agent/runs/${runId}`)
        if (disposed) return
        setRun(snapshot); seq.current = snapshot.lastEventSeq; onChange?.(snapshot)
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
            }).catch(() => {}), 5000)
          }, 10000)
        }
      } catch (failure) { if (!disposed) { setError(failure.message); setConnection('error') } }
    })()
    return () => {
      disposed = true; source?.close()
      window.clearInterval(fallback); window.clearTimeout(retryTimer)
    }
  }, [runId, refresh, onChange])
  return { run, connection, error, refresh }
}
