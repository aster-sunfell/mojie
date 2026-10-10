import React, { useCallback, useEffect, useRef, useState } from 'react'
import { api, readableError } from '../../shared/api/client.js'
import { ConversationPanel } from '../conversation/ConversationPanel.jsx'
import { AgentPanel } from '../agent/AgentPanel.jsx'
import { AgentComposer } from '../agent/AgentComposer.jsx'
import { useAgentTask } from '../agent/useAgentTask.js'
import { ChapterEditor } from '../editor/ChapterEditor.jsx'
import { active } from '../agent/status.js'
import { ChapterSidebar } from './ChapterSidebar.jsx'
import { useMediaQuery } from '../../shared/useMediaQuery.js'
import { Drawer } from '../../shared/ui/Drawer.jsx'
import { Icon } from '../../shared/ui/Icon.jsx'
import { useProposalReview } from '../proposals/useProposalReview.js'

export function Workspace({ projectId, selectedChapterId, navigate }) {
  const [project, setProject] = useState(null)
  const [chapters, setChapters] = useState([])
  const [chapterCursor, setChapterCursor] = useState(null)
  const [chapter, setChapter] = useState(null)
  const [draft, setDraft] = useState('')
  const [saveStatus, setSaveStatus] = useState('已保存')
  const [error, setError] = useState('')
  const [assistantMode, setAssistantMode] = useState('legacy')
  const [showSettings, setShowSettings] = useState(false)
  const [showHistory, setShowHistory] = useState(false)
  const [runBusy, setRunBusy] = useState(false)
  const accepting = false
  const [showAssistant, setShowAssistant] = useState(false)
  const [showChapters, setShowChapters] = useState(false)
  const [showComposer, setShowComposer] = useState(false)
  const compact = useMediaQuery('(max-width: 1100px)')
  const mobile = useMediaQuery('(max-width: 640px)')
  const selectedRef = useRef(selectedChapterId)
  const chapterRef = useRef(chapter)
  const draftRef = useRef(draft)
  const saveInFlight = useRef(null)
  const reviewDirtyRef = useRef(false)
  selectedRef.current = selectedChapterId
  chapterRef.current = chapter
  const dirty = chapter && (draft !== chapter.content || reviewDirtyRef.current)
  const task = useAgentTask({ projectId, chapterId: selectedChapterId, chapter,
    dirty, accepting, projectBusy: runBusy })
  const changeDraft = useCallback(value => {
    draftRef.current = value; setDraft(value); setSaveStatus('未保存')
  }, [])
  const review = useProposalReview({ chapterId: selectedChapterId, run: task.run, draftRef,
    onDraftChange: changeDraft, onDirtyChange: value => {
      reviewDirtyRef.current = value
      if (value) setSaveStatus(current => current === '保存中' ? current : '未保存')
      else if (draftRef.current === chapterRef.current?.content)
        setSaveStatus(current => current === '保存中' ? current : '已保存')
    } })
  useEffect(() => { setShowComposer(false) }, [review.id])
  const loadChapters = useCallback((after = null) => api(`/projects/${projectId}/chapters?limit=50${after ? `&cursor=${encodeURIComponent(after)}` : ''}`)
    .then(page => {
      setChapters(current => after
        ? [...new Map([...current, ...page.items].map(item => [item.id, item])).values()]
          .sort((a, b) => a.chapterOrder - b.chapterOrder)
        : page.items)
      setChapterCursor(page.nextCursor)
    }), [projectId])
  const refreshChapter = useCallback(async () => {
    if (!selectedChapterId) return null
    const value = await api(`/chapters/${selectedChapterId}`)
    if (selectedRef.current !== selectedChapterId) return null
    const hasLocalDraft = chapterRef.current?.id === selectedChapterId
      && draftRef.current !== chapterRef.current.content
    chapterRef.current = value
    setChapter(value)
    setChapters(items => items.map(item => item.id === value.id
      ? { ...item, title: value.title, version: value.version } : item))
    if (!hasLocalDraft) { draftRef.current = value.content; setDraft(value.content); setSaveStatus('已保存') }
    else setSaveStatus('未保存')
    return value
  }, [selectedChapterId])
  useEffect(() => { api(`/projects/${projectId}`).then(setProject).catch(e => setError(readableError(e))); loadChapters().catch(e => setError(readableError(e))) }, [projectId, loadChapters])
  useEffect(() => {
    chapterRef.current = null; draftRef.current = ''
    setChapter(null); setDraft('')
    if (selectedChapterId) refreshChapter().catch(e => setError(readableError(e)))
    setShowChapters(false)
  }, [selectedChapterId, refreshChapter])
  useEffect(() => {
    const check = event => { if (dirty || review.hasPendingEdits) { event.preventDefault(); event.returnValue = '' } }
    window.addEventListener('beforeunload', check)
    return () => window.removeEventListener('beforeunload', check)
  }, [dirty, review.hasPendingEdits])
  const save = useCallback(() => {
    if (saveInFlight.current) return saveInFlight.current
    if (!chapter || accepting) return Promise.resolve(false)
    const chapterId = chapter.id
    const submitted = draftRef.current
    const proposalReviews = review.snapshot()
    setSaveStatus('保存中')
    const pending = (async () => {
      try {
        const value = await api(`/chapters/${chapterId}/content`, 'PUT', { content: submitted, expectedVersion: chapter.version,
          ...(proposalReviews.length ? { proposalReviews } : {}) })
        if (selectedRef.current !== chapterId) return false
        chapterRef.current = value; setChapter(value)
        setChapters(items => items.map(item => item.id === chapterId
          ? { ...item, title: value.title, version: value.version } : item))
        review.commit(proposalReviews, value.version)
        const unchanged = draftRef.current === submitted && !reviewDirtyRef.current
        if (unchanged) { draftRef.current = value.content; setDraft(value.content) }
        setSaveStatus(unchanged ? '已保存' : '未保存')
        setError('')
        return selectedRef.current === chapterId && draftRef.current === submitted && !reviewDirtyRef.current
      } catch (e) {
        if (selectedRef.current === chapterId) {
          setSaveStatus(e.code === 'VERSION_CONFLICT' ? '版本冲突' : '保存失败')
          setError(readableError(e))
        }
        return false
      }
    })()
    saveInFlight.current = pending
    pending.finally(() => { if (saveInFlight.current === pending) saveInFlight.current = null })
    return pending
  }, [chapter, accepting, review.snapshot, review.commit])
  useEffect(() => { const key = event => { if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') { event.preventDefault(); save() } }; window.addEventListener('keydown', key); return () => window.removeEventListener('keydown', key) }, [save])
  async function prepareLeave() {
    if (accepting) return
    if (dirty || review.hasPendingEdits) {
      const decision = window.prompt(review.hasPendingEdits
        ? '还有未应用的建议调整。输入“保存”保存正文后离开（未应用的建议调整会丢弃）、“放弃”直接离开，或“取消”继续编辑。'
        : '正文未保存。输入“保存”后离开、“放弃”直接离开，或“取消”继续编辑。', '取消')
      if (decision === '保存') return await save()
      if (decision !== '放弃') return false
    }
    return true
  }
  async function leave(path) {
    if (!(await prepareLeave())) return
    navigate(path)
  }
  async function addChapter() {
    const title = window.prompt('新章节标题')
    if (!title) return
    if (!(await prepareLeave())) return
    try {
      const created = await api(`/projects/${projectId}/chapters`, 'POST', { title })
      setChapters(items => [...items, created].sort((a, b) => a.chapterOrder - b.chapterOrder))
      navigate(`/projects/${projectId}/chapters/${created.id}`)
    }
    catch (e) { setError(readableError(e)) }
  }
  useEffect(() => {
    if (!projectId) return
    const timer = window.setInterval(() => api(`/agent/runs?projectId=${projectId}&limit=20`).then(page => setRunBusy(page.items.some(item => active(item.status)))).catch(() => {}), 5000)
    return () => window.clearInterval(timer)
  }, [projectId])
  if (error && !project) return <main className="home"><p className="error">{error}</p><button onClick={() => navigate('/')}>返回作品列表</button></main>
  const sidebar = <ChapterSidebar projectId={projectId} project={project} chapters={chapters} selectedChapterId={selectedChapterId}
    busy={runBusy || task.taskActive} cursor={chapterCursor} showSettings={showSettings}
    onLeave={leave} onAddChapter={addChapter}
    onLoadMore={() => loadChapters(chapterCursor).catch(e => setError(readableError(e)))}
    onSettingsToggle={() => { setShowSettings(!showSettings); setShowChapters(false) }} />
  const panel = <div className="assistantContainer">
    <nav className="assistantTabs" aria-label="助手模式"><button className="secondary" aria-pressed={assistantMode === 'legacy'} onClick={() => setAssistantMode('legacy')}>章节任务</button><button className="secondary" aria-pressed={assistantMode === 'conversation'} onClick={() => setAssistantMode('conversation')}>连续对话</button></nav>
    {assistantMode === 'conversation' ? <ConversationPanel key={projectId} projectId={projectId} chapter={chapter} dirty={dirty} projectBusy={runBusy} review={review} onSelectRun={task.selectRun} /> : <AgentPanel task={task} chapter={chapter} review={review} embedded={compact} />}
  </div>

  return <div className="workspace">
    {!mobile && sidebar}
    <div className="writingColumn">
      {compact && <div className="workspaceToolbar">
        {mobile && <button className="secondary" onClick={() => setShowChapters(true)}>
          <Icon name="menu" size={16} />章节
        </button>}
        <button className="secondary assistantToggle" onClick={() => setShowAssistant(true)}>
          <Icon name="message" size={16} />{task.taskActive ? '查看进度' : '助手结果'}
        </button>
      </div>}
      <ChapterEditor projectId={projectId} chapter={chapter} draft={draft}
    saveStatus={saveStatus} dirty={dirty} accepting={accepting} error={error}
    showSettings={showSettings} runBusy={runBusy} showHistory={showHistory}
    onHistoryChange={setShowHistory} onSave={save}
    review={review}
    onDraftChange={changeDraft} />
      {assistantMode === 'legacy' && chapter && chapter.id === selectedChapterId && !showSettings && (review.proposal && !showComposer
        ? <div className="reviewFooter"><button className="secondary" onClick={() => setShowComposer(true)}>向助手提要求</button></div>
        : <>{review.proposal && <div className="reviewFooter"><button className="link" onClick={() => setShowComposer(false)}>收起输入框</button></div>}
          <AgentComposer chapter={chapter} task={task} dirty={dirty} onSave={save} saveStatus={saveStatus} /></>)}
    </div>
    {!compact && panel}
    {compact && showAssistant && <Drawer title="写作助手" onClose={() => setShowAssistant(false)}>{panel}</Drawer>}
    {mobile && showChapters && <Drawer title="章节导航" onClose={() => setShowChapters(false)}>{sidebar}</Drawer>}
  </div>
}
