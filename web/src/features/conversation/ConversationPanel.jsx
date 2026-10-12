import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { api, readableError } from '../../shared/api/client.js'
import { ScrollArea } from '../../shared/ui/ScrollArea.jsx'
import { Icon } from '../../shared/ui/Icon.jsx'
import { Tooltip } from '../../shared/ui/Tooltip.jsx'
import { SourceView } from '../history/SourceView.jsx'
import { loadSkillList } from '../skills/skills.api.js'
import { activeSkillQuery, mentionsFromText, removeSkillMention, skillToken } from '../skills/mentions.js'
import { useConversation } from './useConversation.js'
import { ConversationTurn } from './ConversationTurn.jsx'
import { ConversationSkills } from './ConversationSkills.jsx'
import { ConversationHistory } from './ConversationHistory.jsx'
import './conversation.css'

export function ConversationPanel({ projectId, chapter, dirty, projectBusy, review, getContext, proposalStatuses, onOpenProposal, onActivityChange, skillRevision, visible = true, onUnreadChange }) {
  const chat = useConversation({ projectId, getContext, projectBusy, onActivityChange })
  const [picker, setPicker] = useState(false), [skillPicker, setSkillPicker] = useState(false)
  const [skills, setSkills] = useState([]), [source, setSource] = useState(null), [skillError, setSkillError] = useState('')
  const [newReply, setNewReply] = useState(false), [unseen, setUnseen] = useState(false)
  const [inputFocused, setInputFocused] = useState(false)
  const messagesArea = useRef(null), textarea = useRef(null), composing = useRef(false)
  const atBottom = useRef(true), lastMessage = useRef(null), readingPositions = useRef({}), restoreReading = useRef(true)
  const [chapterNames, setChapterNames] = useState({})
  const skillRequest = useRef(0)
  const loadSkills = useCallback(async () => {
    const request = ++skillRequest.current
    try {
      const all = await loadSkillList()
      if (request === skillRequest.current) { setSkills(all); setSkillError('') }
    } catch (e) { if (request === skillRequest.current) setSkillError(readableError(e)) }
  }, [])
  useEffect(() => { loadSkills(); return () => { skillRequest.current++ } }, [loadSkills, skillRevision])
  const [skillQuery, setSkillQuery] = useState(null), [suggestionIndex, setSuggestionIndex] = useState(0)
  useEffect(() => { setSkillQuery(null); setSkillPicker(false) }, [chat.id])
  const mentions = mentionsFromText(chat.draft.input, skills, chat.draft.mentions)
  const suggestions = skills.filter(item => item.skill.enabled && item.currentVersion?.status !== 'UNAVAILABLE'
    && `${item.skill.name} ${item.currentVersion?.metadata.description || ''}`.toLocaleLowerCase().includes((skillQuery?.query || '').toLocaleLowerCase()))
  function updateInput(value, cursor) {
    chat.updateDraft({ input: value, mentions: mentionsFromText(value, skills, chat.draft.mentions) })
    setSkillQuery(activeSkillQuery(value, cursor)); setSuggestionIndex(0)
  }
  function referenceSkill(item) {
    const input = textarea.current, value = chat.draft.input
    const query = skillQuery || activeSkillQuery(value, input.selectionStart)
    const start = query?.start ?? input.selectionStart, end = query?.end ?? input.selectionEnd
    const token = skillToken(item.skill.name)
    const prefix = start > 0 && !/\s$/.test(value.slice(0, start)) ? ' ' : ''
    const inserted = `${prefix}${token} `
    const next = value.slice(0, start) + inserted + value.slice(end)
    chat.updateDraft({ input: next, mentions: mentionsFromText(next, skills, [...mentions, { skillId: item.skill.id, name: item.skill.name }]) })
    setSkillPicker(false); setSkillQuery(null)
    requestAnimationFrame(() => { input.focus(); input.setSelectionRange(start + inserted.length, start + inserted.length) })
  }
  useEffect(() => { if (chapter) setChapterNames(current => ({ ...current, [chapter.id]: chapter.title })) }, [chapter?.id, chapter?.title])
  useEffect(() => {
    let disposed = false
    api(`/projects/${projectId}/chapters?limit=50`).then(page => {
      if (!disposed) setChapterNames(current => ({ ...Object.fromEntries(page.items.map(c => [c.id, c.title])), ...current }))
    }).catch(() => {})
    return () => { disposed = true }
  }, [projectId])
  useLayoutEffect(() => {
    if (!visible) return
    const element = textarea.current
    element.style.height = 'auto'
    element.style.height = `${Math.min(120, Math.max(24, element.scrollHeight))}px`
  }, [chat.draft.input, visible])
  const scrollToBottom = useCallback(() => {
    const area = messagesArea.current
    if (area) area.scrollTop = area.scrollHeight
    atBottom.current = true; setUnseen(false); setNewReply(false)
  }, [])
  useLayoutEffect(() => {
    if (!visible) { restoreReading.current = true; return }
    setNewReply(false)
    if (!chat.loading && restoreReading.current) {
      const saved = readingPositions.current[chat.id]
      atBottom.current = saved?.bottom ?? true
      if (atBottom.current) scrollToBottom()
      else if (messagesArea.current) messagesArea.current.scrollTop = saved.top
      restoreReading.current = false
    }
    const key = chat.messages.at(-1)?.id
    if (key && lastMessage.current !== key) {
      if (atBottom.current) scrollToBottom()
      else setUnseen(true)
    }
    lastMessage.current = key
  }, [chat.messages, chat.loading, chat.id, visible, scrollToBottom])
  useEffect(() => {
    if (!visible || !globalThis.ResizeObserver) return
    const observer = new ResizeObserver(() => { if (atBottom.current) scrollToBottom() })
    observer.observe(messagesArea.current.firstElementChild)
    return () => observer.disconnect()
  }, [visible, scrollToBottom])
  useEffect(() => { lastMessage.current = null; setSource(null); setUnseen(false); setNewReply(false) }, [chat.id])
  useEffect(() => {
    if (!visible && chat.messages.length && chat.messages.at(-1)?.id !== lastMessage.current) setNewReply(true)
  }, [chat.messages, visible])
  useEffect(() => { onUnreadChange?.(newReply) }, [newReply, onUnreadChange])
  const canSend = !chat.loading && (!chat.id || Boolean(chat.conversation)) && !chat.busy && !chat.taskActive && !chat.unavailableReason && !projectBusy
    && Boolean(chat.draft.input.trim())
    && [...chat.draft.input].length <= 8000
  function send(event) {
    event.preventDefault()
    if (!canSend) return
    atBottom.current = true
    setSkillQuery(null); chat.send(mentions)
  }
  function selectConversation(id) { restoreReading.current = true; chat.select(id); setPicker(false) }
  function openHistory() {
    setPicker(true)
    chat.loadList().catch(() => {})
  }
  const lastUser = chat.messages.filter(m => m.role === 'USER').at(-1)
  const replies = new Map(chat.messages.filter(m => m.role === 'ASSISTANT' && m.runId).map(m => [m.runId, m]))
  const userRuns = new Set(chat.messages.filter(m => m.role === 'USER' && m.runId).map(m => m.runId))
  const turns = chat.messages.filter(m => m.role === 'USER' || !userRuns.has(m.runId))
  const status = chat.taskActive ? chat.cancelRequested ? '正在停止…' : chat.connection === 'reconnecting' ? '连接中断，正在重连并查询状态…' : '正在处理…'
    : newReply ? '有新回复' : ''
  const inputLength = [...chat.draft.input].length
  const skillLabel = '引用 Skill'
  const sendLabel = chat.busy ? '正在提交' : chat.taskActive ? chat.cancelRequested ? '正在停止' : '停止' : '发送'
  return <section className="conversationPanel" aria-label="写作助手" hidden={!visible}>
    <div className="assistantInner assistantHeader">
      <div className="assistantHeading"><div className="assistantTitle"><Icon name="message" size={17} /><h2>写作助手</h2>
        {status && <span className="assistantStatus" role="status">{status}</span>}</div>
        <span className="conversationName">{chat.conversation?.title || '新对话'}</span>
      </div>
      <div className="assistantActions">
        <Tooltip label="历史对话"><button className="assistantIconButton" aria-label="历史对话" disabled={chat.busy}
          onClick={openHistory}><Icon name="history" /></button></Tooltip>
        <Tooltip label="新对话"><button className="assistantIconButton" aria-label="新对话" disabled={chat.busy}
          onClick={() => selectConversation('')}><Icon name="plus" /></button></Tooltip>
      </div>
    </div>
    <ScrollArea ref={messagesArea} id="assistant-messages" className="conversationMessages"
      onScroll={event => {
        if (!visible) return
        const area = event.currentTarget
        atBottom.current = area.scrollHeight - area.scrollTop - area.clientHeight < 48
        readingPositions.current[chat.id] = { top: area.scrollTop, bottom: atBottom.current }
        if (atBottom.current) setUnseen(false)
      }}>
      <div className="assistantInner messageList">
        {chat.loading && <p className="muted">正在恢复对话…</p>}
        {turns.map(m => <ConversationTurn key={m.id} message={m} reply={m.role === 'USER' ? replies.get(m.runId) : null} run={chat.runs[m.runId]} chapterNames={chapterNames}
          review={review} proposalStatuses={proposalStatuses} connection={chat.connection} onOpenProposal={onOpenProposal} onSource={(value, run) => setSource({ value, run })}
          cancelRequested={chat.cancelRequested && chat.conversation?.activeRunId === m.runId}
          onRetry={chat.retry} canRetry={lastUser?.id === m.id && !chat.taskActive && !chat.busy && !projectBusy} />)}
        {!chat.loading && chat.messages.length === 0 && <p className="assistantEmptyMessage">告诉助手你的需求，它会按需查阅作品、分析问题或提出修改建议。</p>}
      </div>
      {unseen && <button className="newReplyButton" onClick={scrollToBottom}>查看新回复 ↓</button>}
    </ScrollArea>
    <form onSubmit={send} className="assistantInner conversationComposer" aria-label="写作助手输入区">
      <div className="assistantInputBox">
        <textarea ref={textarea} aria-label="对话消息" rows={1} placeholder="告诉助手你想做什么…"
          value={chat.draft.input} disabled={chat.busy}
          onFocus={() => setInputFocused(true)} onBlur={() => setInputFocused(false)}
          onChange={event => updateInput(event.target.value, event.target.selectionStart)}
          onSelect={event => { if (!composing.current) setSkillQuery(activeSkillQuery(event.target.value, event.target.selectionStart)) }}
          onCompositionStart={() => { composing.current = true }} onCompositionEnd={event => { composing.current = false; setSkillQuery(activeSkillQuery(event.target.value, event.target.selectionStart)) }}
          aria-expanded={Boolean(skillQuery)} aria-controls={skillQuery ? 'skill-suggestions' : undefined}
          aria-activedescendant={skillQuery && suggestions.length ? `skill-suggestion-${Math.min(suggestionIndex, suggestions.length - 1)}` : undefined}
          onKeyDown={event => {
            if (skillQuery && !composing.current && !event.nativeEvent.isComposing) {
              if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); setSkillQuery(null); return }
              if (suggestions.length && ['ArrowDown', 'ArrowUp', 'Enter'].includes(event.key) && !event.shiftKey) {
                event.preventDefault()
                const index = Math.min(suggestionIndex, suggestions.length - 1)
                if (event.key === 'Enter') referenceSkill(suggestions[index])
                else setSuggestionIndex((index + (event.key === 'ArrowDown' ? 1 : -1) + suggestions.length) % suggestions.length)
                return
              }
            }
            if (event.key === 'Enter' && !event.shiftKey && !composing.current && !event.nativeEvent.isComposing && event.keyCode !== 229) send(event)
          }} />
        {skillQuery && !composing.current && <div id="skill-suggestions" className="skillSuggestions" role="listbox" aria-label="Skill 引用建议">
          {suggestions.map((item, index) => <button type="button" role="option" aria-selected={index === Math.min(suggestionIndex, suggestions.length - 1)}
            id={`skill-suggestion-${index}`} key={item.skill.id} disabled={chat.busy} onMouseDown={event => event.preventDefault()} onClick={() => referenceSkill(item)}>
            <strong>{item.skill.name}</strong><span>{item.currentVersion?.metadata.description}</span>
          </button>)}
          {suggestions.length === 0 && <p>{skillError || '暂无匹配的 Skill，请在设置中添加并启用。'}</p>}
        </div>}
        {mentions.length > 0 && <div className="assistantSkillReferences" aria-label="消息中的 Skill 引用">{mentions.map(mention => <span key={mention.skillId}>{mention.name}
          <button type="button" aria-label={`移除 Skill 引用：${mention.name}`} disabled={chat.busy} onClick={() => {
            const value = removeSkillMention(chat.draft.input, mention.name)
            updateInput(value, value.length); setSkillQuery(null)
          }}><Icon name="close" size={12} /></button>
        </span>)}</div>}
        <div className="assistantInputTools"><div className="assistantContext">
          <Tooltip label={skillLabel}><button type="button" className="assistantIconButton assistantSkillButton" aria-label="引用 Skill"
            disabled={chat.busy} onClick={() => { setSkillQuery(null); setSkillPicker(true); loadSkills() }}>
            <Icon name="skill" />
          </button></Tooltip>
          {chapter && chat.draft.useChapter !== false ? <div className="assistantChapterReference">
            <Tooltip label={`优先参考：${chapter.title} · 已保存 v${chapter.version}`}>
              <span className="assistantChapterLabel"><Icon name="book" size={14} /><span>{chapter.title}</span></span>
            </Tooltip>
            <Tooltip label="移除章节参考"><button type="button" className="assistantReferenceRemove" aria-label="移除章节参考" disabled={chat.busy}
              onClick={() => chat.updateDraft({ useChapter: false })}><Icon name="close" size={12} /></button></Tooltip>
          </div> : chapter && <button type="button" className="link assistantUseChapter" disabled={chat.busy}
            onClick={() => chat.updateDraft({ useChapter: true })}>使用当前章节</button>}
        </div>
          <Tooltip label={sendLabel}><button type={chat.taskActive ? 'button' : 'submit'} className="assistantSend" aria-label={sendLabel}
            disabled={chat.taskActive ? chat.cancelRequested || chat.busy : !canSend}
            onClick={chat.taskActive ? chat.stop : undefined}>
            <Icon name={chat.busy ? 'loading' : chat.taskActive ? 'stop' : 'send'} size={16} />
          </button></Tooltip>
        </div>
      </div>
      <div className="assistantHelp"><span>{chat.unavailableReason || (dirty && chat.draft.useChapter !== false ? '助手读取已保存版本，本轮不包含未保存正文。' : projectBusy && !chat.taskActive ? '作品有任务正在执行，结束后可发送。' : 'Enter 发送 · Shift+Enter 换行')}
      </span>{(inputFocused || inputLength >= 6000) && <span className={inputLength > 8000 ? 'error' : ''}>{inputLength}/8000</span>}</div>
      {chat.error && !picker && <p className="error assistantError" role="alert">{chat.error}</p>}
    </form>
    {picker && <ConversationHistory chat={chat} onSelect={selectConversation} onClose={() => setPicker(false)} />}
    {skillPicker && <ConversationSkills skills={skills} loadError={skillError} onReload={loadSkills} onClose={() => setSkillPicker(false)} onChoose={referenceSkill} />}
    {source && <SourceView key={source.value.id} source={source.value} step={source.run.steps?.find(s => s.toolExecutionId === source.value.toolExecutionId)} close={() => setSource(null)} />}
  </section>
}
