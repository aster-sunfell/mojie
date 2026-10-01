// Require an unambiguous original or suggested span with surviving adjacent context.
// Never replace a whole chapter, or guess a position from the original offsets.
function candidates(text, value, item) {
  const positions = []
  let from = 0
  while (from <= text.length) {
    const start = value ? text.indexOf(value, from) : from
    if (start < 0) break
    const end = start + value.length
    const left = item.contextBefore || ''
    const right = item.contextAfter || ''
    const leftMatch = left ? text.slice(Math.max(0, start - left.length), start) === left : start === 0
    const rightMatch = right ? text.slice(end, end + right.length) === right : end === text.length
    // Insertions/deletions need both anchors because the empty string is everywhere.
    if ((!value && leftMatch && rightMatch) || (value && (leftMatch || rightMatch)))
      positions.push({ start, end, exact: leftMatch && rightMatch })
    from = start + 1
  }
  const exact = positions.filter(position => position.exact)
  return exact.length ? exact : positions
}

export function locateChange(base, draft, item) {
  if (draft === base) return { status: 'READY', start: item.start, end: item.end }
  const before = candidates(draft, item.before, item)
  const after = candidates(draft, item.after, item)
  const exactBefore = before.filter(position => position.exact)
  const exactAfter = after.filter(position => position.exact)
  if (exactBefore.length === 1 && exactAfter.length === 0) return { status: 'READY', ...exactBefore[0] }
  if (exactAfter.length === 1 && exactBefore.length === 0) return { status: 'INCLUDED', ...exactAfter[0] }
  if (exactBefore.length || exactAfter.length) return { status: 'CONFLICT', current: null }
  if (before.length === 1 && after.length === 0) return { status: 'READY', ...before[0] }
  if (after.length === 1 && before.length === 0) return { status: 'INCLUDED', ...after[0] }
  // Context may have been edited; keep a best-effort excerpt for manual review only.
  const left = item.contextBefore || ''
  const right = item.contextAfter || ''
  const start = left ? draft.indexOf(left) : 0
  const contentStart = start < 0 ? -1 : start + left.length
  const end = contentStart < 0 ? -1 : right ? draft.indexOf(right, contentStart) : draft.length
  return { status: 'CONFLICT', current: contentStart >= 0 && end >= contentStart
    ? draft.slice(contentStart, end) : null }
}

export function applyChange(base, draft, item) {
  const location = locateChange(base, draft, item)
  if (location.status === 'CONFLICT') return { ...location, draft }
  return { ...location, draft: location.status === 'INCLUDED' ? draft
    : draft.slice(0, location.start) + item.after + draft.slice(location.end) }
}

export function locateChanges(base, draft, items) {
  const result = new Map(items.map(item => [item.id, locateChange(base, draft, item)]))
  const located = items.map(item => ({ id: item.id, ...result.get(item.id) }))
    .filter(item => item.status !== 'CONFLICT').sort((a, b) => a.start - b.start)
  let group = [], end = -1
  for (const item of located) {
    // Different original edits must never be mapped onto the same current text.
    if (group.length && (item.start < end || item.start === group[0].start)) {
      for (const member of [...group, item]) result.set(member.id, { status: 'CONFLICT', current: null })
      group.push(item); end = Math.max(end, item.end)
    } else { group = [item]; end = item.end }
  }
  return result
}

export function reviewStatus(items) {
  const applied = items.some(item => item.status === 'APPLIED')
  return items.every(item => item.status !== 'PENDING') ? applied ? 'APPLIED' : 'REJECTED'
    : applied ? 'PARTIALLY_APPLIED' : 'PENDING'
}

export function reviewReference(base, items) {
  items = items.map(item => {
    if (item.status !== 'APPLIED' || item.editedContent == null) return item
    const range = lineRange(base, item.start, item.end)
    return { ...item, ...range, before: base.slice(range.start, range.end), after: item.editedContent }
  })
  const applied = items.filter(item => item.status === 'APPLIED').sort((a, b) => b.start - a.start)
  let reference = base
  for (const item of applied) reference = reference.slice(0, item.start) + item.after + reference.slice(item.end)
  return { base: reference, items: items.map(item => {
    const shift = applied.filter(previous => previous.id !== item.id && previous.end <= item.start)
      .reduce((total, previous) => total + previous.after.length - previous.before.length, 0)
    const start = item.start + shift, end = start + (item.status === 'APPLIED' ? item.after.length : item.before.length)
    return { ...item, start, end, contextBefore: Array.from(reference.slice(0, start)).slice(-40).join(''),
      contextAfter: Array.from(reference.slice(end)).slice(0, 40).join('') }
  }) }
}

// Include the complete affected lines, while preserving all separators exactly.
export function lineRange(text, start, end) {
  const lineStart = start === 0 ? 0 : text.lastIndexOf('\n', start - 1) + 1
  const nextBreak = text.indexOf('\n', Math.max(start, end - 1))
  return { start: lineStart, end: nextBreak < 0 ? text.length : Math.max(end, nextBreak) }
}

function anchoredRange(text, left, right) {
  const start = left ? text.indexOf(left) : 0
  if (start < 0 || (left && text.indexOf(left, start + 1) >= 0)) return null
  const from = start + left.length
  const end = right ? text.indexOf(right, from) : text.length
  if (end < from || (right && text.indexOf(right, end + 1) >= 0)) return null
  return { start: from, end }
}

export function inlineDocument(base, draft, items) {
  const reference = reviewReference(base, items)
  const locations = locateChanges(reference.base, draft, reference.items.filter(item => item.status === 'PENDING'))
  const frames = items.map(item => {
    const adjusted = reference.items.find(value => value.id === item.id)
    const location = item.status === 'PENDING' ? locations.get(item.id) : locateReviewedText(reference.base, draft, adjusted)
    let span = location && location.status !== 'CONFLICT' ? location : null
    if (!span) span = anchoredRange(draft, adjusted.contextBefore, adjusted.contextAfter)
    const originalRange = lineRange(base, item.start, item.end)
    const original = base.slice(originalRange.start, originalRange.end)
    const suggested = base.slice(originalRange.start, item.start) + item.after + base.slice(item.end, originalRange.end)
    // Full-line anchors can survive manual changes inside the line.
    if (!span) {
      const range = lineRange(reference.base, adjusted.start, adjusted.end)
      span = anchoredRange(draft, Array.from(reference.base.slice(0, range.start)).slice(-40).join(''),
        Array.from(reference.base.slice(range.end)).slice(0, 40).join(''))
    }
    const range = span ? lineRange(draft, span.start, span.end) : null
    const current = range ? draft.slice(range.start, range.end) : null
    let preview = suggested
    if (range && item.status === 'PENDING' && location?.status === 'READY')
      preview = draft.slice(range.start, location.start) + item.after + draft.slice(location.end, range.end)
    else if (range && location?.status === 'INCLUDED') preview = current
    const stalePreview = !!item.preview && item.preview.against !== current
    return { item, range, original, current, preview: item.preview?.text ?? preview,
      location, conflict: item.status === 'PENDING' && (location?.status === 'CONFLICT' || stalePreview) }
  })
  const located = frames.filter(frame => frame.range).sort((a, b) => a.range.start - b.range.start)
  // A manual join can put separate edits on one line; render those suggestions below
  // the draft instead of duplicating or hiding part of the author's text.
  const overlaps = new Set()
  for (let i = 0; i < located.length; i++) for (let j = i + 1; j < located.length; j++) {
    if (located[j].range.start >= located[i].range.end && located[j].range.start !== located[i].range.start) break
    overlaps.add(located[i].item.id); overlaps.add(located[j].item.id)
  }
  const segments = []
  let cursor = 0
  for (const frame of located.filter(value => !overlaps.has(value.item.id))) {
    if (frame.range.start > cursor) segments.push({ type: 'text', key: `before-${frame.item.id}`, start: cursor,
      end: frame.range.start, text: draft.slice(cursor, frame.range.start) })
    segments.push({ type: 'change', key: `change-${frame.item.id}`, ...frame })
    cursor = frame.range.end
  }
  if (cursor < draft.length || !segments.length) segments.push({ type: 'text', key: 'tail', start: cursor,
    end: draft.length, text: draft.slice(cursor) })
  for (const frame of frames.filter(value => !value.range || overlaps.has(value.item.id)))
    segments.push({ type: 'change', key: `change-${frame.item.id}`, ...frame, range: null, current: null,
      conflict: frame.item.status === 'PENDING' })
  return { segments, frames }
}

export function locateReviewedText(base, draft, item) {
  if (base === draft) return { start: item.start, end: item.end }
  const value = item.status === 'APPLIED' ? item.after : item.before
  const matches = candidates(draft, value, item)
  return matches.length === 1 ? matches[0] : null
}
