import React, { useCallback, useEffect, useRef, useState } from 'react'
import { api, readableError } from '../../shared/api/client.js'
import { AgentPanel } from '../agent/AgentPanel.jsx'
import { ChapterEditor } from '../editor/ChapterEditor.jsx'
import { active } from '../agent/status.js'

export function Workspace({ projectId, selectedChapterId, navigate }) {
  const [project, setProject] = useState(null)
  const [chapters, setChapters] = useState([])
  const [chapterCursor, setChapterCursor] = useState(null)
  const [chapter, setChapter] = useState(null)
  const [draft, setDraft] = useState('')
  const [saveStatus, setSaveStatus] = useState('已保存')
  const [error, setError] = useState('')
  const [showSettings, setShowSettings] = useState(false)
  const [showHistory, setShowHistory] = useState(false)
  const [runBusy, setRunBusy] = useState(false)
  const [accepting, setAccepting] = useState(false)
  const selectedRef = useRef(selectedChapterId)
  const chapterRef = useRef(chapter)
  const draftRef = useRef(draft)
  const saveInFlight = useRef(null)
  selectedRef.current = selectedChapterId
  chapterRef.current = chapter
  const dirty = chapter && draft !== chapter.content
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
  }, [selectedChapterId, refreshChapter])
  useEffect(() => {
    const check = event => { if (dirty) { event.preventDefault(); event.returnValue = '' } }
    window.addEventListener('beforeunload', check)
    return () => window.removeEventListener('beforeunload', check)
  }, [dirty])
  const save = useCallback(() => {
    if (saveInFlight.current) return saveInFlight.current
    if (!chapter || accepting) return Promise.resolve(false)
    const chapterId = chapter.id
    const submitted = draftRef.current
    setSaveStatus('保存中')
    const pending = (async () => {
      try {
        const value = await api(`/chapters/${chapterId}/content`, 'PUT', { content: submitted, expectedVersion: chapter.version })
        if (selectedRef.current !== chapterId) return false
        chapterRef.current = value; setChapter(value)
        setChapters(items => items.map(item => item.id === chapterId
          ? { ...item, title: value.title, version: value.version } : item))
        const unchanged = draftRef.current === submitted
        if (unchanged) { draftRef.current = value.content; setDraft(value.content) }
        setSaveStatus(unchanged ? '已保存' : '未保存')
        return selectedRef.current === chapterId && draftRef.current === submitted
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
  }, [chapter, accepting])
  useEffect(() => { const key = event => { if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') { event.preventDefault(); save() } }; window.addEventListener('keydown', key); return () => window.removeEventListener('keydown', key) }, [save])
  async function prepareLeave() {
    if (accepting) return
    if (dirty) {
      const decision = window.prompt('正文未保存。输入“保存”后离开、“放弃”直接离开，或“取消”继续编辑。', '取消')
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
  return <div className="workspace"><nav className="sidebar"><button className="back" onClick={() => leave('/')}>← 全部作品</button><h1>{project?.title || '加载中…'}</h1><div className="sectionTitle"><h2>章节</h2><button className="iconButton" title="新增章节" onClick={addChapter} disabled={runBusy}>＋</button></div>
    <div className="chapterList">{chapters.map(item => <button key={item.id} className={selectedChapterId === item.id ? 'selected' : ''} onClick={() => leave(`/projects/${projectId}/chapters/${item.id}`)}><small>{String(item.chapterOrder).padStart(2, '0')}</small><strong>{item.title}</strong><small>v{item.version}</small></button>)}</div>
    {chapterCursor && <button className="secondary" onClick={() => loadChapters(chapterCursor).catch(e => setError(readableError(e)))}>加载更多章节</button>}
    <button className="settingsToggle" onClick={() => setShowSettings(!showSettings)}>{showSettings ? '关闭设定' : '管理设定'}</button>
  </nav><ChapterEditor projectId={projectId} chapter={chapter} draft={draft}
    saveStatus={saveStatus} dirty={dirty} accepting={accepting} error={error}
    showSettings={showSettings} runBusy={runBusy} showHistory={showHistory}
    onHistoryChange={setShowHistory} onSave={save}
    onDraftChange={value => { draftRef.current = value; setDraft(value); setSaveStatus('未保存') }} />
    <AgentPanel key={`${projectId}:${chapter?.id || ''}`} projectId={projectId} chapter={chapter} dirty={dirty} refreshChapter={refreshChapter} onAcceptanceBusy={setAccepting} />
  </div>
}
