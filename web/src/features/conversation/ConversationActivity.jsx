import React from 'react'
import { Markdown } from '../../shared/ui/Markdown.jsx'
import { ScrollArea } from '../../shared/ui/ScrollArea.jsx'
import { Icon } from '../../shared/ui/Icon.jsx'
import { active } from '../agent/status.js'

const actions = {
  read_project: '查阅作品信息', list_chapters: '查看章节目录', read_chapter: '阅读章节',
  search_chapters: '搜索章节', list_settings: '查看设定目录', read_settings: '查阅设定',
  search_settings: '搜索设定', propose_chapter_edit: '整理章节修改建议',
  propose_setting_edit: '整理设定修改建议', read_skill: '加载 Skill 指导',
  read_skill_file: '查阅 Skill 文件', run_skill_script: '运行 Skill 脚本',
}

function activityLabel(step, chapterNames, skills) {
  const args = step.args || {}, data = step.result?.data || {}
  const chapter = data.title || chapterNames[args.chapterId]
  const skill = skills?.find(s => s.skillId === args.skillId)?.metadata?.name
  switch (step.toolName) {
    case 'read_chapter': return chapter ? `阅读《${chapter}》` : '阅读章节'
    case 'read_settings': {
      const titles = data.settings?.map(item => item.title).filter(Boolean)
      return titles?.length ? `查阅设定：${titles.join('、')}` : actions.read_settings
    }
    case 'propose_chapter_edit': return chapter ? `整理《${chapter}》的修改建议` : actions.propose_chapter_edit
    case 'search_chapters': case 'search_settings':
      return `${actions[step.toolName]}${args.keyword ? `：“${args.keyword}”` : ''}`
    case 'read_skill': return skill ? `加载“${skill}”指导` : actions.read_skill
    case 'read_skill_file': case 'run_skill_script':
      return `${actions[step.toolName]}${args.relativePath ? `：${args.relativePath}` : ''}`
    default: return actions[step.toolName] || '使用工具'
  }
}

function ToolActivity({ step, chapterNames, skills }) {
  const label = activityLabel(step, chapterNames, skills)
  const running = step.status === 'STARTED'
  const stopped = step.status === 'ABORTED'
  const succeeded = step.status === 'SUCCEEDED'
  const state = running ? '正在' : stopped ? '已停止：' : succeeded ? '已' : '未完成：'
  return <details className={`conversationToolActivity ${running ? 'running' : succeeded ? 'completed' : 'interrupted'}`}>
    <summary><span className="activitySymbol" aria-hidden="true">{running ? <Icon name="loading" size={14} /> : succeeded ? '✓' : stopped ? '−' : '!'}</span>
      <span role={running ? 'status' : undefined}>{state}{label}</span><span className="activityToggle"><Icon name="chevronDown" size={12} /></span></summary>
    <div className="activityToolDetail">
      <p>{step.toolName}{step.errorCode ? ` · ${step.errorCode}` : ''}</p>
      {step.summary && <p>{step.summary}</p>}
      <ScrollArea as="pre" tabIndex={0} aria-label={`${label} 参数`}>{JSON.stringify(step.args, null, 2)}</ScrollArea>
      {step.result && <ScrollArea as="pre" tabIndex={0} aria-label={`${label} 结果`}>{JSON.stringify(step.result, null, 2)}</ScrollArea>}
    </div>
  </details>
}

export function ConversationActivity({ run, chapterNames, resources, connection, cancelRequested }) {
  if (!run) return null
  const draft = run.output?.text && run.status !== 'SUCCEEDED'
    && !(run.activities || []).some(item => item.modelCallIndex === run.output.modelCallIndex)
      ? run.output : null
  // A model response's public text precedes its sequential tool calls. Stable IDs keep
  // expanded activities in place when snapshots refresh or the final answer arrives.
  const timeline = [
    ...(run.activities || []).map(item => ({ ...item, kind: 'commentary' })),
    ...(draft ? [{ id: `draft-${draft.modelCallIndex}`, modelCallIndex: draft.modelCallIndex,
      kind: 'draft', text: draft.text }] : []),
    ...(run.steps || []).map((step, order) => ({ id: step.toolExecutionId, modelCallIndex: step.modelCallIndex,
      kind: 'tool', step, order })),
  ].sort((a, b) => (a.modelCallIndex || 0) - (b.modelCallIndex || 0)
    || Number(a.kind === 'tool') - Number(b.kind === 'tool')
    || (a.order || 0) - (b.order || 0))
  const processing = active(run.status)
  const runningTool = run.steps?.some(step => step.status === 'STARTED')
  const status = cancelRequested ? '正在停止…' : connection === 'reconnecting' ? '连接中断，正在重连并查询状态…'
    : run.status === 'QUEUED' ? '正在排队…' : '正在分析并组织回复…'
  return <div className="conversationActivity">
    {timeline.map(item => item.kind !== 'tool'
      ? <div key={`commentary-${item.id}`} className={item.kind === 'draft' ? 'assistantDraft' : undefined}>
        {item.kind === 'draft' && <small className="assistantDraftLabel">{active(run.status) ? '生成中' : '未完成的回复'}</small>}
        <Markdown className="conversationMarkdown assistantCommentary" content={item.text} />
      </div>
      : <ToolActivity key={`tool-${item.id}`} step={item.step} chapterNames={chapterNames} skills={resources?.skills} />)}
    {processing && (!runningTool || cancelRequested || connection === 'reconnecting') && <div className="assistantProgress" role="status">
      <Icon name="loading" size={16} />{status}</div>}
  </div>
}
