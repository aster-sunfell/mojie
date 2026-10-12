import { expect, test } from 'vitest'
import { activeSkillQuery, mentionsFromText, removeSkillMention, skillToken } from './mentions.js'

const skills = [{ skill: { id: 's', name: '人物检查', enabled: true } }]
test('references survive renames by ID, disappear when removed, and avoid prefix matches', () => {
  const remembered = [{ skillId: 's', name: '人物检查' }]
  expect(mentionsFromText('使用 $人物检查 检查', [], remembered)).toEqual(remembered)
  expect(mentionsFromText('使用 $人物检查新版 检查', skills, remembered)).toEqual([])
  expect(mentionsFromText('不再引用', skills, remembered)).toEqual([])
  expect(removeSkillMention('$人物检查 检查，$人物检查新版', '人物检查')).toBe(' 检查，$人物检查新版')
})
test('names with spaces or punctuation use bracketed references', () => {
  expect(skillToken('character-check')).toBe('$character-check')
  expect(skillToken('人物 检查')).toBe('$[人物 检查]')
  const special = [{ skill: { id: 'x', name: '人物 检查', enabled: true } }]
  expect(mentionsFromText('请用 $[人物 检查] 核对', special)).toEqual([{ skillId: 'x', name: '人物 检查' }])
  expect(activeSkillQuery('请用 $[人物', 7)).toMatchObject({ query: '人物', start: 3 })
  expect(activeSkillQuery('价格 $100 ', 8)).toBeNull()
})
