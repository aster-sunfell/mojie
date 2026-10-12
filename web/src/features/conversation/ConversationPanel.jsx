import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { api, readableError } from '../../shared/api/client.js'
import { ScrollArea } from '../../shared/ui/ScrollArea.jsx'
import { Icon } from '../../shared/ui/Icon.jsx'
import { Tooltip } from '../../shared/ui/Tooltip.jsx'
import { SourceView } from '../history/SourceView.jsx'
import { SkillLibrary } from '../skills/SkillLibrary.jsx'
import { useConversation } from './useConversation.js'
import { ConversationTurn } from './ConversationTurn.jsx'
import { ConversationSkills } from './ConversationSkills.jsx'
import { ConversationHistory } from './ConversationHistory.jsx'
import './conversation.css'

export function ConversationPanel({ projectId, chapter, dirty, projectBusy, review, getContext, onOpenProposal, onActivityChange }) {
  const chat = useConversation({ projectId, getContext, projectBusy, onActivityChange })
  const [expanded, setExpanded] = useState(Boolean(chat.id)), [enlarged, setEnlarged] = useState(false)
  const [picker, setPicker] = useState(false), [skillPicker, setSkillPicker] = useState(false), [library, setLibrary] = useState(false)
  const [skills, setSkills] = useState([]), [source, setSource] = useState(null), [skillError, setSkillError] = useState('')
  const [newReply, setNewReply] = useState(false), [unseen, setUnseen] = useState(false)
  const [inputFocused, setInputFocused] = useState(false)
  const [availableHeight, setAvailableHeight] = useState(720), [desiredHeight, setDesiredHeight] = useState(null), [chromeHeight, setChromeHeight] = useState(190)
  const root = useRef(null), messagesArea = useRef(null), textarea = useRef(null), composing = useRef(false)
  const atBottom = useRef(true), lastMessage = useRef(null), readingPositions = useRef({}), restoreReading = useRef(true)
  const [chapterNames, setChapterNames] = useState({})
  const loadSkills = useCallback(async () => {
    let all = [], cursor
    do {
      const page = await api(`/skills?limit=50${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`)
      all.push(...page.items); cursor = page.nextCursor
    } while (cursor)
    setSkills(all); setSkillError('')
  }, [])
  useEffect(() => { loadSkills().catch(e => setSkillError(readableError(e))) }, [loadSkills])
  useEffect(() => { if (chapter) setChapterNames(current => ({ ...current, [chapter.id]: chapter.title })) }, [chapter?.id, chapter?.title])
  useEffect(() => {
    let disposed = false
    api(`/projects/${projectId}/chapters?limit=50`).then(page => {
      if (!disposed) setChapterNames(current => ({ ...Object.fromEntries(page.items.map(c => [c.id, c.title])), ...current }))
    }).catch(() => {})
    return () => { disposed = true }
  }, [projectId])
  useEffect(() => {
    const area = root.current?.parentElement
    if (!area || !globalThis.ResizeObserver) return
    const header = root.current.querySelector('.assistantHeader'), form = root.current.querySelector('form')
    const toolbar = root.current.querySelector('.assistantMessageToolbar')
    const observer = new ResizeObserver(() => {
      setAvailableHeight(area.clientHeight)
      setChromeHeight(header.offsetHeight + form.offsetHeight + (toolbar?.offsetHeight || 0) + 18 + parseFloat(getComputedStyle(root.current).paddingBottom))
    })
    observer.observe(area); observer.observe(header); observer.observe(form); if (toolbar) observer.observe(toolbar); setAvailableHeight(area.clientHeight)
    return () => observer.disconnect()
  }, [expanded])
  useLayoutEffect(() => {
    const element = textarea.current
    element.style.height = 'auto'
    element.style.height = `${Math.min(120, Math.max(48, element.scrollHeight))}px`
  }, [chat.draft.input])
  const scrollToBottom = useCallback(() => {
    const area = messagesArea.current
    if (area) area.scrollTop = area.scrollHeight
    atBottom.current = true; setUnseen(false); setNewReply(false)
  }, [])
  useLayoutEffect(() => {
    if (expanded && !chat.loading && restoreReading.current) {
      const saved = readingPositions.current[chat.id]
      atBottom.current = saved?.bottom ?? true
      if (atBottom.current) scrollToBottom()
      else if (messagesArea.current) messagesArea.current.scrollTop = saved.top
      restoreReading.current = false
    }
    const key = chat.messages.at(-1)?.id
    if (key && lastMessage.current !== key) {
      if (!expanded) setNewReply(true)
      else if (atBottom.current) scrollToBottom()
      else setUnseen(true)
    }
    lastMessage.current = key
  }, [chat.messages, chat.loading, chat.id, expanded, scrollToBottom])
  useEffect(() => {
    if (!expanded || !globalThis.ResizeObserver) return
    const observer = new ResizeObserver(() => { if (atBottom.current) scrollToBottom() })
    observer.observe(messagesArea.current.firstElementChild)
    return () => observer.disconnect()
  }, [expanded, scrollToBottom])
  useEffect(() => { lastMessage.current = null; setSource(null); setUnseen(false); setNewReply(false) }, [chat.id])
  useEffect(() => {
    if (!chat.loading && !chat.messages.length && !chat.taskActive && !chat.busy) setExpanded(false)
  }, [chat.loading, chat.messages.length, chat.taskActive, chat.busy])
  const panelHeight = Math.max(chromeHeight, Math.min(enlarged ? availableHeight * .6 : desiredHeight ?? availableHeight * .4, availableHeight - 260, availableHeight * .6))
  const canSend = !chat.loading && (!chat.id || Boolean(chat.conversation)) && !chat.busy && !chat.taskActive && !chat.unavailableReason && !projectBusy
    && Boolean(chat.draft.input.trim())
    && [...chat.draft.input].length <= 8000
  function send(event) {
    event.preventDefault()
    if (!canSend) return
    setExpanded(true); atBottom.current = true
    chat.send()
  }
  function saveReading() { if (expanded && messagesArea.current) readingPositions.current[chat.id] = { top: messagesArea.current.scrollTop, bottom: atBottom.current } }
  function toggleMessages() { saveReading(); restoreReading.current = true; setExpanded(value => !value); setNewReply(false); setEnlarged(false) }
  function resize(event) {
    if (event.button !== 0 || !expanded || enlarged) return
    event.currentTarget.setPointerCapture(event.pointerId)
    const start = event.clientY, initialHeight = root.current.offsetHeight
    const handle = move => setDesiredHeight(Math.max(190, Math.min(initialHeight + start - move.clientY, availableHeight * .6)))
    const target = event.currentTarget
    const finish = () => { target.removeEventListener('pointermove', handle); target.removeEventListener('pointerup', finish); target.removeEventListener('pointercancel', finish) }
    target.addEventListener('pointermove', handle); target.addEventListener('pointerup', finish); target.addEventListener('pointercancel', finish)
  }
  function selectConversation(id) { saveReading(); restoreReading.current = true; chat.select(id); setPicker(false); setExpanded(Boolean(id)); setEnlarged(false) }
  function openHistory() {
    setPicker(true)
    chat.loadList().catch(() => {})
  }
  async function viewProposal(run) {
    if (await onOpenProposal(run)) {
      setEnlarged(false)
      if (window.matchMedia?.('(max-width: 640px)').matches) setExpanded(false)
    }
  }
  const lastUser = chat.messages.filter(m => m.role === 'USER').at(-1)
  const status = chat.taskActive ? chat.cancelRequested ? '正在停止…' : chat.connection === 'reconnecting' ? '连接中断，正在重连并查询状态…' : '正在处理…'
    : newReply ? '有新回复' : ''
  const inputLength = [...chat.draft.input].length
  const skillLabel = chat.draft.selected.length ? `已选择 ${chat.draft.selected.length} 个 Skill` : '选择 Skill'
  const sendLabel = chat.busy ? '正在提交' : chat.taskActive ? chat.cancelRequested ? '正在停止' : '停止' : '发送'
  return <section ref={root} className={`conversationPanel ${expanded ? 'expanded' : 'collapsed'} ${enlarged ? 'enlarged' : ''}`}
    aria-label="写作助手" style={expanded ? { height: panelHeight } : undefined}>
    <button className="assistantResize" aria-label="调整对话高度" disabled={!expanded || enlarged}
      onPointerDown={resize} onKeyDown={event => {
        if (['ArrowUp', 'ArrowDown'].includes(event.key)) { event.preventDefault(); setDesiredHeight(h => Math.min(availableHeight * .6, Math.max(190, (h ?? panelHeight) + (event.key === 'ArrowUp' ? 30 : -30)))) }
      }}><span /></button>
    <div className="assistantInner assistantHeader">
      <div className="assistantHeading"><Icon name="message" size={17} /><h2>写作助手</h2>
        <span className="assistantHeadingSeparator" aria-hidden="true">·</span>
        <span className="conversationName">{chat.conversation?.title || '新对话'}</span>
        {status && <span className="assistantStatus" role="status">{status}</span>}</div>
      <div className="assistantActions">
        <Tooltip label="历史对话"><button className="assistantIconButton" aria-label="历史对话" disabled={chat.busy}
          onClick={openHistory}><Icon name="history" /></button></Tooltip>
        <Tooltip label="新对话"><button className="assistantIconButton" aria-label="新对话" disabled={chat.busy}
          onClick={() => selectConversation('')}><Icon name="plus" /></button></Tooltip>
        {(chat.loading || chat.messages.length > 0 || chat.taskActive) && <Tooltip label={expanded ? '收起消息' : '展开消息'}>
          <button className="assistantIconButton" aria-label={expanded ? '收起消息' : '展开消息'} aria-expanded={expanded}
            aria-controls="assistant-messages" onClick={toggleMessages}><Icon name={expanded ? 'chevronDown' : 'chevronUp'} /></button></Tooltip>}
      </div>
    </div>
    {expanded && <div className="assistantInner assistantMessageToolbar"><span>对话消息</span>
      <Tooltip label={enlarged ? '恢复高度' : '扩大对话'}><button className="assistantIconButton"
        aria-label={enlarged ? '恢复高度' : '扩大对话'} aria-pressed={enlarged} onClick={() => setEnlarged(value => !value)}>
        <Icon name={enlarged ? 'collapse' : 'expand'} size={16} /></button></Tooltip>
    </div>}
    <ScrollArea ref={messagesArea} id="assistant-messages" className="conversationMessages" hidden={!expanded}
      onScroll={event => { const area = event.currentTarget; atBottom.current = area.scrollHeight - area.scrollTop - area.clientHeight < 48; if (atBottom.current) setUnseen(false) }}>
      <div className="assistantInner messageList">
        {chat.loading && <p className="muted">正在恢复对话…</p>}
        {chat.messages.map(m => <ConversationTurn key={m.id} message={m} run={chat.runs[m.runId]} chapterNames={chapterNames}
          review={review} connection={chat.connection} onOpenProposal={viewProposal} onSource={(value, run) => setSource({ value, run })}
          onRetry={chat.retry} canRetry={lastUser?.id === m.id && !chat.taskActive && !chat.busy && !projectBusy} />)}
        {!chat.loading && chat.messages.length === 0 && <p className="assistantEmptyMessage">告诉助手你的需求，它会按需查阅作品、分析问题或提出修改建议。</p>}
        {chat.taskActive && <div className="assistantProgress" role="status"><Icon name="loading" size={16} />{status}</div>}
      </div>
      {unseen && expanded && <button className="newReplyButton" onClick={scrollToBottom}>查看新回复 ↓</button>}
    </ScrollArea>
    <form onSubmit={send} className="assistantInner conversationComposer" aria-label="写作助手输入区">
      <div className="assistantInputBox">
        <textarea ref={textarea} aria-label="对话消息" rows={2} placeholder="告诉助手你想做什么…"
          value={chat.draft.input} disabled={chat.busy}
          onFocus={() => setInputFocused(true)} onBlur={() => setInputFocused(false)}
          onChange={event => chat.updateDraft({ input: event.target.value })}
          onCompositionStart={() => { composing.current = true }} onCompositionEnd={() => { composing.current = false }}
          onKeyDown={event => {
            if (event.key === 'Enter' && !event.shiftKey && !composing.current && !event.nativeEvent.isComposing && event.keyCode !== 229) send(event)
          }} />
        <div className="assistantInputTools"><div className="assistantContext">
          <Tooltip label={skillLabel}><button type="button" className="assistantIconButton assistantSkillButton" aria-label="选择 Skill"
            disabled={chat.busy} onClick={() => setSkillPicker(true)}>
            <Icon name="skill" />{chat.draft.selected.length > 0 && <span className="assistantSkillCount">{chat.draft.selected.length}</span>}
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
    {skillPicker && <ConversationSkills chat={chat} skills={skills} loadError={skillError} onClose={() => setSkillPicker(false)} onLibrary={() => { setSkillPicker(false); setLibrary(true) }} />}
    {source && <SourceView key={source.value.id} source={source.value} step={source.run.steps?.find(s => s.toolExecutionId === source.value.toolExecutionId)} close={() => setSource(null)} />}
    {library && <SkillLibrary onClose={() => setLibrary(false)} onChange={loadSkills} />}
  </section>
}
