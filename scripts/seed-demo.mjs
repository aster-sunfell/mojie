#!/usr/bin/env node

// Repeatable content for the MVP PRD 10.2 walkthrough. Each run creates a new project.
const base = (process.env.MOJIE_API_URL || 'http://127.0.0.1:8080/api/v1').replace(/\/$/, '')
const scale = process.argv.includes('--scale')

async function api(path, method = 'GET', body) {
  const response = await fetch(`${base}${path}`, {
    method,
    headers: body === undefined ? undefined : { 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const result = await response.json()
  if (!response.ok) throw new Error(`${method} ${path}: ${response.status} ${result.code || ''} ${result.message || ''}`)
  return result.data
}

const project = await api('/projects', 'POST', { title: scale ? 'M4 固定负载数据' : 'M4 林舟演示' })
const chapters = [
  ['第一章 · 河边', '林舟不会游泳。\n他走到河边，听见急流拍打石岸。\n朋友邀他下水，林舟明确拒绝了。\n他再次说：我不会游泳。'],
  ['第二章 · 过渡', '林舟沿着山路前行。\n夜里，他整理地图，计划明天的行程。\n这一章没有水上训练或能力变化。'],
  ['第三章 · 深水', '暴雨之后，林舟潜入深水，独自游到对岸。\n他没有借助船只，也没有描述此前学会游泳。\n这一段可能需要补充解释。'],
]
const settings = [
  ['CHARACTER', '林舟', '林舟不会游泳，遇到深水会避免下水。'],
  ['WORLD', '河流', '山谷河流湍急，雨后水位上涨。'],
  ['RULE', '能力变化', '角色学会新技能时，应交代训练或其他合理原因。'],
]
const ids = []
for (const [title, content] of chapters) {
  const chapter = await api(`/projects/${project.id}/chapters`, 'POST', { title })
  await api(`/chapters/${chapter.id}/content`, 'PUT', { content, expectedVersion: 1 })
  ids.push(chapter.id)
}
for (const [type, title, content] of settings)
  await api(`/projects/${project.id}/settings`, 'POST', { type, title, content })

if (scale) {
  for (let index = chapters.length + 1; index <= 100; index++) {
    const chapter = await api(`/projects/${project.id}/chapters`, 'POST', { title: `负载章节 ${index}` })
    await api(`/chapters/${chapter.id}/content`, 'PUT', {
      content: `第 ${index} 章。林舟沿路记录见闻。`, expectedVersion: 1,
    })
  }
  for (let index = settings.length + 1; index <= 200; index++)
    await api(`/projects/${project.id}/settings`, 'POST', {
      type: 'WORLD', title: `负载设定 ${index}`, content: `用于分页与延迟验证的固定设定 ${index}。`,
    })
}

console.log(JSON.stringify({ projectId: project.id, targetChapterId: ids[2], noChangeChapterId: ids[1],
  chapterCount: scale ? 100 : 3, settingCount: scale ? 200 : 3 }, null, 2))
