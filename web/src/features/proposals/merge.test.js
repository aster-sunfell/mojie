import { expect, test } from 'vitest'
import { applyChange, locateChange, locateChanges, reviewReference } from './merge.js'

const base = '林舟游过河。\n\n他拿起红灯。\n'
const changes = [ ['游过河', '走过桥'], ['红', '蓝'] ].map(([before, after], id) => {
  const start = base.indexOf(before), end = start + before.length
  return { id, before, after, start, end, contextBefore: base.slice(0, start), contextAfter: base.slice(end), status: 'PENDING' }
})

test('applies a local change without overwriting unrelated manual edits', () => {
  const result = applyChange(base, `${base}自己补写的细节。`, changes[0])
  expect(result.status).toBe('READY')
  expect(result.draft).toBe('林舟走过桥。\n\n他拿起红灯。\n自己补写的细节。')
})
test('rebases remaining changes after an earlier accepted change and manual editing', () => {
  const items = changes.map(item => ({ ...item, status: item.id === 0 ? 'APPLIED' : 'PENDING' }))
  const reference = reviewReference(base, items)
  const result = applyChange(reference.base, `${reference.base}手动补写`, reference.items[1])
  expect(result.status).toBe('READY')
  expect(result.draft).toContain('蓝灯。\n手动补写')
})
test('same-position manual edits are preserved and surfaced for review', () => {
  const edited = base.replace('游过河', '沿山路绕行')
  expect(applyChange(base, edited, changes[0])).toMatchObject({ status: 'CONFLICT', draft: edited, current: '沿山路绕行' })
})
test('repeated anchors are ambiguous and cannot replace the wrong occurrence', () => {
  const item = { before: '旧', after: '新', contextBefore: '甲', contextAfter: '乙' }
  expect(applyChange('甲旧乙', '甲旧乙甲旧乙', item).status).toBe('CONFLICT')
})
test('already included text is recognized even when it contains the old text', () => {
  const item = { before: 'a', after: 'aa', contextBefore: 'x', contextAfter: 'y' }
  expect(applyChange('xay', 'xaay', item)).toMatchObject({ status: 'INCLUDED', draft: 'xaay' })
})
test('insertion and deletion require a unique pair of surrounding anchors', () => {
  const insertion = { before: '', after: '新', start: 1, end: 1, contextBefore: '甲', contextAfter: '乙' }
  expect(applyChange('甲乙', '甲乙附加', insertion).draft).toBe('甲新乙附加')
  expect(locateChange('甲乙', '甲新乙附加', insertion).status).toBe('INCLUDED')
  const deletion = { ...insertion, before: '旧', after: '' }
  expect(applyChange('甲旧乙', '甲旧乙附加', deletion).draft).toBe('甲乙附加')
  expect(locateChange('甲旧乙', '甲乙附加', deletion).status).toBe('INCLUDED')
})

test('different edit blocks cannot be applied onto the same current span', () => {
  const items = [
    { id: 0, start: 1, end: 2, before: '旧', after: '新', contextBefore: '甲', contextAfter: '乙间隔丙旧丁' },
    { id: 1, start: 7, end: 8, before: '旧', after: '另一', contextBefore: '甲旧乙间隔丙', contextAfter: '丁' },
  ]
  const locations = locateChanges('甲旧乙间隔丙旧丁', '甲旧丁', items)
  expect(locations.get(0).status).toBe('CONFLICT')
  expect(locations.get(1).status).toBe('CONFLICT')
})
