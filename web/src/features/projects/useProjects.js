import { useEffect, useState } from 'react'
import { readableError } from '../../shared/api/client.js'
import { projectsApi } from './projects.api.js'

export function useProjects(open) {
  const [projects, setProjects] = useState([])
  const [cursor, setCursor] = useState(null)
  const [title, setTitle] = useState('')
  const [error, setError] = useState('')
  const [removing, setRemoving] = useState(null)
  const [deleting, setDeleting] = useState(false)
  const load = (after = null) => projectsApi.list(after)
    .then(page => { setProjects(current => after ? [...current, ...page.items] : page.items); setCursor(page.nextCursor) })
    .catch(e => setError(readableError(e)))
  useEffect(() => { load() }, [])
  async function create(event) {
    event.preventDefault()
    try { const value = await projectsApi.create(title); setTitle(''); open(value.id) }
    catch (e) { setError(readableError(e)) }
  }
  async function confirmRemove() {
    setDeleting(true)
    try {
      await projectsApi.remove(removing.id)
      setProjects(current => current.filter(item => item.id !== removing.id))
      setRemoving(null)
    } catch (e) { setError(readableError(e)); setRemoving(null) }
    finally { setDeleting(false) }
  }
  return { projects, cursor, title, setTitle, error, removing, setRemoving,
    deleting, load, create, confirmRemove }
}
