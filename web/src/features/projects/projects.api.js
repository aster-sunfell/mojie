import { api } from '../../shared/api/client.js'

export const projectsApi = {
  list(after = null) {
    return api(`/projects?limit=20${after ? `&cursor=${encodeURIComponent(after)}` : ''}`)
  },
  create(title) { return api('/projects', 'POST', { title }) },
  remove(id) { return api(`/projects/${id}`, 'DELETE') },
}
