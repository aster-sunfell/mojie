import React, { useCallback, useEffect, useRef, useState } from 'react'
import { api, readableError } from '../../shared/api/client.js'
import { useRun } from './useRun.js'
import { cp } from '../../shared/utils/text.js'
import { active, statusName } from './status.js'
import { SourceView } from '../history/SourceView.jsx'
import { Proposal } from '../proposals/Proposal.jsx'

export function AgentPanel({ projectId, chapter, dirty, refreshChapter, onAcceptanceBusy }) {
  const [instruction, setInstruction] = useState('检查当前章节与人物设定、前文经历是否矛盾；如有必要，请给出修改建议。')
  const [runs, setRuns] = useState([])
  const [runCursor, setRunCursor] = useState(null)
  const [runId, setRunId] = useState(null)
  const [capabilities, setCapabilities] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [source, setSource] = useState(null)
  const pending = React.useRef(null)
  const runsRequest = useRef(0)
  const loadRuns = useCallback(async (after = null) => {
    if (!projectId || !chapter?.id) return
    const request = ++runsRequest.current
    try {
      const page = await api(`/agent/runs?projectId=${projectId}&chapterId=${chapter.id}&limit=50${after ? `&cursor=${encodeURIComponent(after)}` : ''}`)
      if (request !== runsRequest.current) return
      setRuns(current => after ? [...current, ...page.items] : page.items)
      setRunCursor(page.nextCursor)
      if (!after) setRunId(current => current || page.items[0]?.id || null)
      setError('')
    } catch (e) {
      if (request === runsRequest.current) setError(readableError(e))
    }
  }, [projectId, chapter?.id])
  useEffect(() => {
    setRuns([]); setRunCursor(null); setRunId(null); setSource(null)
    pending.current = null
    loadRuns()
    return () => { runsRequest.current++ }
  }, [loadRuns])
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
      <div className="sectionTitle"><h3>任务记录</h3><button className="link" onClick={() => loadRuns()}>刷新</button></div>
      <div className="runList">{runs.map(item => <button key={item.id} onClick={() => setRunId(item.id)} className={runId === item.id ? 'selected' : ''}><span>{statusName(item.status)}</span><small>{new Date(item.queuedAt).toLocaleString()}</small></button>)}{runs.length === 0 && <p className="muted">当前章节暂无任务</p>}</div>
      {runCursor && <button className="secondary" onClick={() => loadRuns(runCursor)}>加载更多任务</button>}
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
        {run.status === 'SUCCEEDED' && run.proposalId && <Proposal key={run.proposalId} id={run.proposalId} dirty={dirty} refreshChapter={refreshChapter} refreshRun={refresh} onAcceptanceBusy={onAcceptanceBusy} />}
      </div>}
    </div>{source && <SourceView source={source} step={run?.steps?.find(item => item.toolExecutionId === source.toolExecutionId)} close={() => setSource(null)} />}
  </aside>
}
