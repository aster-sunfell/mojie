import { useCallback, useEffect, useRef, useState } from 'react'
import { readableError } from '../../shared/api/client.js'
import { projectsApi } from './projects.api.js'

export function useProjects(keyword) {
  const [projects, setProjects] = useState([])
  const [cursor, setCursor] = useState(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [removing, setRemoving] = useState(null)
  const [deleting, setDeleting] = useState(false)
  const requestNumber = useRef(0)
  const deletePending = useRef(false)
  const query = keyword.trim()

  const load = useCallback(async (after = null) => {
    const request = ++requestNumber.current
    if (after) setLoadingMore(true)
    else setLoading(true)
    setError('')
    try {
      const page = await projectsApi.list({ keyword: query, cursor: after })
      if (request !== requestNumber.current) return
      setProjects(current => after
        ? [...new Map([...current, ...page.items].map(item => [item.id, item])).values()]
        : page.items)
      setCursor(page.nextCursor)
    } catch (failure) {
      if (request === requestNumber.current) setError(readableError(failure))
    } finally {
      if (request === requestNumber.current) { setLoading(false); setLoadingMore(false) }
    }
  }, [query])

  useEffect(() => {
    setProjects([]); setCursor(null); setError(''); setLoading(true); setLoadingMore(false)
    if (!query) load()
    const timer = query ? setTimeout(() => load(), 300) : null
    return () => { clearTimeout(timer); requestNumber.current++ }
  }, [load, query])

  async function confirmRemove() {
    if (!removing || deletePending.current) return
    deletePending.current = true
    setDeleting(true)
    try {
      await projectsApi.remove(removing.id)
      setProjects(current => current.filter(item => item.id !== removing.id))
      setRemoving(null)
    } catch (failure) { setError(readableError(failure)); setRemoving(null) }
    finally { deletePending.current = false; setDeleting(false) }
  }

  return { projects, cursor, error, loading, loadingMore, removing, setRemoving,
    deleting, load, confirmRemove }
}
