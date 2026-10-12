export function skillToken(name) {
  return /^[\p{L}_-][\p{L}\p{N}_-]{0,63}$/u.test(name) ? `$${name}` : `$[${name}]`
}

export function skillTokens(text) {
  return [...text.matchAll(/(?<![\p{L}\p{N}_$])\$(?:\[([^\]\r\n]{1,64})\]|([\p{L}_-][\p{L}\p{N}_-]{0,63}))/gu)]
    .map(match => ({ name: match[1] ?? match[2], start: match.index, end: match.index + match[0].length }))
}

export function mentionsFromText(text, skills, remembered = []) {
  const known = new Map(skills.filter(item => item.skill.enabled).map(item => [item.skill.name, item.skill.id]))
  // A chosen reference retains its identity even when its Skill is renamed or disabled.
  for (const mention of remembered) known.set(mention.name, mention.skillId)
  const mentions = new Map()
  for (const token of skillTokens(text)) {
    const skillId = known.get(token.name)
    if (skillId && !mentions.has(skillId)) mentions.set(skillId, { skillId, name: token.name })
  }
  return [...mentions.values()]
}

export function activeSkillQuery(text, cursor) {
  const prefix = text.slice(0, cursor)
  const match = prefix.match(/(?:^|[^\p{L}\p{N}_$])\$(?:\[([^\]\r\n]*)|([\p{L}\p{N}_-]*))$/u)
  if (!match) return null
  const start = prefix.lastIndexOf('$')
  return { start, end: cursor, query: match[1] ?? match[2] }
}

export function removeSkillMention(text, name) {
  let result = text
  for (const token of skillTokens(text).filter(token => token.name === name).reverse())
    result = result.slice(0, token.start) + result.slice(token.end)
  return result
}
