import React, { useCallback, useEffect, useState } from 'react'
import { api, readableError } from './api.js'
import { useRun } from './useRun.js'

function pathIds() {
  const parts = window.location.pathname.split('/').filter(Boolean)
  return { projectId: parts[0] === 'projects' ? parts[1] : null,
    chapterId: parts[2] === 'chapters' ? parts[3] : null }
}
function cp(value) { return [...(value || '')].length }
function statusName(status) {
  return ({ QUEUED: '排队中', RUNNING: '运行中', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已停止' })[status] || status
}
const active = status => status === 'QUEUED' || status === 'RUNNING'

function ProjectHome({ open }) {
  const [projects, setProjects] = useState([])
  const [title, setTitle] = useState('')
  const [error, setError] = useState('')
  const load = () => api('/projects?limit=50').then(setProjects).catch(e => setError(readableError(e)))
  useEffect(() => { load() }, [])
  async function create(event) {
    event.preventDefault()
    try { const value = await api('/projects', 'POST', { title }); setTitle(''); open(value.id) }
    catch (e) { setError(readableError(e)) }
  }
  return <main className="home"><header><span className="brand">墨界 <small>Mojie</small></span><h1>你的作品</h1><p>从章节开始写作，再让 Agent 查阅证据并给出建议。</p></header>
    <form className="card create" onSubmit={create}><label>新建作品</label><div className="row"><input value={title} onChange={e => setTitle(e.target.value)} maxLength={80} placeholder="作品名称" required /><button>创建</button></div></form>
    {error && <p className="error">{error}</p>}
    <section className="projectGrid">{projects.map(project => <button className="projectCard" onClick={() => open(project.id)} key={project.id}><strong>{project.title}</strong><span>最近更新 {new Date(project.updatedAt).toLocaleString()}</span></button>)}</section>
  </main>
}

function Settings({ projectId, disabled }) {
  const [items, setItems] = useState([])
  const [form, setForm] = useState({ type: 'CHARACTER', title: '', content: '' })
  const [editing, setEditing] = useState(null)
  const [keyword, setKeyword] = useState('')
  const [error, setError] = useState('')
  const load = useCallback(() => api(`/projects/${projectId}/settings?limit=50`).then(setItems).catch(e => setError(readableError(e))), [projectId])
  useEffect(() => { load() }, [load])
  async function save(event) {
    event.preventDefault()
    try {
      if (editing) await api(`/settings/${editing}`, 'PATCH', form)
      else await api(`/projects/${projectId}/settings`, 'POST', form)
      setForm({ type: 'CHARACTER', title: '', content: '' }); setEditing(null); setError(''); await load()
    } catch (e) { setError(readableError(e)) }
  }
  async function remove(id) {
    if (!window.confirm('确定删除这条设定？')) return
    try { await api(`/settings/${id}`, 'DELETE'); await load() }
    catch (e) { setError(readableError(e)) }
  }
  return <section className="settings"><div className="sectionTitle"><h2>作品设定</h2>{disabled && <small>任务运行期间暂不可修改设定</small>}</div>
    <input value={keyword} onChange={e => setKeyword(e.target.value)} placeholder="筛选标题或正文" />
    {error && <p className="error">{error}</p>}
    <div className="settingList">{items.filter(item => !keyword || `${item.title}${item.content}`.includes(keyword)).map(item => <article className="setting" key={item.id}>
      <small>{({ CHARACTER: '人物', WORLD: '世界观', RULE: '写作约束' })[item.type]}</small><strong>{item.title}</strong><p>{item.content}</p>
      <div className="row"><button className="link" disabled={disabled} onClick={() => { setEditing(item.id); setForm({ type: item.type, title: item.title, content: item.content }) }}>编辑</button><button className="link danger" disabled={disabled} onClick={() => remove(item.id)}>删除</button></div>
    </article>)}{items.length === 0 && <p className="muted">暂无设定</p>}</div>
    <form onSubmit={save} className="settingForm"><h3>{editing ? '编辑设定' : '新增设定'}</h3>
      <select disabled={disabled} value={form.type} onChange={e => setForm({ ...form, type: e.target.value })}><option value="CHARACTER">人物</option><option value="WORLD">世界观</option><option value="RULE">写作约束</option></select>
      <input disabled={disabled} value={form.title} maxLength={80} required placeholder="标题" onChange={e => setForm({ ...form, title: e.target.value })} />
      <textarea disabled={disabled} value={form.content} maxLength={2000} required placeholder="设定内容" onChange={e => setForm({ ...form, content: e.target.value })} />
      <div className="row"><button disabled={disabled}>保存设定</button>{editing && <button type="button" className="secondary" onClick={() => { setEditing(null); setForm({ type: 'CHARACTER', title: '', content: '' }) }}>取消</button>}</div>
    </form>
  </section>
}

function History({ chapterId, close }) {
  const [revisions, setRevisions] = useState([])
  const [selected, setSelected] = useState(null)
  const [error, setError] = useState('')
  useEffect(() => { api(`/chapters/${chapterId}/revisions?limit=50`).then(setRevisions).catch(e => setError(readableError(e))) }, [chapterId])
  async function view(version) {
    try { setSelected(await api(`/chapters/${chapterId}/revisions/${version}`)) }
    catch (e) { setError(readableError(e)) }
  }
  return <div className="overlay"><div className="drawer"><div className="sectionTitle"><h2>章节历史</h2><button className="secondary" onClick={close}>关闭</button></div>
    {error && <p className="error">{error}</p>}
    <div className="historyList">{revisions.map(item => <button key={item.version} onClick={() => view(item.version)} className={selected?.version === item.version ? 'selected' : ''}>版本 {item.version} · {item.source} <small>{new Date(item.createdAt).toLocaleString()}</small></button>)}</div>
    {selected && <><h3>只读版本 {selected.version}</h3><pre className="historicalText">{selected.content || '（空章节）'}</pre></>}
  </div></div>
}

function SourceView({ source, step, close }) {
  const [revision, setRevision] = useState(null)
  const [error, setError] = useState('')
  useEffect(() => {
    if (!source?.chapterId) return
    api(`/chapters/${source.chapterId}/revisions/${source.chapterVersion}`).then(setRevision).catch(e => setError(readableError(e)))
  }, [source])
  useEffect(() => { if (revision && source?.lineNumber) document.getElementById('source-line')?.scrollIntoView({ block: 'center' }) }, [revision, source])
  const setting = step?.result?.data?.settings?.find(item => item.settingId === source.settingId)
  const snippet = step?.result?.data?.items?.find(item => item.settingId === source.settingId && item.codePointOffset === source.codepointOffset)
  return <div className="overlay"><div className="drawer"><div className="sectionTitle"><h2>历史来源</h2><button className="secondary" onClick={close}>关闭</button></div>
    <p className="muted">{source.chapterId ? `章节版本 ${source.chapterVersion}` : '任务执行时的设定内容'} · {source.kind === 'SEARCH_SNIPPET' ? '搜索摘录' : '完整读取'} · 不会覆盖编辑器草稿</p>
    {error && <p className="error">{error}</p>}
    {setting && <><h3>{setting.title}</h3><pre className="historicalText">{setting.content}</pre></>}
    {snippet && <><h3>{snippet.title}</h3><pre className="historicalText">{snippet.snippet}</pre></>}
    {revision && <div className="sourceLines">{revision.content.split('\n').map((line, index) => <div key={index} id={index + 1 === source.lineNumber ? 'source-line' : undefined} className={index + 1 === source.lineNumber ? 'hitLine' : ''}><span>{index + 1}</span><pre>{line || ' '}</pre></div>)}</div>}
  </div></div>
}

function Proposal({ id, dirty, refreshChapter, refreshRun, onAcceptanceBusy }) {
  const [proposal, setProposal] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => { api(`/edit-proposals/${id}`).then(setProposal).catch(e => setError(readableError(e))) }, [id])
  async function decide(action) {
    setBusy(true); setError('')
    if (action === 'accept') onAcceptanceBusy(true)
    try {
      await api(`/edit-proposals/${id}/${action}`, 'POST')
      setProposal(await api(`/edit-proposals/${id}`))
      if (action === 'accept') await refreshChapter()
      await refreshRun()
    } catch (e) {
      setError(readableError(e))
      api(`/edit-proposals/${id}`).then(setProposal).catch(() => {})
    } finally { setBusy(false); if (action === 'accept') onAcceptanceBusy(false) }
  }
  if (!proposal) return error && <p className="error">{error}</p>
  return <section className="proposal"><h3>修改建议 <span className="badge">{proposal.status}</span></h3><p>{proposal.reason}</p>
    <p className="muted">基于章节版本 {proposal.baseVersion}，采纳前会再次检查当前版本。</p>
    <div className="comparison"><div><strong>任务基准原文</strong><pre>{proposal.baseContent}</pre></div><div><strong>建议稿</strong><pre>{proposal.proposedContent}</pre></div></div>
    {error && <p className="error">{error}</p>}
    {proposal.status === 'PENDING' && <div className="row"><button disabled={dirty || busy} onClick={() => decide('accept')}>整体采纳</button><button className="secondary" disabled={busy} onClick={() => decide('reject')}>拒绝</button>{dirty && <small>请先保存或放弃本地修改</small>}</div>}
    {proposal.status === 'APPLIED' && <p className="success">已保存为章节版本 {proposal.appliedVersion}</p>}
  </section>
}

function AgentPanel({ projectId, chapter, dirty, refreshChapter, onAcceptanceBusy }) {
  const [instruction, setInstruction] = useState('检查当前章节与人物设定、前文经历是否矛盾；如有必要，请给出修改建议。')
  const [runs, setRuns] = useState([])
  const [runId, setRunId] = useState(null)
  const [capabilities, setCapabilities] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [source, setSource] = useState(null)
  const pending = React.useRef(null)
  const loadRuns = useCallback(async () => {
    if (!projectId || !chapter?.id) return
    const items = await api(`/agent/runs?projectId=${projectId}&chapterId=${chapter.id}&limit=50`)
    setRuns(items)
    setRunId(current => current || items[0]?.id || null)
  }, [projectId, chapter?.id])
  useEffect(() => { setRunId(null); pending.current = null; loadRuns().catch(e => setError(readableError(e))) }, [loadRuns])
  useEffect(() => { api('/system/capabilities').then(setCapabilities).catch(() => {}) }, [])
  const onRunChange = useCallback(snapshot => {
    setRuns(items => items.map(item => item.id === snapshot.id ? { ...item, ...snapshot } : item))
  }, [])
  const { run, connection, error: runError, refresh } = useRun(runId, onRunChange)
  useEffect(() => {
    if (!run || !active(run.status)) return
    const timer = window.setInterval(() => { refresh().catch(() => {}) }, 5000)
    return () => window.clearInterval(timer)
  }, [run?.id, run?.status, refresh])
  async function submit() {
    if (!chapter || dirty) { setError('请先保存当前章节正文。'); return }
    setBusy(true); setError('')
    if (!pending.current) pending.current = {
      projectId, targetChapterId: chapter.id, expectedVersion: chapter.version,
      instruction, clientRequestId: crypto.randomUUID()
    }
    try {
      const receipt = await api('/agent/runs', 'POST', pending.current)
      pending.current = null
      setRunId(receipt.runId)
      await loadRuns()
    } catch (e) {
      setError(readableError(e))
      if (e.status && e.status !== 503) pending.current = null
    } finally { setBusy(false) }
  }
  async function cancel() {
    try { await api(`/agent/runs/${runId}/cancel`, 'POST'); await refresh() }
    catch (e) { setError(readableError(e)) }
  }
  const taskActive = runs.some(item => active(item.status)) || (run && active(run.status))
  return <aside className="agentPanel"><div className="panelHeader"><span className="eyebrow">AGENT</span><h2>章节检查</h2><p>围绕当前已保存章节查阅证据，建议由你决定是否采纳。</p></div>
    <div className="agentBody">
      {chapter ? <><div className="target"><strong>{chapter.title}</strong><small>当前版本 {chapter.version}</small></div>
        <label htmlFor="instruction">检查要求</label><textarea id="instruction" className="instruction" value={instruction} onChange={e => { setInstruction(e.target.value); if (pending.current) pending.current = null }} maxLength={2000} />
        <div className="row"><button disabled={busy || dirty || taskActive || !capabilities?.agentConfigured || cp(instruction) < 1} onClick={submit}>{run && !active(run.status) ? '重新运行' : '开始检查'}</button><small>{cp(instruction)} / 2000</small></div>
        {!capabilities?.agentConfigured && <p className="hint">模型尚未配置；章节和设定仍可编辑。</p>}{dirty && <p className="hint">当前正文未保存，保存后才能提交任务。</p>}
      </> : <p className="muted">先选择一个章节。</p>}
      {error && <p className="error">{error}</p>}
      <div className="sectionTitle"><h3>任务记录</h3><button className="link" onClick={() => loadRuns().catch(e => setError(readableError(e)))}>刷新</button></div>
      <div className="runList">{runs.map(item => <button key={item.id} onClick={() => setRunId(item.id)} className={runId === item.id ? 'selected' : ''}><span>{statusName(item.status)}</span><small>{new Date(item.queuedAt).toLocaleString()}</small></button>)}{runs.length === 0 && <p className="muted">当前章节暂无任务</p>}</div>
      {runError && <p className="error">{runError}</p>}
      {run && <div className="runDetail"><div className="sectionTitle"><h3>{statusName(run.status)}</h3>{active(run.status) && <button className="secondary" onClick={cancel} disabled={run.cancelRequested}>{run.cancelRequested ? '正在停止' : '停止'}</button>}</div>
        <p className="muted">基准版本 {run.baseVersion} · 模型 {run.modelCallCount}/8 · 工具 {run.toolCallCount}/12</p>
        {active(run.status) && <p className="hint">{connection === 'reconnecting' ? '事件连接中断，正在重连并查询状态…' : run.cancelRequested ? '正在停止…' : '正在处理，页面关闭后任务继续运行。'}</p>}
        {run.status === 'FAILED' && <p className="error">{run.errorCode}：{run.errorDetail}</p>}
        {run.analysis && <section className="analysis"><h3>最终分析</h3><p>{run.analysis}</p></section>}
        <div className="sectionTitle"><h3>执行记录</h3><small>{run.steps?.length || 0} 步</small></div>
        <div className="steps">{run.steps?.map(step => <details key={step.toolExecutionId}><summary><span>{step.toolName}</span><small>{step.status}{step.errorCode ? ` · ${step.errorCode}` : ''}</small></summary><p>{step.summary}</p><pre>{JSON.stringify(step.args, null, 2)}</pre>{step.result && <pre>{JSON.stringify(step.result, null, 2)}</pre>}</details>)}</div>
        <div className="sectionTitle"><h3>查阅来源</h3><small>{run.sources?.length || 0} 条</small></div>
        <div className="sources">{run.sources?.map(item => <button key={item.id} onClick={() => setSource(item)}><strong>{item.kind === 'SEARCH_SNIPPET' ? '搜索摘录' : item.kind === 'FULL_SETTING' ? '完整设定' : '完整章节'}</strong><small>{item.chapterId ? `版本 ${item.chapterVersion}${item.lineNumber ? ` · 第 ${item.lineNumber} 行` : ''}` : '设定'}</small><small>{item.providedToModel ? '已进入模型请求' : '仅执行，未确认送达模型'}</small></button>)}</div>
        {run.status === 'SUCCEEDED' && run.proposalId && <Proposal id={run.proposalId} dirty={dirty} refreshChapter={refreshChapter} refreshRun={refresh} onAcceptanceBusy={onAcceptanceBusy} />}
      </div>}
    </div>{source && <SourceView source={source} step={run?.steps?.find(item => item.toolExecutionId === source.toolExecutionId)} close={() => setSource(null)} />}
  </aside>
}

function Workspace({ projectId, selectedChapterId, navigate }) {
  const [project, setProject] = useState(null)
  const [chapters, setChapters] = useState([])
  const [chapter, setChapter] = useState(null)
  const [draft, setDraft] = useState('')
  const [saveStatus, setSaveStatus] = useState('已保存')
  const [error, setError] = useState('')
  const [showSettings, setShowSettings] = useState(false)
  const [showHistory, setShowHistory] = useState(false)
  const [runBusy, setRunBusy] = useState(false)
  const [accepting, setAccepting] = useState(false)
  const dirty = chapter && draft !== chapter.content
  const loadChapters = useCallback(() => api(`/projects/${projectId}/chapters?limit=50`).then(setChapters), [projectId])
  const refreshChapter = useCallback(async () => {
    if (!selectedChapterId) return null
    const value = await api(`/chapters/${selectedChapterId}`)
    setChapter(value); setDraft(value.content); setSaveStatus('已保存'); await loadChapters()
    return value
  }, [selectedChapterId, loadChapters])
  useEffect(() => { api(`/projects/${projectId}`).then(setProject).catch(e => setError(readableError(e))); loadChapters().catch(e => setError(readableError(e))) }, [projectId, loadChapters])
  useEffect(() => { setChapter(null); setDraft(''); if (selectedChapterId) refreshChapter().catch(e => setError(readableError(e))) }, [selectedChapterId, refreshChapter])
  useEffect(() => {
    const check = event => { if (dirty) { event.preventDefault(); event.returnValue = '' } }
    window.addEventListener('beforeunload', check)
    return () => window.removeEventListener('beforeunload', check)
  }, [dirty])
  const save = useCallback(async () => {
    if (!chapter || accepting) return false
    setSaveStatus('保存中')
    try {
      const value = await api(`/chapters/${chapter.id}/content`, 'PUT', { content: draft, expectedVersion: chapter.version })
      setChapter(value); setDraft(value.content); setSaveStatus('已保存'); await loadChapters(); return true
    } catch (e) { setSaveStatus(e.code === 'VERSION_CONFLICT' ? '版本冲突' : '保存失败'); setError(readableError(e)); return false }
  }, [chapter, draft, loadChapters, accepting])
  useEffect(() => { const key = event => { if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') { event.preventDefault(); save() } }; window.addEventListener('keydown', key); return () => window.removeEventListener('keydown', key) }, [save])
  async function leave(path) {
    if (accepting) return
    if (dirty) {
      const decision = window.prompt('正文未保存。输入“保存”后离开、“放弃”直接离开，或“取消”继续编辑。', '取消')
      if (decision === '保存') { if (!(await save())) return }
      else if (decision !== '放弃') return
    }
    navigate(path)
  }
  async function addChapter() {
    const title = window.prompt('新章节标题')
    if (!title) return
    try { const created = await api(`/projects/${projectId}/chapters`, 'POST', { title }); await loadChapters(); await leave(`/projects/${projectId}/chapters/${created.id}`) }
    catch (e) { setError(readableError(e)) }
  }
  useEffect(() => {
    if (!projectId) return
    const timer = window.setInterval(() => api(`/agent/runs?projectId=${projectId}&limit=20`).then(items => setRunBusy(items.some(item => active(item.status)))).catch(() => {}), 5000)
    return () => window.clearInterval(timer)
  }, [projectId])
  if (error && !project) return <main className="home"><p className="error">{error}</p><button onClick={() => navigate('/')}>返回作品列表</button></main>
  return <div className="workspace"><nav className="sidebar"><button className="back" onClick={() => leave('/')}>← 全部作品</button><h1>{project?.title || '加载中…'}</h1><div className="sectionTitle"><h2>章节</h2><button className="iconButton" title="新增章节" onClick={addChapter} disabled={runBusy}>＋</button></div>
    <div className="chapterList">{chapters.map(item => <button key={item.id} className={selectedChapterId === item.id ? 'selected' : ''} onClick={() => leave(`/projects/${projectId}/chapters/${item.id}`)}><small>{String(item.chapterOrder).padStart(2, '0')}</small><strong>{item.title}</strong><small>v{item.version}</small></button>)}</div>
    <button className="settingsToggle" onClick={() => setShowSettings(!showSettings)}>{showSettings ? '关闭设定' : '管理设定'}</button>
  </nav><main className="editorArea">{showSettings ? <Settings projectId={projectId} disabled={runBusy} /> : chapter ? <><div className="editorHeader"><div><span className="eyebrow">CHAPTER {chapter.chapterOrder}</span><h1>{chapter.title}</h1><p>版本 {chapter.version} · {cp(draft)} 字 · {saveStatus}</p></div><div className="row"><button className="secondary" onClick={() => setShowHistory(true)}>查看历史</button><button onClick={save} disabled={!dirty || accepting || saveStatus === '保存中'}>保存正文</button></div></div>
      {error && <p className="error">{error}</p>}
      <textarea className="editor" value={draft} disabled={accepting} onChange={e => { setDraft(e.target.value); setSaveStatus('未保存') }} placeholder="从这里开始写作…" />
      {showHistory && <History chapterId={chapter.id} close={() => setShowHistory(false)} />}
    </> : <div className="emptyEditor">从左侧选择章节，或新建第一章。</div>}</main>
    <AgentPanel projectId={projectId} chapter={chapter} dirty={dirty} refreshChapter={refreshChapter} onAcceptanceBusy={setAccepting} />
  </div>
}

export default function App() {
  const [location, setLocation] = useState(pathIds)
  useEffect(() => { const listener = () => setLocation(pathIds()); window.addEventListener('popstate', listener); return () => window.removeEventListener('popstate', listener) }, [])
  const navigate = useCallback(path => { window.history.pushState({}, '', path); setLocation(pathIds()) }, [])
  return location.projectId ? <Workspace projectId={location.projectId} selectedChapterId={location.chapterId} navigate={navigate} /> : <ProjectHome open={id => navigate(`/projects/${id}`)} />
}
