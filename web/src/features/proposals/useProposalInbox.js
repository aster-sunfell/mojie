import { useCallback, useEffect, useRef, useState } from 'react'
import { api, readableError } from '../../shared/api/client.js'

export function useProposalInbox(projectId) {
  const [counts, setCounts] = useState(null)
  const [error, setError] = useState('')
  const [arrival, setArrival] = useState(null)
  const previousTotal = useRef(null)
  const request = useRef(0)
  const refresh = useCallback(async () => {
    const token = ++request.current
    try {
      const value = await api(`/projects/${projectId}/edit-proposals/summary`)
      if (token !== request.current) return
      const total = value.pendingCount + value.conflictedCount
      if (previousTotal.current != null && total > previousTotal.current)
        setArrival({ count: total - previousTotal.current, at: Date.now() })
      previousTotal.current = total
      setCounts(value); setError('')
    } catch (e) {
      if (token === request.current) setError(readableError(e))
    }
  }, [projectId])
  useEffect(() => {
    previousTotal.current = null
    setCounts(null); setError(''); setArrival(null); refresh()
    const onVisible = () => { if (!document.hidden) refresh() }
    const timer = window.setInterval(onVisible, 10000)
    window.addEventListener('focus', onVisible)
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      request.current++; window.clearInterval(timer)
      window.removeEventListener('focus', onVisible)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [refresh])
  useEffect(() => {
    if (!arrival) return
    const timer = window.setTimeout(() => setArrival(null), 3000)
    return () => window.clearTimeout(timer)
  }, [arrival])
  return { counts, error, refresh, arrival }
}
