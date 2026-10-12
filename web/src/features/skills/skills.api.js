import { api } from '../../shared/api/client.js'

export async function loadSkillList() {
  const items = []
  let cursor
  do {
    const page = await api(`/skills?limit=50${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`)
    items.push(...page.items)
    cursor = page.nextCursor
  } while (cursor)
  return items
}
