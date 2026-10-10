import React, { useCallback, useEffect, useRef, useState } from 'react'
import { api, readableError } from '../../shared/api/client.js'
import { Markdown } from '../../shared/ui/Markdown.jsx'
import { useRun } from '../agent/useRun.js'
import { active } from '../agent/status.js'
import { RunResult } from '../agent/RunResult.jsx'
import { SourceView } from '../history/SourceView.jsx'
import { SkillLibrary } from '../skills/SkillLibrary.jsx'
import './conversation.css'

export function ConversationPanel({ projectId, chapter, dirty, projectBusy, review, onSelectRun }) {
  const [items, setItems] = useState([]), [listCursor, setListCursor] = useState(null), [archived, setArchived] = useState(false)
  const [id, setId] = useState(() => localStorage.getItem(`mojie:conversation:${projectId}`) || '')
  const [conversation, setConversation] = useState(null), [messages, setMessages] = useState([])
  const [input, setInput] = useState(''), [type, setType] = useState('CHAT'), [error, setError] = useState(''), [busy, setBusy] = useState(false)
  const [library, setLibrary] = useState(false), [skills, setSkills] = useState([]), [pins, setPins] = useState([]), [selected, setSelected] = useState([])
  const [versions, setVersions] = useState({}), [runId, setRunId] = useState(null), [resources, setResources] = useState(null), [source, setSource] = useState(null)
  const scope = useRef(id), counter = useRef(0), pending = useRef(null), submitting = useRef(false)
  const listRequest = useRef(0), listLoadRef = useRef(null)
  const loadRef = useRef(null), onRunRef = useRef(onSelectRun)
  scope.current = id; onRunRef.current = onSelectRun
  const loadList = useCallback(async (after = null) => {
    const request = ++listRequest.current
    const value = await api(`/projects/${projectId}/conversations?status=${archived ? 'ARCHIVED' : 'ACTIVE'}&limit=50${after ? `&cursor=${encodeURIComponent(after)}` : ''}`)
    if (request !== listRequest.current) return
    setItems(current => after ? [...current, ...value.items] : value.items); setListCursor(value.nextCursor)
  }, [projectId, archived])
  listLoadRef.current = loadList
  const load = useCallback(async () => {
    if (!id) return
    const token = ++counter.current
    const [c, preferences] = await Promise.all([api(`/conversations/${id}`), api(`/conversations/${id}/skills`)])
    let page, cursor, all = []
    do { page = await api(`/conversations/${id}/messages?limit=50${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`); all.push(...page.items); cursor = page.nextCursor } while (cursor)
    if (scope.current !== id || counter.current !== token) return
    setConversation(c); setMessages(all); setPins(preferences)
    setRunId(c.activeRunId || all.at(-1)?.runId || null)
  }, [id])
  loadRef.current = load
  const loadSkills = useCallback(async () => { let all = [], cursor; do { const page = await api(`/skills?limit=50${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`); all.push(...page.items); cursor = page.nextCursor } while (cursor); setSkills(all) }, [])
  useEffect(() => { loadList().catch(e => setError(readableError(e))) }, [loadList])
  useEffect(() => { loadSkills().catch(e => setError(readableError(e))) }, [loadSkills])
  useEffect(() => { counter.current++; setConversation(null); setMessages([]); setRunId(null); setPins([]); setSelected([]); setResources(null); setSource(null); setError(''); setInput(''); pending.current = null; if (id) localStorage.setItem(`mojie:conversation:${projectId}`, id); load().catch(e => { if (scope.current === id) setError(readableError(e)) }); return () => { counter.current++ } }, [id, projectId, load])
  const onChange = useCallback(snapshot => { if (!active(snapshot.status)) { loadRef.current?.().catch(() => {}); listLoadRef.current?.().catch(() => {}) } }, [])
  const { run, connection, refresh, error: runError } = useRun(runId, onChange)
  useEffect(() => { if (runId) onRunRef.current?.(runId) }, [runId, chapter?.id])
  useEffect(() => {
    setResources(null)
    if (!runId) return
    let disposed = false
    api(`/agent/runs/${runId}/resources`).then(value => { if (!disposed) setResources(value) }).catch(() => {})
    return () => { disposed = true }
  }, [runId, run?.lastEventSeq])
  useEffect(() => { if (!conversation?.activeRunId) return; const timer = setInterval(() => loadRef.current?.().catch(() => {}), 3000); return () => clearInterval(timer) }, [conversation?.activeRunId])
  async function action(fn) { setBusy(true); setError(''); const current = id; try { await fn(); if (scope.current === current) { await load(); await loadList() } } catch (e) { if (scope.current === current) { setError(readableError(e)); if (e.code === 'CONVERSATION_VERSION_CONFLICT') { pending.current = null; await load().catch(() => {}) } } } finally { setBusy(false) } }
  async function create() { setError(''); try { const c = await api(`/projects/${projectId}/conversations`, 'POST'); setId(c.id); await loadList() } catch (e) { setError(readableError(e)) } }
  async function patch(value) { await action(() => api(`/conversations/${id}`, 'PATCH', { expectedVersion: conversation.version, ...value })) }
  async function send(event) {
    event.preventDefault(); if (submitting.current || busy || conversation?.activeRunId) return
    submitting.current = true
    const current = id
    const request = pending.current || { content: input, clientRequestId: crypto.randomUUID(), expectedConversationSeq: conversation.lastSeq, taskType: type, ...(type !== 'CHAT' ? { targetChapterId: chapter.id, expectedVersion: chapter.version } : {}), selectedSkills: selected }
    pending.current = request
    await action(async () => {
      const receipt = await api(`/conversations/${current}/messages`, 'POST', request)
      pending.current = null
      if (scope.current === current) { setInput(''); setSelected([]); setRunId(receipt.runId) }
    }); submitting.current = false
  }
  async function chooseSkill(skill) {
    const currentId = id
    const history = versions[skill.skill.id] || (await api(`/skills/${skill.skill.id}/versions?limit=50`)).items
    if (scope.current !== currentId) return
    setVersions(current => ({ ...current, [skill.skill.id]: history }))
    setSelected(current => current.some(s => s.skillId === skill.skill.id) ? current.filter(s => s.skillId !== skill.skill.id) : [...current, { skillId: skill.skill.id, versionId: pins.find(s => s.skillId === skill.skill.id)?.versionId || skill.skill.currentVersionId }]); pending.current = null
  }
  const taskActive = Boolean(conversation?.activeRunId || run && active(run.status))
  const blocked = !conversation || conversation.status !== 'ACTIVE' || taskActive || projectBusy || busy || !input.trim() || [...input].length > 8000 || type !== 'CHAT' && (!chapter || dirty)
  return <aside className="conversationPanel" aria-label="连续对话">
    <div className="conversationToolbar"><button onClick={create} disabled={busy}>新建对话</button><button className="secondary" onClick={() => setLibrary(true)}>Skill 库</button></div>
    <label>当前对话<select aria-label="当前对话" value={id} onChange={e => setId(e.target.value)}><option value="">选择或新建对话</option>{!items.some(c => c.id === id) && conversation && <option value={id}>{conversation.title}</option>}{items.map(c => <option key={c.id} value={c.id}>{c.title}{c.activeRunId ? ' · 执行中' : ''}</option>)}</select></label>
    <label className="archiveFilter"><input type="checkbox" checked={archived} onChange={e => setArchived(e.target.checked)} />查看归档</label>{listCursor && <button className="link" onClick={() => loadList(listCursor)}>更多对话</button>}
    {conversation && <div className="conversationToolbar"><button className="link" disabled={taskActive || busy} onClick={() => { const title = window.prompt('对话标题', conversation.title); if (title) patch({ title }) }}>重命名</button><button className="link" disabled={taskActive || busy} onClick={() => patch({ status: conversation.status === 'ACTIVE' ? 'ARCHIVED' : 'ACTIVE' })}>{conversation.status === 'ACTIVE' ? '归档' : '恢复'}</button><small>模型 {conversation.modelId}</small></div>}
    {(error || runError) && <p className="error" role="alert">{error || runError}</p>}
    <div className="conversationMessages">{messages.map(m => <article key={m.id} className={`conversationMessage ${m.role.toLowerCase()}`}><small>{m.role === 'USER' ? '你' : '助手'}</small>{m.role === 'USER' ? <p>{m.content}</p> : <Markdown content={m.content} />}
      {m.role === 'USER' && ['FAILED', 'CANCELLED'].includes(m.status) && <p className="muted">本轮{m.status === 'FAILED' ? '失败' : '已停止'}，未完成回答。{m.seq === conversation?.lastSeq && <button className="link" disabled={taskActive || busy || projectBusy} onClick={() => action(() => api(`/conversations/${id}/messages/${m.id}/retry`, 'POST', { clientRequestId: crypto.randomUUID() }))}>重试本轮</button>}</p>}
    </article>)}{!id && <p className="muted">新建独立对话，持续讨论人物、大纲或章节。</p>}</div>
    {conversation && <form onSubmit={send} className="conversationComposer">
      <label>本轮任务<select aria-label="本轮任务" value={type} disabled={taskActive || busy} onChange={e => { setType(e.target.value); pending.current = null }}><option value="CHAT">普通聊天</option><option value="CHAPTER_REVIEW">章节审阅</option><option value="CHAPTER_EDIT">章节修改</option></select></label>
      {type !== 'CHAT' && <p className="muted">目标：{chapter?.title || '请先选择章节'} · 版本 {chapter?.version || '—'}{dirty ? ' · 请先保存正文' : ''}</p>}
      {dirty && type === 'CHAT' && <p className="hint">当前未保存草稿未提供给模型，只讨论已保存内容。</p>}
      <details className="conversationSkills"><summary>本轮 Skill 与对话固定项 · {selected.length} / {pins.length}</summary>
        <label><input type="checkbox" checked={conversation.discoveryEnabled} disabled={taskActive || busy} onChange={e => patch({ discoveryEnabled: e.target.checked })} />允许自动发现其他 Skill</label>
        {pins.map(p => <p key={p.skillId}>固定：{skills.find(s => s.skill.id === p.skillId)?.skill.name || p.skillId} · {p.versionId}<button type="button" className="link" disabled={taskActive || busy} onClick={() => action(() => api(`/conversations/${id}/skills`, 'PUT', pins.filter(s => s.skillId !== p.skillId)))}>取消固定</button></p>)}
        {skills.filter(s => s.skill.enabled).map(s => { const choice = selected.find(p => p.skillId === s.skill.id); return <div key={s.skill.id}><label><input type="checkbox" checked={Boolean(choice)} disabled={taskActive || busy} onChange={() => chooseSkill(s).catch(e => setError(readableError(e)))} />{s.skill.name}</label>{choice && <><select aria-label={`${s.skill.name} 版本`} value={choice.versionId} onChange={e => { setSelected(current => current.map(p => p.skillId === s.skill.id ? { ...p, versionId: e.target.value } : p)); pending.current = null }}>{!(versions[s.skill.id] || []).some(v => v.id === choice.versionId) && <option value={choice.versionId}>{choice.versionId}</option>}{(versions[s.skill.id] || []).map(v => <option key={v.id} value={v.id}>{v.id}</option>)}</select><button type="button" className="link" disabled={taskActive || busy} onClick={() => action(() => api(`/conversations/${id}/skills`, 'PUT', [...pins.filter(p => p.skillId !== s.skill.id), choice]))}>固定到对话</button></>}</div> })}
      </details>
      <textarea aria-label="对话消息" placeholder="继续讨论，或提出新的写作问题…" value={input} disabled={taskActive || busy || conversation.status !== 'ACTIVE'} onChange={e => { setInput(e.target.value); pending.current = null }} />
      <div className="conversationToolbar"><small>{[...input].length}/8000</small><button disabled={blocked}>发送</button>{taskActive && <button type="button" className="secondary" onClick={() => action(() => api(`/agent/runs/${conversation.activeRunId || runId}/cancel`, 'POST'))}>停止</button>}</div>
      {projectBusy && !taskActive && <p className="hint">作品有任务正在执行，结束后可发送。</p>}
    </form>}
    {run && <details className="conversationExecution" open={active(run.status)}><summary>本轮执行详情</summary><RunResult task={{ run, connection, refresh }} review={review} onSource={setSource} />
      {resources?.skills.map(s => <p key={s.skillId} className="muted">{s.metadata.name} · {s.versionId} · {s.providedToModel ? '已进入模型请求' : s.loaded ? '已加载，尚未确认送达' : '可发现'}</p>)}
      {resources?.artifacts.map(a => <p key={a.id}><a href={`/api/v1/artifacts/${a.id}/download`} download>{a.name}</a> · {run.status === 'SUCCEEDED' ? '附件' : '未完成任务产物'} · 到期 {new Date(a.expiresAt).toLocaleDateString()}</p>)}
    </details>}
    {source && <SourceView key={source.id} source={source} step={run?.steps?.find(s => s.toolExecutionId === source.toolExecutionId)} close={() => setSource(null)} />}
    {library && <SkillLibrary onClose={() => setLibrary(false)} onChange={loadSkills} />}
  </aside>
}
