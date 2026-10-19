import { NewChapterProposal } from '../proposals/NewChapterProposal.jsx'
import React, { useCallback, useEffect, useRef, useState } from 'react'
import { api, readableError } from '../../shared/api/client.js'
import { ConversationPanel } from '../conversation/ConversationPanel.jsx'
import { ChapterEditor } from '../editor/ChapterEditor.jsx'
import { active } from '../agent/status.js'
import { ChapterSidebar } from './ChapterSidebar.jsx'
import { useMediaQuery } from '../../shared/useMediaQuery.js'
import { Drawer } from '../../shared/ui/Drawer.jsx'
import { Icon } from '../../shared/ui/Icon.jsx'
import { Settings } from '../settings/Settings.jsx'
import { useProposalReview } from '../proposals/useProposalReview.js'
import { useProposalInbox } from '../proposals/useProposalInbox.js'
import { ProposalInbox } from '../proposals/ProposalInbox.jsx'

export function Workspace({ projectId, selectedChapterId, navigate, onSettings, applicationSettingsOpen, skillRevision }) {
  const [project, setProject] = useState(null)
  const [chapters, setChapters] = useState([])
  const [chapterCursor, setChapterCursor] = useState(null)
  const [chapter, setChapter] = useState(null)
  const [draft, setDraft] = useState('')
  const [saveStatus, setSaveStatus] = useState('已保存')
  const [error, setError] = useState('')
  const [reviewRun, setReviewRun] = useState(null)
  const [showSettings, setShowSettings] = useState(false)
  const [settingProposalId, setSettingProposalId] = useState(null)
  const [newChapterProposalId, setNewChapterProposalId] = useState(null)
  const [proposalStatuses, setProposalStatuses] = useState({})
  const inbox = useProposalInbox(projectId)
  const [inboxOpen, setInboxOpen] = useState(() => new URLSearchParams(window.location.search).get('proposals') === 'pending')
  const [conversationRequest, setConversationRequest] = useState(null)
  const conversationRequestId = useRef(0)
  function closeInbox() {
    setInboxOpen(false)
    const url = new URL(window.location.href)
    if (url.searchParams.get('proposals') === 'pending') {
      url.searchParams.delete('proposals')
      window.history.replaceState({}, '', url.pathname + url.search + url.hash)
    }
  }
  const proposalChanged = useCallback((id, status) => {
    setProposalStatuses(current => ({ ...current, [id]: status }))
    inbox.refresh()
  }, [inbox.refresh])
  const [showHistory, setShowHistory] = useState(false)
  const [runBusy, setRunBusy] = useState(false)
  const [assistantHasReply, setAssistantHasReply] = useState(false)
  const accepting = false
  const [showChapters, setShowChapters] = useState(false)
  const mobile = useMediaQuery('(max-width: 640px)')
  const compact = useMediaQuery('(max-width: 1199px)')
  const [readingMode, setReadingMode] = useState(() => selectedChapterId ? compact ? 'chapter' : 'split' : 'conversation')
  const previousChapterId = useRef(selectedChapterId)
  const layoutMode = !selectedChapterId ? 'conversation' : readingMode === 'split' && compact ? 'chapter' : readingMode
  useEffect(() => {
    if (previousChapterId.current === selectedChapterId) return
    previousChapterId.current = selectedChapterId
    setReadingMode(current => !selectedChapterId ? 'conversation' : current === 'chapter' || compact ? 'chapter' : 'split')
  }, [selectedChapterId, compact])
  const selectedRef = useRef(selectedChapterId)
  const chapterRef = useRef(chapter)
  const draftRef = useRef(draft)
  const saveInFlight = useRef(null)
  const saveRequest = useRef(null)
  const reviewDirtyRef = useRef(false)
  const workspaceRef = useRef(null)
  selectedRef.current = selectedChapterId
  chapterRef.current = chapter
  const dirty = chapter && (draft !== chapter.content || reviewDirtyRef.current)
  useEffect(() => {
    const viewport = window.visualViewport
    if (!mobile || !viewport) return
    const resize = () => { if (workspaceRef.current) workspaceRef.current.style.height = `${viewport.height}px` }
    resize(); viewport.addEventListener('resize', resize)
    return () => { viewport.removeEventListener('resize', resize); if (workspaceRef.current) workspaceRef.current.style.height = '' }
  }, [mobile])
  const changeDraft = useCallback(value => {
    draftRef.current = value; setDraft(value); setSaveStatus('未保存')
  }, [])
  const review = useProposalReview({ chapterId: selectedChapterId,
    run: reviewRun?.targetChapterId === selectedChapterId ? reviewRun : null, draftRef,
    onSave: () => saveRequest.current(),
    onStatusChange: proposalChanged,
    onDraftChange: changeDraft, onDirtyChange: value => {
      reviewDirtyRef.current = value
      if (value) setSaveStatus(current => current === '保存中' ? current : '未保存')
      else if (draftRef.current === chapterRef.current?.content)
        setSaveStatus(current => current === '保存中' ? current : '已保存')
    } })
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
    const currentChapter = chapterRef.current
    if (!currentChapter || accepting) return Promise.resolve(false)
    const chapterId = currentChapter.id
    const submitted = draftRef.current
    const proposalReviews = review.snapshot()
    const previous = saveInFlight.current
    setSaveStatus('保存中')
    const pending = (async () => {
      // Each request keeps its own draft and decisions, then uses the version
      // returned by the preceding save. A failed save stops the queued writes.
      if (previous && !(await previous)) return false
      if (selectedRef.current !== chapterId || chapterRef.current?.id !== chapterId) return false
      try {
        const decisions = review.pendingReviews(proposalReviews)
        const value = await api(`/chapters/${chapterId}/content`, 'PUT', { content: submitted, expectedVersion: chapterRef.current.version,
          ...(decisions.length ? { proposalReviews: decisions } : {}) })
        if (selectedRef.current !== chapterId) return false
        chapterRef.current = value; setChapter(value)
        setChapters(items => items.map(item => item.id === chapterId
          ? { ...item, title: value.title, version: value.version } : item))
        review.commit(decisions, value.version)
        const unchanged = draftRef.current === submitted && !reviewDirtyRef.current
        if (unchanged) { draftRef.current = value.content; setDraft(value.content) }
        setSaveStatus(saveInFlight.current !== pending ? '保存中' : unchanged ? '已保存' : '未保存')
        setError('')
        return true
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
  }, [accepting, review.snapshot, review.pendingReviews, review.commit])
  saveRequest.current = save
  useEffect(() => { const key = event => { if (!showSettings && !newChapterProposalId && !applicationSettingsOpen && (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') { event.preventDefault(); save() } }; window.addEventListener('keydown', key); return () => window.removeEventListener('keydown', key) }, [save, showSettings, newChapterProposalId, applicationSettingsOpen])
  async function prepareLeave() {
    if (accepting) return
    // An already confirmed suggestion needs no second confirmation while saving.
    while (saveInFlight.current) await saveInFlight.current
    if ((chapterRef.current && draftRef.current !== chapterRef.current.content) || reviewDirtyRef.current || review.hasPendingEdits) {
      const decision = window.prompt(review.hasPendingEdits
        ? '还有未应用的建议调整。输入“保存”保存正文后离开（未应用的建议调整会丢弃）、“放弃”直接离开，或“取消”继续编辑。'
        : '正文未保存。输入“保存”后离开、“放弃”直接离开，或“取消”继续编辑。', '取消')
      if (decision === '保存') return await save()
        && draftRef.current === chapterRef.current?.content && !reviewDirtyRef.current
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
  const busyRequest = useRef(0)
  const refreshBusy = useCallback(async () => {
    const request = ++busyRequest.current
    try {
      const page = await api(`/agent/runs?projectId=${projectId}&limit=20`)
      if (request === busyRequest.current) setRunBusy(page.items.some(item => active(item.status)))
    } catch { /* Keep the last known activity state until the next poll. */ }
  }, [projectId])
  useEffect(() => {
    refreshBusy()
    const timer = window.setInterval(refreshBusy, 5000)
    return () => { window.clearInterval(timer); busyRequest.current++ }
  }, [refreshBusy])
  const refreshActivity = useCallback(() => { refreshBusy(); inbox.refresh() }, [refreshBusy, inbox.refresh])
  async function openProposal(run) {
    if (run?.status !== 'SUCCEEDED' || !run.proposalId) return false
    if (run.targetType === 'SETTING') {
      setSettingProposalId(run.proposalId); setShowSettings(true)
      return true
    }
    if (run.operation === 'CREATE') { setNewChapterProposalId(run.proposalId); return true }
    if (!run.targetChapterId) return false
    if (run.targetChapterId !== selectedRef.current) {
      if (!(await prepareLeave())) return false
      setReviewRun(run)
      navigate(`/projects/${projectId}/chapters/${run.targetChapterId}`)
    } else {
      // Same-chapter review sessions remain cached, including unsaved choices.
      setReviewRun(run)
      requestAnimationFrame(() => workspaceRef.current?.querySelector('.inlineChange')?.scrollIntoView?.({ block: 'start', behavior: 'smooth' }))
    }
    setReadingMode(compact ? 'chapter' : 'split')
    return true
  }
  const getContext = useCallback(() => ({ chapter: chapterRef.current }), [])
  useEffect(() => {
    if (!review.proposal || review.loading) return
    const frame = requestAnimationFrame(() => workspaceRef.current?.querySelector('.inlineChange')?.scrollIntoView?.({ block: 'start' }))
    return () => cancelAnimationFrame(frame)
  }, [review.id, review.loading])
  if (error && !project) return <main className="home"><p className="error">{error}</p><button onClick={() => navigate('/')}>返回作品列表</button></main>
  const sidebar = <ChapterSidebar projectId={projectId} project={project} chapters={chapters} selectedChapterId={selectedChapterId}
    busy={runBusy} cursor={chapterCursor} inert={showSettings || !!newChapterProposalId || inboxOpen}
    onLeave={leave} onAddChapter={addChapter}
    onLoadMore={() => loadChapters(chapterCursor).catch(e => setError(readableError(e)))} />

  return <div ref={workspaceRef} className="workspace">
    {!mobile && sidebar}
    <div className="writingColumn" inert={showSettings || !!newChapterProposalId || inboxOpen}>
      <header className="workspaceToolbar" aria-label="作品工具栏">
        {mobile && <button className="secondary" onClick={() => setShowChapters(true)}>
          <Icon name="menu" size={16} />章节
        </button>}
        <div className="readingModes" role="group" aria-label="阅读布局">
          <button aria-pressed={layoutMode === 'chapter'} disabled={!selectedChapterId}
            onClick={() => setReadingMode('chapter')}><Icon name="book" size={16} />专注章节</button>
          {!compact && <button aria-pressed={layoutMode === 'split'} disabled={!selectedChapterId}
            onClick={() => setReadingMode('split')}><Icon name="columns" size={16} />并排阅读</button>}
          <button aria-pressed={layoutMode === 'conversation'} onClick={() => setReadingMode('conversation')}>
            <Icon name="message" size={16} />专注对话{assistantHasReply && <span className="assistantReplyBadge" role="status">有新回复</span>}</button>
        </div>
        <div className="proposalInboxEntry"><button className={`secondary proposalInboxButton ${inbox.counts && inbox.counts.pendingCount + inbox.counts.conflictedCount > 0 ? 'hasSuggestions' : ''}`}
          aria-haspopup="dialog" title={inbox.error ? '建议状态暂不可用，点击重试查看' : '查看本作品所有对话的未处理建议'}
          onClick={() => { inbox.refresh(); setInboxOpen(true) }}>
          <Icon name="edit" size={16} />待处理建议
          <span className="proposalInboxCount">{inbox.counts ? inbox.counts.pendingCount + inbox.counts.conflictedCount : '—'}</span>
        </button>{inbox.arrival && <span className="proposalInboxArrival" role="status">新增 {inbox.arrival.count} 份待处理建议</span>}</div>
        <button className="secondary workspaceSettingsButton" aria-haspopup="dialog" onClick={() => { setSettingProposalId(null); setShowSettings(true) }}>
          <Icon name="book" size={16} />作品设定
        </button>
        <button className="secondary appSettingsButton" aria-haspopup="dialog" onClick={onSettings}><Icon name="settings" size={17} />设置</button>
      </header>
      <div className={`workspacePanes ${layoutMode}`}>
        <div className="chapterPane" hidden={layoutMode === 'conversation'}>
          <ChapterEditor chapter={chapter} draft={draft}
            saveStatus={saveStatus} dirty={dirty} accepting={accepting} error={error}
            showHistory={showHistory}
            onHistoryChange={setShowHistory} onSave={save}
            review={review}
            onDraftChange={changeDraft} />
        </div>
        <ConversationPanel key={projectId} projectId={projectId} chapter={chapter} dirty={dirty} skillRevision={skillRevision}
          visible={layoutMode !== 'chapter'} onUnreadChange={setAssistantHasReply}
          projectBusy={runBusy} review={review} getContext={getContext} proposalStatuses={proposalStatuses}
          onOpenProposal={openProposal} onActivityChange={refreshActivity} requestedConversation={conversationRequest} />
      </div>
    </div>
    {mobile && showChapters && <Drawer title="章节导航" onClose={() => setShowChapters(false)}>{sidebar}</Drawer>}
    {newChapterProposalId && <NewChapterProposal key={newChapterProposalId} proposalId={newChapterProposalId} disabled={runBusy}
      onClose={() => setNewChapterProposalId(null)}
      onStatusChange={proposalChanged}
      onSaved={async id => {
        await loadChapters()
        if (await prepareLeave()) { setNewChapterProposalId(null); navigate(`/projects/${projectId}/chapters/${id}`) }
      }} />}
    {showSettings && <Settings key={projectId} projectId={projectId} disabled={runBusy} proposalId={settingProposalId}
      onProposalChange={proposalChanged}
      onClose={() => { setShowSettings(false); setSettingProposalId(null) }} />}
    {inboxOpen && <ProposalInbox projectId={projectId} counts={inbox.counts} onRefresh={inbox.refresh}
      onReview={openProposal} onClose={closeInbox}
      onConversation={item => {
        setConversationRequest({ id: item.conversationId, runId: item.runId, requestId: ++conversationRequestId.current })
        setReadingMode(selectedRef.current && !compact ? 'split' : 'conversation')
      }} />}
  </div>
}
