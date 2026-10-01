import { api } from '../../shared/api/client.js'

export const projectsApi = {
  list({ keyword = '', cursor = null } = {}) {
    const params = new URLSearchParams({ limit: '20' })
    if (keyword) params.set('keyword', keyword)
    if (cursor) params.set('cursor', cursor)
    return api(`/projects?${params}`)
  },
  create(title) { return api('/projects', 'POST', { title }) },
  remove(id) { return api(`/projects/${id}`, 'DELETE') },
}
